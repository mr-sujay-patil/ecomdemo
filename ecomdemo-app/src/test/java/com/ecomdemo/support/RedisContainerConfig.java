package com.ecomdemo.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one place a Redis container is declared, alongside {@link PostgresContainerConfig}.
 *
 * <p><b>A {@code GenericContainer}, not a dedicated module.</b> Testcontainers ships a module per
 * database engine, but not one for Redis — a Redis container needs nothing beyond an image and a
 * port, so there is nothing for a module to add. What makes it work with Spring is the
 * {@code name} on {@link ServiceConnection}: with a dedicated module Boot infers the service from
 * the container type, and here it is told directly. Boot then reads the container's host and its
 * random port and configures {@code spring.data.redis.*} from them, which is why
 * {@code application-it.properties} deliberately sets neither.
 *
 * <p><b>Why it lives in the same shared context.</b> This is imported by {@link IntegrationTest}
 * next to the PostgreSQL configuration, so both containers belong to the ONE application context
 * every {@code *IT} class shares. Declaring it on a single test class instead would give that
 * class a context of its own — and with it a second PostgreSQL as well as a Redis, because the
 * context cache key is the whole annotation set, not the part that changed. That is the Phase 7
 * lesson applied rather than re-learned.
 *
 * <p>The image is pinned for the same reason the others are: a cache whose eviction behaviour
 * changed under the test would produce a failure nobody could reproduce.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisContainerConfig {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:8-alpine");

    private static final int REDIS_PORT = 6379;

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(REDIS_PORT);
    }
}
