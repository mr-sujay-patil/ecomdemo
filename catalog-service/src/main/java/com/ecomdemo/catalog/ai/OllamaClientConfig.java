package com.ecomdemo.catalog.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Ollama's HTTP client, rebuilt only to give it a deadline.
 *
 * <p>Spring AI's own {@code OllamaApi} is built on Boot's shared {@link RestClient.Builder}, which
 * has no read timeout - so a model that hangs holds the admin's request forever. Setting
 * {@code spring.http.clients.read-timeout} would fix that, and would also quietly change the
 * timeout of inventory-service's client, which is built on the same builder. So the deadline is
 * set here, on a clone, for this one client; Spring AI's bean steps aside because it is
 * {@code @ConditionalOnMissingBean}. The clone keeps the builder's observation support, so the
 * calls still appear in traces.
 *
 * <p>OpenAI needs none of this: its client takes {@code spring.ai.openai.chat.timeout} directly.
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "ollama")
class OllamaClientConfig {

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
