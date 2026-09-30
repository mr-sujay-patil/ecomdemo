package com.ecomdemo.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.KafkaContainerConfig;
import com.ecomdemo.support.PostgresContainerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * notification-service has no business API, so it builds no OpenAPI document at all (KI-001).
 *
 * <p>Its security already refuses {@code /v3/api-docs}; this is the second lock, so that loosening
 * a security rule later cannot publish a document by accident.
 */
@SpringBootTest
@Import({PostgresContainerConfig.class, KafkaContainerConfig.class})
@ActiveProfiles("it")
@DisplayName("Notification API documentation")
class NotificationApiDocsIT {

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
