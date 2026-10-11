package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ecomdemo.redis.RedisClientCertificateAutoConfiguration;
import com.ecomdemo.support.RedisAcl;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * Redis authenticates its clients by certificate, on the real image (Phase 36; since Phase 37 the certificate also
 * names the ACL user, and there is no shared password: {@code RedisAclIT}).
 *
 * <p>Redis is started as its Deployment starts it: the manifest's image and arguments, a certificate where
 * cert-manager's {@code cache-tls} is mounted, the ACL file where its ConfigMap is mounted.
 * The clients are a service's own: {@code app}'s Redis and {@code redis} SSL-bundle settings from its ConfigMap,
 * given to Spring Boot as its pod gives them, with the certificate under test where the pod mounts its client
 * certificate. Then:
 * <ul>
 *   <li>the service's certificate: PONG, with no password (Phase 37: it logs in as the user its CN names);</li>
 *   <li>no certificate, or one from another CA, fails the handshake; a client that still sends the old shared
 *       password is refused (Phase 37: {@code default} is off);</li>
 *   <li>a renewed client certificate is used by the next connection, with no restart, and Spring Boot alone
 *       would NOT use it (the reason for {@code com.ecomdemo.redis});</li>
 *   <li>the pod's readiness probe and {@code cert-reload} sidecar work with Redis's own certificate as their
 *       client certificate, and would not with a `server auth`-only one.</li>
 * </ul>
 */
@DisplayName("Redis client certificates on the real image (Phase 36, Phase 37)")
class RedisClientAuthIT {

    /** What a client configured before Phase 37 would still send. */
    private static final String OLD_SHARED_PASSWORD = "it-redis-password";
    private static final String SERVER_EXTENSIONS =
            "subjectAltName=DNS:localhost,DNS:cache,IP:127.0.0.1\\nextendedKeyUsage=serverAuth,clientAuth";

    private static GenericContainer<?> issuer;
    private static GenericContainer<?> redis;

    @TempDir
    static Path files;

    @BeforeAll
    static void startRedis() throws Exception {
        issuer = KafkaTestBroker.startIssuer();
        KafkaTestBroker.issueCa(issuer, files, "ca");
        KafkaTestBroker.issueCa(issuer, files, "other-ca");
        // cache-tls as cert-manager issues it from k8s/service-certificates.yaml: both usages, and (Phase 37) the CN
        // that makes the pod's own tools the ACL user cache-admin.
        KafkaTestBroker.issueLeaf(issuer, files, "cache", "ca", RedisAcl.ADMIN, SERVER_EXTENSIONS);
        KafkaTestBroker.issueLeaf(issuer, files, "cache-renewed", "ca", RedisAcl.ADMIN, SERVER_EXTENSIONS);
        KafkaTestBroker.issueLeaf(issuer, files, "cache-server-only", "ca", RedisAcl.ADMIN,
                "subjectAltName=DNS:localhost,IP:127.0.0.1\\nextendedKeyUsage=serverAuth");
        KafkaTestBroker.issueClient(issuer, files, "ecomdemo", "ca", "ecomdemo");
        KafkaTestBroker.issueClient(issuer, files, "other-ca-ecomdemo", "other-ca", "ecomdemo");
        redis = startRedis(files, "cache");
    }

    @AfterAll
    static void stop() {
        for (GenericContainer<?> container : new GenericContainer<?>[] {redis, issuer}) {
            if (container != null) {
                container.stop();
            }
        }
    }

