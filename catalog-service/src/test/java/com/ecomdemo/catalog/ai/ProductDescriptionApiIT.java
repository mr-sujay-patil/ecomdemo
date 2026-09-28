package com.ecomdemo.catalog.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.shared.ApiError;
import com.ecomdemo.support.CatalogIntegrationTest;
import com.ecomdemo.support.ScriptedChatModel;
import java.math.BigDecimal;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The endpoint end to end - HTTP, security, the prompt, PostgreSQL, the cache and the metrics - with
 * only the model itself replaced. The {@link ScriptedChatModel} bean is what makes Spring AI's
 * {@code ChatClient.Builder} appear: its auto-configuration is conditional on a {@code ChatModel}
 * existing, exactly as it would be with a real provider switched on.
 */
@DisplayName("POST /api/products/{id}/generate-description")
class ProductDescriptionApiIT extends CatalogIntegrationTest {

    @TestConfiguration
    static class ScriptedModel {
        @Bean
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }

    @Autowired
    private ScriptedChatModel model;

    @Autowired
    private DataSource dataSource;

    private Long productId;

    @BeforeEach
    void createProduct() {
        model.reset();
        ResponseEntity<ProductResponse> created = rest.postForEntity(
                "/api/products",
                new ProductRequest("IT Keyboard", "Written by a person.", new BigDecimal("8999.00"), 5, "PERIPHERALS"),
                ProductResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        productId = created.getBody().id();
    }

    @AfterEach
    void deleteProduct() {
        // Also proves V3's ON DELETE CASCADE: without it, a product with a generation could not be deleted.
        rest.delete("/api/products/" + productId);
        assertThat(rest.getForEntity("/api/products/" + productId, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(generationRows()).isZero();
    }

    private String generatePath() {
        return "/api/products/" + productId + "/generate-description";
    }

    private int generationRows() {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM product_description_generation WHERE product_id = ?", Integer.class, productId);
    }

    @Test
    @DisplayName("generates, answers with the structured copy, saves the description and keeps the history")
    void generatesAndSaves() {
        // Read once first, so a cached copy exists and the test proves it is evicted.
        rest.getForEntity("/api/products/" + productId, ProductResponse.class);
        model.replyWith(ScriptedChatModel.validReply(), 180, 60);

        ResponseEntity<GeneratedDescriptionResponse> response =
                rest.postForEntity(generatePath(), null, GeneratedDescriptionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        GeneratedDescriptionResponse body = response.getBody();
        assertThat(body.productId()).isEqualTo(productId);
        assertThat(body.description()).isEqualTo("A compact 87-key keyboard with hot-swappable switches.");
        assertThat(body.tags()).containsExactly("mechanical keyboard", "hot-swap", "pbt keycaps");
        assertThat(body.seoTitle()).isEqualTo("Mechanical Keyboard with Hot-Swap Switches");
        assertThat(body.model()).isEqualTo(ScriptedChatModel.MODEL);
        assertThat(body.promptTokens()).isEqualTo(180);
        assertThat(body.completionTokens()).isEqualTo(60);
        assertThat(body.generatedAt()).isNotNull();

        assertThat(rest.getForEntity("/api/products/" + productId, ProductResponse.class).getBody().description())
                .as("saved to the product, and not hidden by the cached copy")
                .isEqualTo(body.description());
        assertThat(generationRows()).isEqualTo(1);
        assertThat(model.prompts().getFirst().getContents()).contains("Name: IT Keyboard");
    }

    @Test
    @DisplayName("token usage and the outcome are published at /actuator/prometheus")
    void publishesMetrics() {
        model.replyWith(ScriptedChatModel.validReply(), 180, 60);
        rest.postForEntity(generatePath(), null, GeneratedDescriptionResponse.class);

        String scrape = anonymous.getForObject("/actuator/prometheus", String.class);

        assertThat(scrape)
                .containsPattern("ecomdemo_ai_tokens_total\\{[^}]*type=\"prompt\"[^}]*} [1-9]")
                .containsPattern("ecomdemo_ai_tokens_total\\{[^}]*type=\"completion\"[^}]*} [1-9]")
                .containsPattern("ecomdemo_ai_generations_seconds_count\\{[^}]*outcome=\"success\"");
    }

    @Test
    @DisplayName("a failing model is a 503 with Retry-After, and the product is untouched")
    void degradesGracefully() {
        model.failWith(new RuntimeException("connection reset"));

        ResponseEntity<ApiError> response = rest.postForEntity(generatePath(), null, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(response.getBody().message()).contains("not changed");
        assertThat(rest.getForEntity("/api/products/" + productId, ProductResponse.class).getBody().description())
                .isEqualTo("Written by a person.");
        assertThat(generationRows()).isZero();
    }

    @Test
    @DisplayName("an unusable answer is a 503 too, and nothing is saved")
    void rejectsAnUnusableAnswer() {
        model.replyWith("I'd love to help! What tone would you like?", 50, 12);

        ResponseEntity<ApiError> response = rest.postForEntity(generatePath(), null, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().message()).contains("unusable answer");
        assertThat(generationRows()).isZero();
    }

    @Test
    @DisplayName("an unknown product is a 404, and the model is never asked")
    void unknownProduct() {
        ResponseEntity<ApiError> response =
                rest.postForEntity("/api/products/999999/generate-description", null, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    @DisplayName("no token, no generation: 401")
    void requiresAToken() {
        ResponseEntity<ApiError> response = anonymous.postForEntity(generatePath(), null, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(model.prompts()).isEmpty();
    }
}
