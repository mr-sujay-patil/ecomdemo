package com.ecomdemo.inventory;

import com.ecomdemo.support.PublishedApiDocs;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * inventory-service publishes its own OpenAPI document (KI-001). The gateway routes
 * {@code /v3/api-docs/inventory} here; until KI-001 this answered 401. The API itself stays closed:
 * the document describes its shape, not its data.
 *
 * <p>The annotations match {@code InventorySecurityTest}, so both share one application context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Inventory API documentation")
class InventoryApiDocsTest {

    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private MockMvcTester mvc;

    @Test
    @DisplayName("an anonymous caller gets the inventory's OpenAPI document")
    void publishesItsOwnDocument() throws Exception {
        var response = mvc.get().uri("/v3/api-docs").exchange().getResponse();

        org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo(200);
        PublishedApiDocs.assertDocuments(response.getContentAsString(), "EcomDemo Inventory API",
                List.of("/api/inventory/{productId}"));
    }
}
