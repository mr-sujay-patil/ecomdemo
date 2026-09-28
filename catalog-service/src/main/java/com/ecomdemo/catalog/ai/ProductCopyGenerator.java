package com.ecomdemo.catalog.ai;

import com.ecomdemo.catalog.Product;
import com.ecomdemo.shared.ServiceUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * The one place this service talks to a language model. It turns a product into a prompt, sends
 * it, and turns the reply back into a validated {@link ProductCopy} - or into a 503.
 *
 * <h2>Why every failure is a 503, and none is a 500</h2>
 *
 * A model is a dependency like inventory-service, only slower and less predictable: it can be
 * unconfigured, down, rate-limited, slower than the timeout, or answer with something that is not
 * the JSON it was asked for. None of those is a bug in this service, and none of them may change
 * the product. So each one becomes the shared {@link ServiceUnavailableException} - "try again
 * later" with a Retry-After - and the caller's product is exactly as it was. That is what
 * "degrades gracefully" means here: the catalogue keeps working, and one admin button says "not
 * now".
 *
 * <h2>Why the converter is called by hand</h2>
 *
 * {@code ChatClient...call().entity(ProductCopy.class)} would do the same in one line, but it
 * sends the request and parses the reply inside the same call, so a timeout and a reply that is
 * not JSON arrive as the same kind of exception. Splitting them lets the metrics and the log say
 * which one happened, and "the model is down" and "the model is misbehaving" are fixed in
 * different places.
 */
@Component
public class ProductCopyGenerator {

    private static final Logger log = LoggerFactory.getLogger(ProductCopyGenerator.class);

    static final String GENERATIONS = "ecomdemo.ai.generations";
    static final String TOKENS = "ecomdemo.ai.tokens";

    /** How long a client should wait before trying again after a failed generation. */
    private static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    private final ChatClient chatClient;
    private final Resource systemPrompt;
    private final Resource userPrompt;
    private final Validator validator;
    private final MeterRegistry meterRegistry;
    private final BeanOutputConverter<ProductCopy> converter = new BeanOutputConverter<>(ProductCopy.class);

    /**
     * Two {@link ObjectProvider}s, because the model may legitimately not exist: while
     * {@code AI_CHAT_PROVIDER=none} no {@code ChatModel} is created. Spring AI still REGISTERS its
     * {@code ChatClient.Builder}, and resolving that builder without a model fails - so the model
     * is asked for first, and the builder only when there is one. A plain constructor parameter
     * would turn "no provider configured" into "the service does not start".
     */
    public ProductCopyGenerator(
            ObjectProvider<ChatModel> chatModel,
            ObjectProvider<ChatClient.Builder> chatClientBuilder,
            @Value("classpath:prompts/product-description-system.st") Resource systemPrompt,
            @Value("classpath:prompts/product-description-user.st") Resource userPrompt,
            Validator validator,
            MeterRegistry meterRegistry) {
        this.chatClient = chatModel.getIfAvailable() == null ? null : chatClientBuilder.getObject().build();
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.validator = validator;
        this.meterRegistry = meterRegistry;
    }

    public boolean isConfigured() {
        return chatClient != null;
    }

    public GeneratedCopy generate(Product product) {
        if (chatClient == null) {
            count("not_configured");
            throw new ServiceUnavailableException(
                    "Description generation is not configured. Set AI_CHAT_PROVIDER to openai (with "
                            + "OPENAI_API_KEY) or ollama, and restart catalog-service.",
                    Duration.ofMinutes(5), null);
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(user -> user.text(userPrompt)
                            .param("name", product.getName())
                            .param("category", orNone(product.getCategory()))
                            .param("price", product.getPrice().toPlainString())
                            .param("description", orNone(product.getDescription()))
                            .param("format", converter.getFormat()))
                    .call()
                    .chatResponse();
        } catch (RuntimeException e) {
            // The message only: the exception may carry the request, and the request carries
            // the prompt. Never the key - Spring AI does not put it in exceptions, and nor do we.
            log.warn("Description generation for product {} failed: {}", product.getId(), e.toString());
            stop(sample, "failed");
            throw new ServiceUnavailableException(
                    "The description generator did not answer. The product was not changed.", RETRY_AFTER, e);
        }

        recordTokens(response);
        ProductCopy copy = parse(product.getId(), response);
        if (copy == null) {
            stop(sample, "invalid");
            throw new ServiceUnavailableException(
                    "The description generator returned an unusable answer. The product was not changed.",
                    RETRY_AFTER, null);
        }

        stop(sample, "success");
        Usage usage = usage(response);
        return new GeneratedCopy(
                normalise(copy),
                response.getMetadata() == null ? null : response.getMetadata().getModel(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
    }

    /** Null when the reply is missing, is not the JSON asked for, or breaks {@link ProductCopy}'s limits. */
    private ProductCopy parse(Long productId, ChatResponse response) {
        String text = response == null || response.getResult() == null
                ? null
                : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            log.warn("Description generation for product {}: empty reply", productId);
            return null;
        }
        ProductCopy copy;
        try {
            copy = converter.convert(text);
        } catch (RuntimeException e) {
            log.warn("Description generation for product {}: reply was not the requested JSON ({})",
                    productId, e.getClass().getSimpleName());
            return null;
        }
        Set<ConstraintViolation<ProductCopy>> violations = copy == null ? Set.of() : validator.validate(copy);
        if (copy == null || !violations.isEmpty()) {
            log.warn("Description generation for product {}: reply broke the limits: {}", productId,
                    violations.stream()
                            .map(v -> v.getPropertyPath() + " " + v.getMessage())
                            .collect(Collectors.joining("; ")));
            return null;
        }
        return copy;
    }

    /** Trimmed, lowercase, de-duplicated tags: the model's formatting is not the catalogue's. */
    private static ProductCopy normalise(ProductCopy copy) {
        List<String> tags = copy.tags().stream()
                .map(tag -> tag.strip().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        return new ProductCopy(copy.description().strip(), tags, copy.seoTitle().strip());
    }

    /**
     * Tokens are what a hosted model bills by, so they are what "how much is this costing" is
     * answered with. Spring AI's own {@code gen_ai.client.token.usage} meter counts the same thing
     * per model; this one is ours, so a dashboard or a test does not depend on a library's naming.
     */
    private void recordTokens(ChatResponse response) {
        Usage usage = usage(response);
        if (usage == null) {
            return;
        }
        if (usage.getPromptTokens() != null) {
            tokens("prompt").increment(usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() != null) {
            tokens("completion").increment(usage.getCompletionTokens());
        }
    }

    private static Usage usage(ChatResponse response) {
        return response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
    }

    private Counter tokens(String type) {
        return Counter.builder(TOKENS)
                .description("Tokens used by product description generation")
                .tag("type", type)
                .register(meterRegistry);
    }

    private void stop(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder(GENERATIONS)
                .description("Product description generations, by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry));
    }

    /** A not-configured refusal takes no time worth measuring, but is still worth counting. */
    private void count(String outcome) {
        Timer.builder(GENERATIONS)
                .description("Product description generations, by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(Duration.ZERO);
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    /** A validated reply, and what it cost. The model and token counts may be null: not every provider reports them. */
    public record GeneratedCopy(ProductCopy copy, String model, Integer promptTokens, Integer completionTokens) {
    }
}
