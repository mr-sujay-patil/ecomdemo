package com.ecomdemo.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * payment-service has no public API, so it builds no OpenAPI document at all (KI-001).
 *
 * <p>Its security already refuses {@code /v3/api-docs}; this is the second lock. With springdoc
 * switched off, loosening a security rule later cannot publish a document that describes
 * {@code /internal/**}. The annotations match {@code PaymentSecurityTest}, so both share a context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Payment API documentation")
class PaymentApiDocsTest {

    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("builds no OpenAPI document and serves no Swagger UI")
    void buildsNoDocument() {
        assertThat(context.getBeanNamesForType(org.springdoc.webmvc.api.OpenApiWebMvcResource.class))
                .as("springdoc's /v3/api-docs endpoint")
                .isEmpty();
        assertThat(context.getBeanNamesForType(org.springdoc.webmvc.ui.SwaggerWelcomeWebMvc.class))
                .as("springdoc's Swagger UI")
                .isEmpty();
    }
}
