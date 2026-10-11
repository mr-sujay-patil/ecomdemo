package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ecomdemo.support.RedisAcl;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.data.redis.health.DataRedisHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

/**
 * The client certificate names the Redis user, and the user decides what the connection may do (Phase 37), on the
 * real image with the manifest's own arguments and ACL file.
 *
 * <p>The clients are the services' own: each one's {@code SPRING_DATA_REDIS_*} and {@code redis} bundle settings
 * from its ConfigMap, with no password (there is none to send any more), and a certificate with its CN. Then:
 * <ul>
 *   <li>each service logs in as its own user (Redis names it in a refusal) and Boot's health check is UP;</li>
 *   <li>catalog's certificate may use the catalogue's keys and is refused the assistant's, the rate limiter's,
 *       another database and FLUSHALL;</li>
 *   <li>a certificate from the cluster CA whose CN names no user gets NOAUTH; a certificate cannot switch to
 *       another service's user with AUTH; the old shared password logs nobody in;</li>
 *   <li>the tools in the Redis pod are {@code cache-admin}, and the {@code cert-reload} sidecar loads a changed
 *       ACL file without a restart.</li>
 * </ul>
 * What each service's code does under its user is run by {@code CatalogCacheAclIT}, {@code AssistantRedisAclIT}
 * and {@code RateLimitAclIT}; the handshake refusals (no certificate, another CA) by {@code RedisClientAuthIT}.
 */
@DisplayName("Redis ACL users from the client certificate on the real image (Phase 37)")
class RedisAclIT {

    private static final String SERVER_EXTENSIONS =
            "subjectAltName=DNS:localhost,DNS:cache,IP:127.0.0.1\\nextendedKeyUsage=serverAuth,clientAuth";

    private static GenericContainer<?> issuer;
    private static GenericContainer<?> redis;

    @TempDir
    static Path files;

    @BeforeAll
    static void start() throws Exception {
        issuer = KafkaTestBroker.startIssuer();
        KafkaTestBroker.issueCa(issuer, files, "ca");
        KafkaTestBroker.issueLeaf(issuer, files, "cache", "ca", RedisAcl.ADMIN, SERVER_EXTENSIONS);
        for (String commonName : RedisClientAuthConfigTest.REDIS_CLIENTS.values()) {
            KafkaTestBroker.issueClient(issuer, files, commonName, "ca", commonName);
        }
        // A real client certificate of the cluster, for a service that does not use Redis: no user has its name.
        KafkaTestBroker.issueClient(issuer, files, "customer", "ca", "customer");
        redis = RedisClientAuthIT.startRedis(files, "cache");
    }

    @AfterAll
    static void stop() {
        for (GenericContainer<?> container : new GenericContainer<?>[] {redis, issuer}) {
            if (container != null) {
                container.stop();
            }
        }
    }

