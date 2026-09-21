package com.ecomdemo.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the profile configuration added in Phase 4.
 *
 * <p>These assertions are about resolved configuration, not about a running database. The
 * {@link ConfigDataApplicationContextInitializer} loads the same application.properties and
 * application-&lt;profile&gt;.properties files the application reads, so the placeholders and their
 * defaults are resolved exactly as they would be at startup - but nothing connects anywhere, which
 * keeps the suite runnable with no PostgreSQL installed.
 *
 * <p>Spring resolves {@code ${POSTGRES_HOST:localhost}} against the whole Environment, and system
 * properties sit in it alongside environment variables. So setting a system property here exercises
 * the same placeholder mechanism that an environment variable uses in production, without a test
 * having to mutate the JVM's real environment.
 */
class DatasourceConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Nested
    @DisplayName("the dev profile")
    class DevProfile {

        private ApplicationContextRunner dev() {
            return runner.withPropertyValues("spring.profiles.active=dev");
        }

        @Test
        @DisplayName("points at a local PostgreSQL when no environment variables are set")
        void usesLocalDefaults() {
            dev().run(context -> {
                Environment env = context.getEnvironment();
                assertThat(env.getProperty("spring.datasource.url"))
                        .isEqualTo("jdbc:postgresql://localhost:5432/ecomdemo");
                assertThat(env.getProperty("spring.datasource.username")).isEqualTo("ecomdemo");
                assertThat(env.getProperty("spring.datasource.password")).isEqualTo("ecomdemo");
            });
        }

        @Test
        @DisplayName("takes every connection detail from the environment when it is set")
        void environmentOverridesEveryDefault() {
            dev().withSystemProperties(
                            "POSTGRES_HOST=db.internal",
                            "POSTGRES_PORT=6543",
                            "POSTGRES_DB=shop",
                            "POSTGRES_USER=app",
                            "POSTGRES_PASSWORD=s3cret")
                    .run(context -> {
                        Environment env = context.getEnvironment();
                        assertThat(env.getProperty("spring.datasource.url"))
                                .isEqualTo("jdbc:postgresql://db.internal:6543/shop");
                        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("app");
                        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("s3cret");
                    });
        }

        @Test
        @DisplayName("keeps the schema and its data across restarts")
        void doesNotDropTheSchemaOnShutdown() {
            dev().run(context -> assertThat(
                            context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
                    .isEqualTo("update"));
        }

        @Test
        @DisplayName("names the Hikari pool so it is identifiable in logs and metrics")
        void configuresTheConnectionPool() {
            dev().run(context -> {
                Environment env = context.getEnvironment();
                assertThat(env.getProperty("spring.datasource.hikari.pool-name"))
                        .isEqualTo("EcomdemoPool");
                assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class))
                        .isPositive();
            });
        }

        @Test
        @DisplayName("seeds the catalogue, which data.sql's guard makes safe to repeat")
        void runsTheSeedScript() {
            dev().run(context -> assertThat(
                            context.getEnvironment().getProperty("spring.sql.init.mode"))
                    .isEqualTo("always"));
        }
    }

    @Nested
    @DisplayName("the test profile")
    class TestProfile {

        private ApplicationContextRunner test() {
            return runner.withPropertyValues("spring.profiles.active=test");
        }

        @Test
        @DisplayName("runs on in-memory H2, so the suite needs no database installed")
        void usesInMemoryH2() {
            test().run(context -> assertThat(
                            context.getEnvironment().getProperty("spring.datasource.url"))
                    .startsWith("jdbc:h2:mem:"));
        }

        @Test
        @DisplayName("builds and drops the schema for every run")
        void recreatesTheSchema() {
            test().run(context -> assertThat(
                            context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
                    .isEqualTo("create-drop"));
        }
    }

    /**
     * Read from the file rather than from the Environment on purpose: this JVM runs with
     * {@code -Dspring.profiles.active=test} (see the surefire configuration in pom.xml), and a
     * system property beats the file. So the default the file declares is invisible from inside
     * the suite, and the file itself is the only place the claim can be checked.
     */
    @Test
    @DisplayName("defaults to dev, so ./mvnw spring-boot:run needs no arguments")
    void defaultsToTheDevProfile() throws IOException {
        Properties base = new Properties();
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
            base.load(in);
        }
        assertThat(base.getProperty("spring.profiles.active")).isEqualTo("dev");
    }
}
