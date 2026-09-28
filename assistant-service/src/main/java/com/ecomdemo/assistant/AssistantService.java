package com.ecomdemo.assistant;

import com.ecomdemo.assistant.AssistantReply.Source;
import com.ecomdemo.assistant.actions.PendingActions;
import com.ecomdemo.assistant.actions.PendingCartAddition;
import com.ecomdemo.assistant.policy.PolicyLibrary;
import com.ecomdemo.assistant.policy.RetrievedPassage;
import com.ecomdemo.assistant.store.CartView;
import com.ecomdemo.assistant.store.StoreClient;
import com.ecomdemo.assistant.store.StoreUnavailableException;
import com.ecomdemo.assistant.tools.ShoppingTools;
import com.ecomdemo.assistant.tools.Turn;
import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.shared.ServiceUnavailableException;
import com.ecomdemo.shared.TokenClaims;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * One question in, one grounded answer out (Phase 29).
 *
 * <h2>The request, step by step</h2>
 *
 * <ol>
 *   <li><b>Retrieve</b> the policy passages the message is about ({@link PolicyLibrary}).
 *   <li><b>Augment</b>: put them in the system prompt, under rules that say to answer from them.
 *   <li><b>Generate</b>, with the tools available and the conversation so far replayed from Redis.
 *       The model may call tools - each round is another call to it - before it answers.
 *   <li><b>Report</b> what the answer was based on: the passages, the products the tools returned,
 *       the orders looked at, and any cart addition waiting for confirmation.
 * </ol>
 * Policies are retrieved on EVERY message, products only when the model asks. Policies are a small
 * fixed set and a small model reliably forgets to look things up; products need arguments (a
 * category, a price limit) only the model can pick out of the question.
 *
 * <h2>Whose conversation</h2>
 *
 * The memory key is {@code <userId>:<conversationId>}, with the user id from the verified token.
 * A conversation id is only meaningful together with the user it belongs to.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    static final String CHATS = "ecomdemo.assistant.chats";
    static final String TOKENS = "ecomdemo.ai.tokens";

    static final String TOOL_LIMIT_ANSWER =
            "Sorry, I could not work that out. Could you ask again, a little more specifically?";

    private static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    private final ChatClient chatClient;
    private final boolean embeddingConfigured;
    private final PolicyLibrary policies;
    private final StoreClient store;
    private final PendingActions actions;
    private final ChatMemory chatMemory;
    private final ChatMemoryRepository memoryRepository;
    private final AssistantProperties properties;
    private final MeterRegistry meterRegistry;
    private final Resource systemPrompt;

    public AssistantService(
            ObjectProvider<ChatModel> chatModel,
            ObjectProvider<ChatClient.Builder> chatClientBuilder,
            ObjectProvider<EmbeddingModel> embeddingModel,
            PolicyLibrary policies,
            StoreClient store,
            PendingActions actions,
            ChatMemory chatMemory,
            ChatMemoryRepository memoryRepository,
            AssistantProperties properties,
            MeterRegistry meterRegistry,
            @Value("classpath:prompts/assistant-system.st") Resource systemPrompt) {
        // The model is asked for first: Spring AI registers the builder even with no model, and
        // resolving it then fails (the Phase 27 lesson). And the builder BEAN, not the static
        // ChatClient.builder(model): only the bean carries the ToolCallingManager configured from
        // spring.ai.tools.limits.* - the static one makes its own, allowing 150 tool calls - and
        // the observation registry that puts model calls in the trace.
        this.chatClient = chatModel.getIfAvailable() == null ? null : chatClientBuilder.getObject().build();
        this.embeddingConfigured = embeddingModel.getIfAvailable() != null;
        this.policies = policies;
        this.store = store;
        this.actions = actions;
        this.chatMemory = chatMemory;
        this.memoryRepository = memoryRepository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.systemPrompt = systemPrompt;
    }

    public AssistantReply chat(Jwt caller, ChatRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        if (chatClient == null || !embeddingConfigured) {
            stop(sample, "not_configured");
            throw notConfigured();
        }
        long userId = userId(caller);
        String conversationId = request.conversationId() == null
                ? UUID.randomUUID().toString() : request.conversationId().toLowerCase();

        List<RetrievedPassage> passages;
        try {
            passages = policies.relevant(request.message());
        } catch (RuntimeException e) {
            log.warn("Policy retrieval failed: {}", e.toString());
            stop(sample, "model_failed");
            throw unavailable(e);
        }

        Turn turn = new Turn();
        ShoppingTools tools = new ShoppingTools(store, actions, meterRegistry,
                caller.getTokenValue(), userId, properties.productResults(), turn);
        String memoryKey = userId + ":" + conversationId;
        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .system(system -> system.text(systemPrompt).param("policies", render(passages)))
                    .user(request.message())
                    .tools(tools)
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, memoryKey))
                    .call()
                    .chatResponse();
        } catch (RuntimeException e) {
            log.warn("The chat model failed: {}", e.toString());
            stop(sample, "model_failed");
            throw unavailable(e);
        }

        Generation result = response == null ? null : response.getResult();
        if (result != null && ToolCallLimitExceededException.FINISH_REASON.equals(result.getMetadata().getFinishReason())) {
            // The model kept asking for tools. Spring AI stops the loop and hands back its own
            // message ("Tool call limit (3) exceeded for tool ...") as if it were the answer - and
            // the memory advisor has already stored it as one. Neither is for the customer. Not an
            // outage either, so not a 503: a plain answer, and a counter to say how often.
            log.warn("Answer stopped at the tool call limit: {}", result.getOutput().getText());
            replaceLastAnswer(memoryKey, TOOL_LIMIT_ANSWER);
            stop(sample, "tool_limit");
            return reply(conversationId, TOOL_LIMIT_ANSWER, passages, turn);
        }
        String answer = result == null ? null : result.getOutput().getText();
        if (answer == null || answer.isBlank()) {
            stop(sample, "empty");
            throw new ServiceUnavailableException(
                    "The assistant could not produce an answer. Try again.", RETRY_AFTER, null);
        }
        recordTokens(response);
        stop(sample, "answered");
        return reply(conversationId, answer.strip(), passages, turn);
    }

    /**
     * Makes a cart addition the assistant proposed. The only way anything the assistant suggests
     * changes the store - and the model cannot call it.
     */
    public ConfirmedAddition confirm(Jwt caller, String actionId) {
        PendingCartAddition action = actions.take(userId(caller), actionId)
                .orElseThrow(() -> new NotFoundException(
                        "No pending cart addition with id " + actionId + ". It may have expired or been confirmed already."));
        try {
            CartView cart = store.addToCart(caller.getTokenValue(), action.productId(), action.quantity());
            meterRegistry.counter("ecomdemo.assistant.actions", "outcome", "confirmed").increment();
            return new ConfirmedAddition(action.productId(), action.productName(), action.quantity(),
                    cart == null ? null : cart.totalAmount());
        } catch (HttpClientErrorException.NotFound e) {
            meterRegistry.counter("ecomdemo.assistant.actions", "outcome", "product_gone").increment();
            throw NotFoundException.product(action.productId());
        } catch (StoreUnavailableException e) {
            meterRegistry.counter("ecomdemo.assistant.actions", "outcome", "unavailable").increment();
            throw new ServiceUnavailableException(
                    "The cart is unavailable right now. Ask the assistant again in a moment.", RETRY_AFTER, e);
        }
    }

    /** Swaps the last stored message of a conversation (the answer just given) for {@code answer}. */
    private void replaceLastAnswer(String memoryKey, String answer) {
        List<Message> messages = new ArrayList<>(memoryRepository.findByConversationId(memoryKey));
        if (!messages.isEmpty() && messages.getLast().getMessageType() == MessageType.ASSISTANT) {
            messages.set(messages.size() - 1, new AssistantMessage(answer));
            memoryRepository.saveAll(memoryKey, messages);
        }
    }

    private static AssistantReply reply(String conversationId, String answer, List<RetrievedPassage> passages,
            Turn turn) {
        List<Source> sources = new ArrayList<>();
        passages.forEach(p -> sources.add(new Source("policy", p.passage().id(), p.passage().heading())));
        turn.products().forEach(p -> sources.add(new Source("product", p.id().toString(), p.name())));
        turn.orders().forEach(id -> sources.add(new Source("order", id.toString(), "Order " + id)));
        return new AssistantReply(conversationId, answer, sources, turn.toolsUsed(), turn.pendingAction());
    }

    static String render(List<RetrievedPassage> passages) {
        if (passages.isEmpty()) {
            return "(none are relevant to this message)";
        }
        return passages.stream().map(p -> p.passage().text()).collect(Collectors.joining("\n\n"));
    }

    private static long userId(Jwt caller) {
        Object claim = caller.getClaim(TokenClaims.USER_ID);
        if (claim instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("The token carries no usable '" + TokenClaims.USER_ID + "' claim");
    }

    private void recordTokens(ChatResponse response) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        if (usage.getPromptTokens() != null) {
            meterRegistry.counter(TOKENS, "type", "prompt").increment(usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() != null) {
            meterRegistry.counter(TOKENS, "type", "completion").increment(usage.getCompletionTokens());
        }
    }

    private void stop(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder(CHATS)
                .description("Assistant answers, by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry));
    }

    private static ServiceUnavailableException unavailable(RuntimeException cause) {
        return new ServiceUnavailableException(
                "The assistant is unavailable: the language model did not answer.", RETRY_AFTER, cause);
    }

    private static ServiceUnavailableException notConfigured() {
        return new ServiceUnavailableException(
                "The assistant is not configured. It needs a chat model and an embedding model: set "
                        + "AI_CHAT_PROVIDER and AI_EMBEDDING_PROVIDER to openai (with OPENAI_API_KEY) or "
                        + "ollama, and restart assistant-service.",
                Duration.ofMinutes(5), null);
    }
}