    @ParameterizedTest(name = "{0} is the user {1}")
    @CsvSource({"app, ecomdemo", "catalog-service, catalog", "gateway-service, gateway", "assistant-service, assistant"})
    @DisplayName("each service's certificate logs in as its own user, with no password, and Boot's Redis health is UP")
    void eachServiceIsItsOwnUser(String service, String user) throws Exception {
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("mount-" + service), user, "ca");
        RedisClientAuthIT.redisClients(service, redis, mount, true).run(context -> {
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            assertThat(new DataRedisHealthIndicator(factory).health(true).getStatus()).isEqualTo(Status.UP);
            try (RedisConnection connection = factory.getConnection()) {
                assertThat(connection.ping()).isEqualTo("PONG");
                // Redis names the user in a refusal: the proof of who this connection is.
                assertThatThrownBy(() -> connection.serverCommands().flushAll())
                        .hasStackTraceContaining("NOPERM User " + user + " has no permissions to run the 'flushall' command");
            }
        });
    }

    @Test
    @DisplayName("catalog's certificate may use the catalogue's keys and is refused every other service's, database 1 and FLUSHALL")
    void catalogStaysInItsKeys() throws Exception {
        assertThat(cliAs("catalog", "SET", "product::acl", "x", "EX", "60")).isEqualTo("OK");
        assertThat(cliAs("catalog", "GET", "product::acl")).isEqualTo("x");
        assertThat(cliAs("catalog", "DEL", "product::acl")).isEqualTo("1");
        assertThat(cliAs("catalog", "GET", "assistant:memory:42:any")).startsWith("NOPERM");
        assertThat(cliAs("catalog", "GET", "request_rate_limiter.{user:x}.tokens")).startsWith("NOPERM");
        assertThat(cliAs("catalog", "SELECT", "1")).startsWith("NOPERM");
        assertThat(cliAs("catalog", "FLUSHALL")).startsWith("NOPERM");
        assertThat(cliAs("catalog", "CONFIG", "GET", "requirepass")).startsWith("NOPERM");
    }

    @Test
    @DisplayName("app's certificate (ecomdemo) touches no key at all")
    void appTouchesNoKey() throws Exception {
        assertThat(cliAs("ecomdemo", "PING")).isEqualTo("PONG");
        assertThat(cliAs("ecomdemo", "GET", "product::1")).startsWith("NOPERM");
        assertThat(cliAs("ecomdemo", "SET", "anything", "x")).startsWith("NOPERM");
    }

    @Test
    @DisplayName("a cluster-CA certificate whose CN names no Redis user passes TLS, then gets NOAUTH for every command")
    void unknownCommonNameGetsNothing() throws Exception {
        assertThat(cliAs("customer", "PING")).contains("NOAUTH");
        assertThat(cliAs("customer", "GET", "product::1")).contains("NOAUTH");
    }

    @Test
    @DisplayName("a certificate cannot become another user: AUTH to a service user, or to default with a password, is refused")
    void authCannotSwitchUsers() throws Exception {
        // Each call is a connection that is already `catalog` (its certificate) and then tries to AUTH as another.
        for (String user : List.of("assistant", "gateway", "ecomdemo", RedisAcl.ADMIN, "default")) {
            assertThat(cliAs("catalog", "AUTH", user, "a-guess")).as(user).contains("WRONGPASS");
        }
        assertThat(cliAs("catalog", "AUTH", "it-redis-password")).as("the old shared password").contains("WRONGPASS");
        assertThat(cliAs("customer", "AUTH", "it-redis-password")).contains("WRONGPASS");
    }

    @Test
    @DisplayName("the readiness probe is cache-admin (the pod's own certificate) and gets PONG")
    void probeIsTheAdmin() throws Exception {
        List<String> probe = PostgresTlsConfigTest.strings(
                ((Map<?, ?>) ((Map<?, ?>) PostgresTlsConfigTest.container(RedisAclConfigTest.cachePod(), "redis")
                        .get("readinessProbe")).get("exec")).get("command"));
        ExecResult result = redis.execInContainer(probe.toArray(String[]::new));
        assertThat(result.getStdout().strip()).as(result.getStderr()).isEqualTo("PONG");

        List<String> whoami = new ArrayList<>(probe.subList(0, probe.size() - 1));
        whoami.addAll(List.of("ACL", "WHOAMI"));
        assertThat(redis.execInContainer(whoami.toArray(String[]::new)).getStdout().strip()).isEqualTo(RedisAcl.ADMIN);
    }

    @Test
    @DisplayName("the cert-reload sidecar loads a changed ACL file (ACL LOAD): a new rule applies without a restart")
    void sidecarReloadsTheAcl() throws Exception {
        try (GenericContainer<?> reloading = RedisClientAuthIT.startRedis(files, "cache")) {
            String script = PostgresTlsConfigTest.strings(PostgresTlsConfigTest.container(RedisAclConfigTest.cachePod(),
                    "cert-reload").get("args")).getFirst().replace("sleep 30", "sleep 1");
            reloading.execInContainer("sh", "-c", "nohup sh -c \"$1\" >/tmp/cert-reload.log 2>&1 &", "cert-reload", script);
            assertThat(cliAs(reloading, "ecomdemo", "GET", "product::1")).startsWith("NOPERM");

            // Kubernetes updates the mounted ConfigMap: here the ecomdemo user gains the catalogue's keys.
            String changed = RedisAcl.file().replace("user ecomdemo reset on", "user ecomdemo reset on ~product::*");
            assertThat(changed).isNotEqualTo(RedisAcl.file());
            reloading.copyFileToContainer(Transferable.of(changed.getBytes(StandardCharsets.UTF_8), 0644),
                    RedisAclConfigTest.ACL_PATH);

            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                    .untilAsserted(() -> assertThat(cliAs(reloading, "ecomdemo", "GET", "product::1"))
                            .doesNotContain("NOPERM"));
            assertThat(reloading.execInContainer("cat", "/tmp/cert-reload.log").getStdout())
                    .contains("reloaded the Redis ACL");
        }
    }

    /** redis-cli in the Redis container over TLS, presenting the client certificate with {@code commonName}. */
    private static String cliAs(String commonName, String... command) throws Exception {
        return cliAs(redis, commonName, command);
    }

    private static String cliAs(GenericContainer<?> server, String commonName, String... command) throws Exception {
        String directory = "/tmp/clients/" + commonName;
        KafkaTestBroker.copy(server, files.resolve(commonName + ".crt"), directory + "/tls.crt");
        KafkaTestBroker.copy(server, files.resolve(commonName + ".key"), directory + "/tls.key");
        List<String> call = new ArrayList<>(List.of("redis-cli", "--tls", "--cacert", "/etc/ecomdemo-tls/ca.crt",
                "--cert", directory + "/tls.crt", "--key", directory + "/tls.key"));
        call.addAll(List.of(command));
        ExecResult result = server.execInContainer(call.toArray(String[]::new));
        return (result.getStdout() + result.getStderr()).strip();
    }
}
