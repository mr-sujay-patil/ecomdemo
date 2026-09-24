package com.ecomdemo.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The one place a PostgreSQL container is declared. Every integration test gets it by extending
 * {@link IntegrationTest}, which imports this class.
 *
 * <p><b>Why a {@code @Bean} and not {@code @Container}.</b> Testcontainers' own JUnit extension
 * ({@code @Testcontainers} + {@code @Container}) starts and stops the container around the test
 * class, and the test then has to copy the container's random port into
 * {@code spring.datasource.url} through a {@code @DynamicPropertySource} method. Declaring the
 * container as a Spring bean instead hands both jobs to Spring: it starts the container when the
 * context is created, stops it when the context is closed, and {@link ServiceConnection} reads
 * the url, username and password straight off the container and feeds them to the auto-configured
 * {@code DataSource}. There is no connection string written anywhere in this project's test code.
 *
 * <p><b>Lifecycle and reuse.</b> Spring caches an application context by the full set of things
 * that define it — the test class annotations, the active profiles, the imported configuration.
 * All three {@code *IT} classes ask for exactly the same context, so Spring builds it once and
 * the three classes share it, which means <em>one</em> container for the whole
 * {@code failsafe:integration-test} run rather than one per class. That is the reuse that
 * matters here, and it is free.
 *
 * <p>A second, opt-in level of reuse exists for the inner development loop: calling
 * {@code withReuse(true)} on the container and setting {@code testcontainers.reuse.enable=true}
 * in {@code ~/.testcontainers.properties} leaves the container <em>running</em> after the JVM
 * exits, so the next run attaches to it in milliseconds instead of booting PostgreSQL again. It
 * is deliberately not switched on here: a surviving container keeps the rows the last run wrote,
 * and a build that only passes because of yesterday's data is worse than a build that takes
 * another six seconds.
 *
 * <p><b>The image is pinned to the same tag the application runs against</b>
 * ({@code postgres:18-alpine}, the tag in the README's {@code docker run}). Pinning is the whole
 * point: {@code postgres:latest} would make the build depend on what Docker Hub published this
 * morning, and "works on PostgreSQL" would stop being a fact about a known version.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    /** The image tag, kept identical to the one the dev profile talks to. */
    private static final String POSTGRES_IMAGE = "postgres:18-alpine";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }
}
