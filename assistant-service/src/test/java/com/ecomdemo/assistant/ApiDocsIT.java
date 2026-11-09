package com.ecomdemo.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.assistant.support.AssistantIntegrationTest;
import com.ecomdemo.support.PublishedApiDocs;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * assistant-service publishes its own OpenAPI document (KI-001). The gateway routes
 * {@code /v3/api-docs/assistant} here; until KI-001 its {@code denyAll()} answered 401.
 */
@DisplayName("assistant-service API documentation")
class ApiDocsIT extends AssistantIntegrationTest {

    @Test
    @DisplayName("an anonymous caller gets the assistant's OpenAPI document")
    void publishesItsOwnDocument() {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PublishedApiDocs.assertDocuments(response.getBody(), "EcomDemo Assistant API",
                List.of("/api/assistant/chat"));
    }

    @Test
    @DisplayName("a reply's pendingAction is published as the cart addition or null (KI-086)")
    void pendingActionIsTheAdditionOrNull() {
        // It was {$ref, type: "null"}: in OpenAPI 3.1 both must hold, which only null satisfies,
        // and a generated client typed it as always present.
        JsonNode spec = PublishedApiDocs.assertDocuments(rest.getForEntity("/v3/api-docs", String.class).getBody(),
                "EcomDemo Assistant API", List.of("/api/assistant/chat"));
        JsonNode pendingAction = spec.path("components").path("schemas").path("AssistantReply")
                .path("properties").path("pendingAction");

        assertThat(pendingAction.has("$ref")).isFalse();
        assertThat(pendingAction.path("anyOf").valueStream()
                        .map(option -> option.path("$ref").asString(option.path("type").asString("")))
                        .toList())
                .containsExactlyInAnyOrder("#/components/schemas/PendingCartAddition", "null");
    }
}
