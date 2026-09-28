package com.ecomdemo.catalog.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.shared.ApiError;
import com.ecomdemo.support.CatalogIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The default: no provider. The service must start - this test's context IS that start - and the
 * one endpoint that needs a model says so, instead of the whole service failing over a missing key.
 */
@DisplayName("generate-description with AI_CHAT_PROVIDER unset")
class DescriptionGenerationNotConfiguredIT extends CatalogIntegrationTest {

    @Test
    @DisplayName("answers 503 with setup instructions, and the product still reads normally")
    void notConfigured() {
        ResponseEntity<ApiError> response =
                rest.postForEntity("/api/products/1/generate-description", null, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().message()).contains("not configured").contains("AI_CHAT_PROVIDER");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("300");
        assertThat(rest.getForEntity("/api/products/1", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
