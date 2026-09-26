package com.ecomdemo.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * Reads {@code application.yml} and checks the things a compiler cannot.
 *
 * <p><strong>Why a test for configuration at all.</strong> A route's target, and the bean name the
 * rate limiter resolves its key with, are strings in a YAML file. Nothing checks them at build time,
 * and neither fails at startup: a mistyped host produces a 503 on the first request that needs it,
 * and a stale {@code #{@beanName}} reference throws when traffic arrives, not when the context is
 * built. Both are the same shape of defect as {@code TokenView} inventing its field names in Phase
 * 20d — a contract written from memory, discovered by a user.
 *
 * <p>This class is deliberately a plain unit test. It parses the file rather than starting a context,
 * so it costs milliseconds and runs in Surefire, where a failure is immediate rather than waiting for
 * the integration suite.
 */
@DisplayName("Gateway route configuration")
class GatewayRouteConfigurationTest {

    private static final Path CONFIG = Path.of("src/main/resources/application.yml");

    /**
     * The hosts a route is allowed to point at: the compose service names, and the localhost
     * defaults used when running the gateway outside Docker.
     */
    private static final Set<String> KNOWN_TARGETS = Set.of(
            "app", "catalog-service", "customer-service", "inventory-service", "localhost");

    private static final Pattern URI_HOST =
            Pattern.compile("^https?://(?:\\$\\{[A-Z_]+:)?https?://([a-z-]+):(\\d+)\\}?$|^https?://([a-z-]+):(\\d+)\\}?$");

    private final Properties properties = load();

    private static Properties load() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(CONFIG));
        Properties loaded = yaml.getObject();
        assertThat(loaded).as("application.yml parses").isNotNull();
        return loaded;
    }

    private List<String> routeIds() {
        List<String> ids = new ArrayList<>();
        for (int index = 0; ; index++) {
            String id = properties.getProperty(
                    "spring.cloud.gateway.server.webflux.routes[%d].id".formatted(index));
            if (id == null) {
                return ids;
            }
            ids.add(id);
        }
    }

    private String uriOf(int index) {
        return properties.getProperty(
                "spring.cloud.gateway.server.webflux.routes[%d].uri".formatted(index));
    }

    @Test
    @DisplayName("every route points at a service this system actually has")
    void everyRouteTargetsAKnownService() {
        List<String> ids = routeIds();
        assertThat(ids).as("routes are configured at all").isNotEmpty();

        for (int index = 0; index < ids.size(); index++) {
            String uri = uriOf(index);
            assertThat(uri).as("route '%s' has a uri", ids.get(index)).isNotNull();

            // The value is `${CATALOG_BASE_URL:http://localhost:8081}` - an environment variable
            // with a default. Both halves are checked: the default must be a host this project
            // knows, so a typo in the fallback cannot hide behind a variable that is set in compose
            // and unset everywhere else.
            Matcher matcher = Pattern.compile("([a-z][a-z-]*):(\\d+)").matcher(uri);
            List<String> hosts = new ArrayList<>();
            while (matcher.find()) {
                hosts.add(matcher.group(1));
            }
            assertThat(hosts)
                    .as("route '%s' names a host and port in '%s'", ids.get(index), uri)
                    .isNotEmpty();
            assertThat(KNOWN_TARGETS)
                    .as("route '%s' points at '%s', which is not a service in this system",
                            ids.get(index), hosts)
                    .containsAll(hosts);
        }
    }

    /**
     * The ordering claim, and it is a real defect if it breaks.
     *
     * <p>Spring Cloud Gateway evaluates routes in declaration order and takes the first whose
     * predicates match. The application's route is {@code Path=/api/**}, which matches everything the
     * other three match as well — so if it were ever moved up, catalogue, login and stock requests
     * would all be sent to the application instead, and the failure would be a 404 from the wrong
     * service rather than anything that says "route order".
     */
    @Test
    @DisplayName("the application's catch-all route is LAST, or it swallows the others")
    void theCatchAllRouteIsLast() {
        List<String> ids = routeIds();
        assertThat(ids).last().as("the /api/** route must be declared last").isEqualTo("app");
    }

    /**
     * notification-service has no route, asserted so that adding one is a decision.
     *
     * <p>It has no business API — it consumes Kafka and writes its own table. A route to it would
     * return 503 rather than 404, which is a worse answer than having no route at all.
     */
    @Test
    @DisplayName("notification-service has no route, because it has no business API")
    void notificationServiceIsNotRouted() {
        assertThat(routeIds()).doesNotContain("notification", "notification-service");
        for (int index = 0; index < routeIds().size(); index++) {
            assertThat(uriOf(index)).doesNotContain("notification-service");
        }
    }

    /**
     * THE ONE THIS CLASS EXISTS FOR.
     *
     * <p>{@code key-resolver: "#{@rateLimitKeyResolver}"} is a SpEL bean reference resolved when a
     * request arrives, not when the context starts. Rename the bean and the configuration still
     * loads, the container still reports healthy, and the first request through the gateway fails.
     * This ties the string to the actual {@code @Bean} method name so the rename fails the build.
     */
    @Test
    @DisplayName("the rate limiter's key-resolver names a bean that exists")
    void theKeyResolverReferenceMatchesABean() throws IOException {
        String yaml = Files.readString(CONFIG);
        Matcher reference = Pattern.compile("key-resolver:\\s*\"#\\{@([A-Za-z0-9_]+)\\}\"").matcher(yaml);
        assertThat(reference.find()).as("a key-resolver bean reference is configured").isTrue();
        String beanName = reference.group(1);

        List<String> beanMethods = new ArrayList<>();
        for (Method method : RateLimitConfig.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.springframework.context.annotation.Bean.class)) {
                beanMethods.add(method.getName());
            }
        }
        assertThat(beanMethods)
                .as("application.yml resolves '#{@%s}', which must be a @Bean method name", beanName)
                .contains(beanName);
    }
}
