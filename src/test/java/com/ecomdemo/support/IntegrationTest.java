package com.ecomdemo.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for the {@code *IT} tests: the whole application on a real port, talking to a real
 * PostgreSQL in a container.
 *
 * <p>Every annotation here is part of the context cache key, so keeping them in one place is what
 * lets the three integration tests share a single context — and therefore a single container and
 * a single PostgreSQL boot. Add a {@code @MockitoBean} or a stray {@code @TestPropertySource} to
 * one subclass and that subclass silently gets a context, and a container, of its own.
 *
 * <p>{@link WebEnvironment#RANDOM_PORT} starts Tomcat on a free port and the injected
 * {@link TestRestTemplate} is pre-pointed at it. Requests therefore travel over real HTTP through
 * the real filter chain, Jackson and the {@code GlobalExceptionHandler} — unlike
 * {@code @WebMvcTest}, which calls the controller through a mock servlet environment with the
 * service layer stubbed out.
 *
 * <p>{@code TestRestTemplate} never throws on a 4xx or 5xx; it hands back the
 * {@link org.springframework.http.ResponseEntity} so the status itself can be asserted. That is
 * the difference from {@code RestTemplate} and the reason the error-path tests read as plainly
 * as the happy-path ones.
 *
 * <p>{@code @AutoConfigureTestRestTemplate} is new in Spring Boot 4. Boot 3 contributed the
 * {@code TestRestTemplate} bean to every {@code @SpringBootTest} with a real port; Boot 4 moved
 * the class into {@code spring-boot-resttestclient} and registers its auto-configuration only
 * when this annotation asks for it, so without it the field is simply not injected and the
 * context fails to start.
 *
 * <p>These tests are deliberately NOT {@code @Transactional}. A rolled-back test transaction
 * would never commit, so it could not prove that a real commit works, and the HTTP request runs
 * on a Tomcat thread that would not see the test thread's transaction anyway. They clean up after
 * themselves instead, through the same API they exercise.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(PostgresContainerConfig.class)
@ActiveProfiles("it")
public abstract class IntegrationTest {

    @Autowired
    protected TestRestTemplate rest;
}
