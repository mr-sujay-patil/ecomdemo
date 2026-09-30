package com.ecomdemo.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.CustomerIntegrationTest;
import com.ecomdemo.support.PublishedApiDocs;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * customer-service publishes its own OpenAPI document (KI-001). The gateway routes
 * {@code /v3/api-docs/customer} here; until KI-001 this answered 401.
 */
@DisplayName("customer-service API documentation")
class ApiDocsIT extends CustomerIntegrationTest {

    @Test
    @DisplayName("an anonymous caller gets the accounts' OpenAPI document")
    void publishesItsOwnDocument() {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PublishedApiDocs.assertDocuments(response.getBody(), "EcomDemo Customer API",
                List.of("/api/auth/login", "/api/customers/register", "/api/customers/me"));
    }
}
