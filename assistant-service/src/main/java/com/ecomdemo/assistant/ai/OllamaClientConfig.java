package com.ecomdemo.assistant.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Ollama's HTTP client, rebuilt only to give it a deadline - catalog-service's class of the same
 * name, for the same reason (Phase 27): Spring AI's own client has no read timeout, so a model that
 * hangs would hold the customer's request forever. The deadline applies to EACH model call, and one
 * answer may make several (one per round of tool calls).
 */
@Configuration
@Conditional(OllamaClientConfig.OllamaInUse.class)
class OllamaClientConfig {

    /** Chat OR embeddings on Ollama: the nested conditions are OR-ed. */
    static class OllamaInUse extends AnyNestedCondition {

        OllamaInUse() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "ollama")
        static class Chat {
        }

        @ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "ollama")
        static class Embedding {
        }
    }

    @Bean
    OllamaApi ollamaApi(
            RestClient.Builder builder,
            @Value("${spring.ai.ollama.base-url}") String baseUrl,
            @Value("${ecomdemo.ai.timeout}") Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(builder.clone().requestFactory(requestFactory))
                .responseErrorHandler(RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER)
                .build();
    }
}
