package com.ecomdemo.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * catalog-service's own database container, on the image compose and Kubernetes run (Phase 28).
 *
 * <p>Not the shared {@code PostgresContainerConfig}: that is {@code postgres:18-alpine}, which has
 * no {@code vector} extension, so V4 would fail on its first line. Changing the shared one would
 * move five other services onto an image they do not need. {@code asCompatibleSubstituteFor} tells
 * Testcontainers this image speaks PostgreSQL, which it checks by name.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PgVectorContainerConfig {

    private static final DockerImageName PGVECTOR_IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg18-trixie").asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(PGVECTOR_IMAGE);
    }
}