    @Test
    @DisplayName("app's certificate, and no password: PONG")
    void certificateAlone() throws Exception {
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("app"), "ecomdemo", "ca");
        appRedisClients(redis, mount, true).run(context -> {
            try (RedisConnection connection = context.getBean(LettuceConnectionFactory.class).getConnection()) {
                assertThat(connection.ping()).isEqualTo("PONG");
            }
        });
    }

    @Test
    @DisplayName("a TLS client that trusts the CA but has no certificate is refused in the handshake")
    void noCertificate() throws Exception {
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("no-certificate"), "ecomdemo", "ca");
        appRedisClients(redis, mount, true, "SPRING_SSL_BUNDLE_PEM_REDIS_KEYSTORE_CERTIFICATE",
                "SPRING_SSL_BUNDLE_PEM_REDIS_KEYSTORE_PRIVATEKEY").run(context -> {
                    String before = redis.getLogs();
                    assertThatThrownBy(() -> ping(context.getBean(LettuceConnectionFactory.class)))
                            .isInstanceOf(RedisConnectionFailureException.class);
                    // Redis says why, in its log.
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                            assertThat(redis.getLogs().substring(before.length())).contains("peer did not return a certificate"));
                });
    }

    @Test
    @DisplayName("a certificate for the right name from another CA is refused in the handshake")
    void otherCa() throws Exception {
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("other-ca"), "other-ca-ecomdemo", "ca");
        appRedisClients(redis, mount, true).run(context -> {
            String before = redis.getLogs();
            assertThatThrownBy(() -> ping(context.getBean(LettuceConnectionFactory.class)))
                    .isInstanceOf(RedisConnectionFailureException.class);
            // Redis says why, in its log.
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(redis.getLogs().substring(before.length())).contains("certificate verify failed"));
        });
    }

    @Test
    @DisplayName("the certificate with the old shared password: refused (Phase 37: the AUTH asks for `default`, which is off)")
    void certificateWithTheOldPassword() throws Exception {
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("old-password"), "ecomdemo", "ca");
        appRedisClients(redis, mount, true).withPropertyValues("spring.data.redis.password=" + OLD_SHARED_PASSWORD)
                .run(context -> assertThatThrownBy(() -> ping(context.getBean(LettuceConnectionFactory.class)))
                        .hasStackTraceContaining("WRONGPASS"));
    }

    @Test
    @DisplayName("a renewed client certificate is used by the next connection, without a restart")
    void renewedCertificateIsUsed() throws Exception {
        // The service starts with a certificate Redis refuses, and cert-manager then "renews" it with one Redis
        // accepts: the difference is visible on the next connection.
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("renewal"), "other-ca-ecomdemo", "ca");
        appRedisClients(redis, mount, true).run(context -> {
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            assertThatThrownBy(() -> ping(factory)).isInstanceOf(RedisConnectionFailureException.class);

            KafkaTestBroker.clientMount(files, mount, "ecomdemo", "ca");

            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                    .untilAsserted(() -> assertThat(ping(factory)).isEqualTo("PONG"));
        });
    }

    @Test
    @DisplayName("without com.ecomdemo.redis, Spring Boot keeps the start-up certificate after the bundle reloads")
    void bootAloneKeepsTheStartUpCertificate() throws Exception {
        // The same renewal, with Boot's own Redis configuration only. Boot reloads the bundle, but Lettuce keeps
        // the key managers it was given at start-up: this is the expiry trap the auto-configuration closes.
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("boot-alone"), "other-ca-ecomdemo", "ca");
        appRedisClients(redis, mount, false).run(context -> {
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            SslBundles bundles = context.getBean(SslBundles.class);
            SslBundle atStart = bundles.getBundle("redis");
            assertThatThrownBy(() -> ping(factory)).isInstanceOf(RedisConnectionFailureException.class);

            KafkaTestBroker.clientMount(files, mount, "ecomdemo", "ca");
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                    .until(() -> bundles.getBundle("redis") != atStart);

            for (int connection = 1; connection <= 3; connection++) {
                assertThatThrownBy(() -> ping(factory)).as("new connection " + connection + " after the reload")
                        .isInstanceOf(RedisConnectionFailureException.class);
            }
        });
    }

    @Test
    @DisplayName("the readiness probe presents Redis's own certificate; a `server auth`-only one would be refused")
    void readinessProbe() throws Exception {
        List<String> probe = PostgresTlsConfigTest.strings(
                ((Map<?, ?>) ((Map<?, ?>) redisContainer().get("readinessProbe")).get("exec")).get("command"));

        ExecResult result = redis.execInContainer(probe.toArray(String[]::new));
        assertThat(result.getStdout().strip()).as(result.getStderr()).isEqualTo("PONG");

        // The same probe with a certificate that may only serve: OpenSSL refuses it as a client's.
        KafkaTestBroker.copy(redis, files.resolve("cache-server-only.crt"), "/tmp/server-only.crt");
        KafkaTestBroker.copy(redis, files.resolve("cache-server-only.key"), "/tmp/server-only.key");
        List<String> serverOnly = probe.stream().map(argument -> argument
                .replace("/etc/ecomdemo-tls/tls.crt", "/tmp/server-only.crt")
                .replace("/etc/ecomdemo-tls/tls.key", "/tmp/server-only.key")).toList();
        assertThat(redis.execInContainer(serverOnly.toArray(String[]::new)).getStdout()).doesNotContain("PONG");
    }

    @Test
    @DisplayName("the cert-reload sidecar, presenting the renewed certificate, makes Redis serve it")
    void certReloadSidecar() throws Exception {
        try (GenericContainer<?> reloading = startRedis(files, "cache")) {
            BigInteger before = servedSerial(reloading);
            // The sidecar's own script, checking every second instead of every 30, in the same image (in the pod
            // it is a second container beside Redis, with the same volume and network).
            String script = PostgresTlsConfigTest.strings(sidecar().get("args")).getFirst().replace("sleep 30", "sleep 1");
            reloading.execInContainer("sh", "-c", "nohup sh -c \"$1\" >/tmp/cert-reload.log 2>&1 &", "cert-reload", script);

            // cert-manager renews: a new key and certificate in the Secret's files, the key first.
            KafkaTestBroker.copy(reloading, files.resolve("cache-renewed.key"), "/etc/ecomdemo-tls/tls.key");
            KafkaTestBroker.copy(reloading, files.resolve("cache-renewed.crt"), "/etc/ecomdemo-tls/tls.crt");

            BigInteger renewed = serial(files.resolve("cache-renewed.crt"));
            assertThat(renewed).isNotEqualTo(before);
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                    .untilAsserted(() -> assertThat(servedSerial(reloading)).isEqualTo(renewed));
            assertThat(reloading.execInContainer("cat", "/tmp/cert-reload.log").getStdout())
                    .contains("reloaded the Redis certificate");
        }
    }

    /**
     * Redis, started as its Deployment starts it, with {@code leaf} as its certificate, the CA {@code ca}, and the
     * ACL file of the {@code cache-acl} ConfigMap where the pod mounts it (Phase 37).
     */
    static GenericContainer<?> startRedis(Path files, String leaf) throws IOException {
        Map<String, Object> container = redisContainer();
        List<String> args = PostgresTlsConfigTest.strings(container.get("args"));

        GenericContainer<?> started = new GenericContainer<>((String) container.get("image"))
                .withCommand(args.toArray(String[]::new))
                .withExposedPorts(6379)
                .withCopyToContainer(Transferable.of(RedisAcl.file().getBytes(StandardCharsets.UTF_8), 0644),
                        RedisAclConfigTest.ACL_PATH)
                .withCopyToContainer(Transferable.of(Files.readAllBytes(files.resolve(leaf + ".crt")), 0644),
                        "/etc/ecomdemo-tls/tls.crt")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(files.resolve(leaf + ".key")), 0644),
                        "/etc/ecomdemo-tls/tls.key")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(files.resolve("ca.crt")), 0644),
                        "/etc/ecomdemo-tls/ca.crt")
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\s", 1));
        started.start();
        return started;
    }

    /**
     * {@code app}'s Redis clients, as the service builds them: its ConfigMap's {@code SPRING_DATA_REDIS_*} and
     * {@code redis} bundle settings as environment variables, the host and port replaced, the client-certificate
     * paths pointing at {@code mount}, and no password (Phase 37); {@code without} names settings to leave out.
     * {@code currentCertificate} adds the service's own {@code com.ecomdemo.redis} auto-configuration.
     */
    private static ApplicationContextRunner appRedisClients(GenericContainer<?> server, Path mount,
            boolean currentCertificate, String... without) throws IOException {
        return redisClients("app", server, mount, currentCertificate, without);
    }

    /** {@code service}'s Redis clients, as {@link #appRedisClients} builds app's. */
    static ApplicationContextRunner redisClients(String service, GenericContainer<?> server, Path mount,
            boolean currentCertificate, String... without) throws IOException {
        Map<String, Object> environment = new HashMap<>();
        PostgresTlsConfigTest.clientSettings(service).forEach((name, value) -> {
            if (name.startsWith("SPRING_DATA_REDIS_") || name.startsWith("SPRING_SSL_BUNDLE_PEM_REDIS_")) {
                environment.put(name, String.valueOf(value).replace(KafkaTestBroker.CLIENT_MOUNT, mount.toString()));
            }
        });
        assertThat(environment).containsEntry("SPRING_DATA_REDIS_SSL_BUNDLE", "redis");
        environment.put("SPRING_DATA_REDIS_HOST", server.getHost());
        environment.put("SPRING_DATA_REDIS_PORT", String.valueOf(server.getMappedPort(6379)));
        for (String name : without) {
            assertThat(environment.remove(name)).as(name).isNotNull();
        }
        List<Class<?>> configurations = new ArrayList<>(List.of(SslAutoConfiguration.class, DataRedisAutoConfiguration.class));
        if (currentCertificate) {
            configurations.add(RedisClientCertificateAutoConfiguration.class);
        }
        return new ApplicationContextRunner()
                // Boot maps SPRING_DATA_... names only in a source named systemEnvironment.
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                environment)))
                .withConfiguration(AutoConfigurations.of(configurations.toArray(Class<?>[]::new)))
                .withPropertyValues("spring.ssl.bundle.watch.file.quiet-period=1s",
                        "spring.data.redis.connect-timeout=5s", "spring.data.redis.timeout=5s");
    }

    private static String ping(LettuceConnectionFactory factory) {
        try (RedisConnection connection = factory.getConnection()) {
            return connection.ping();
        }
    }

    /** The serial of the certificate Redis serves now, from a TLS handshake that presents a client certificate. */
    private static BigInteger servedSerial(GenericContainer<?> server) throws Exception {
        PemSslStoreDetails client = PemSslStoreDetails.forCertificate(files.resolve("ecomdemo.crt").toUri().toString())
                .withPrivateKey(files.resolve("ecomdemo.key").toUri().toString());
        PemSslStoreDetails ca = PemSslStoreDetails.forCertificate(files.resolve("ca.crt").toUri().toString());
        SslBundle bundle = SslBundle.of(new PemSslStoreBundle(client, ca));
        try (SSLSocket socket = (SSLSocket) bundle.createSslContext().getSocketFactory()
                .createSocket(server.getHost(), server.getMappedPort(6379))) {
            socket.startHandshake();
            return ((X509Certificate) socket.getSession().getPeerCertificates()[0]).getSerialNumber();
        }
    }

    private static BigInteger serial(Path certificate) throws Exception {
        try (var in = Files.newInputStream(certificate)) {
            return ((X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(in)).getSerialNumber();
        }
    }

    private static Map<String, Object> redisContainer() throws IOException {
        return PostgresTlsConfigTest.container(cachePod(), "redis");
    }

    private static Map<String, Object> sidecar() throws IOException {
        return PostgresTlsConfigTest.container(cachePod(), "cert-reload");
    }

    private static Map<String, Object> cachePod() throws IOException {
        return PostgresTlsConfigTest.podSpec(PostgresTlsConfigTest.resource(RedisClientAuthConfigTest.CACHE,
                "Deployment", "cache"));
    }
}
