package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * Kafka's TLS settings work on the real image, with a client's real settings (KI-059).
 *
 * <p>The broker image is started with the StatefulSet's own environment (its listeners) and start command,
 * and a certificate where cert-manager's Secret is mounted. The client side is {@code app}'s ConfigMap,
 * bound into Spring Boot's {@link KafkaProperties} as Boot binds environment variables. Then:
 * <ul>
 *   <li>an SSL client that trusts the CA and checks the name connects;</li>
 *   <li>a plain-text client gets nowhere;</li>
 *   <li>a host name the certificate does not carry is refused by the client;</li>
 *   <li>the {@code cert-reload} sidecar's script puts a renewed certificate in service without a restart.</li>
 * </ul>
 * The one change from the manifest: the advertised address is {@code localhost} on a port of this
 * machine, because the test reaches the broker from outside the container network.
 */
@DisplayName("Kafka TLS on the real image (KI-059)")
class KafkaTlsIT {

    private static GenericContainer<?> issuer;

    @TempDir
    static Path files;

    @BeforeAll
    static void startTheIssuer() throws Exception {
        issuer = new GenericContainer<>(brokerImage()).withCreateContainerCmdModifier(cmd -> cmd
                .withEntrypoint("sleep", "infinity"));
        issuer.start();
        issue("ca", null);
        issue("other-ca", null);
        issue("leaf", "ca");
        issue("renewed", "other-ca");
    }

    @AfterAll
    static void stopTheIssuer() {
        if (issuer != null) {
            issuer.stop();
        }
    }

    @Test
    @DisplayName("SSL with the CA and the name connects; plain text and a name the certificate lacks are refused")
    void tlsOnlyAndVerified() throws Exception {
        int port = freePort();
        try (GenericContainer<?> broker = startBroker(port)) {
            try (Admin admin = Admin.create(clientProperties("localhost:" + port, "ca"))) {
                assertThat(admin.listTopics().names().get(30, TimeUnit.SECONDS)).isNotNull();
            }

            Map<String, Object> plain = clientProperties("localhost:" + port, "ca");
            plain.put(AdminClientConfig.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT");
            try (Admin admin = Admin.create(plain)) {
                assertThatThrownBy(() -> admin.listTopics().names().get(30, TimeUnit.SECONDS)).isNotNull();
            }

            try (Admin admin = Admin.create(clientProperties("127.0.0.1:" + port, "ca"))) {
                assertThatThrownBy(() -> admin.listTopics().names().get(30, TimeUnit.SECONDS))
                        .hasStackTraceContaining("SSL handshake failed");
            }
        }
    }

    @Test
    @DisplayName("the cert-reload script puts a renewed certificate in service without a restart")
    void renewalIsReloaded() throws Exception {
        int port = freePort();
        try (GenericContainer<?> broker = startBroker(port)) {
            String reload = String.join("\n", PostgresTlsConfigTest.strings(PostgresTlsConfigTest.container(
                    pod(), "cert-reload").get("args")));
            broker.execInContainer("sh", "-c", "RELOAD_INTERVAL=1 nohup sh -c \"$1\" >/tmp/reload.log 2>&1 &",
                    "reloader", reload);
            // The renewal comes from ANOTHER CA, so a client that trusts only that CA tells the two apart.
            copy(broker, "renewed.crt", "/etc/ecomdemo-tls/tls.crt");
            copy(broker, "renewed.key", "/etc/ecomdemo-tls/tls.key");

            // Until the reload, the broker still presents the old certificate and the handshake fails.
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).ignoreExceptions().untilAsserted(() -> {
                try (Admin admin = Admin.create(clientProperties("localhost:" + port, "other-ca"))) {
                    assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).isNotNull();
                }
            });
            assertThat(broker.execInContainer("cat", "/tmp/reload.log").getStdout())
                    .contains("reloaded the Kafka certificate");
            assertThat(broker.isRunning()).isTrue();
        }
    }

    /** The broker, started as its StatefulSet starts it, advertising itself on the given port of this machine. */
    private static GenericContainer<?> startBroker(int port) throws IOException {
        Map<String, Object> container = PostgresTlsConfigTest.container(pod(), "kafka");
        Map<String, String> env = new HashMap<>(KafkaTlsConfigTest.brokerEnvironment());
        env.put("KAFKA_ADVERTISED_LISTENERS",
                env.get("KAFKA_ADVERTISED_LISTENERS").replace("INTERNAL://kafka:9092", "INTERNAL://localhost:" + port));
        env.put("KAFKA_HEAP_OPTS", "-Xmx256m -Xms256m");
        List<String> command = PostgresTlsConfigTest.strings(container.get("command"));
        List<String> args = PostgresTlsConfigTest.strings(container.get("args"));

        GenericContainer<?> broker = new GenericContainer<>((String) container.get("image"))
                .withEnv(env)
                // The pod's volumes: its data claim and the emptyDir the keystore is written to.
                .withTmpFs(Map.of("/var/lib/kafka/data", "rw,mode=1777", "/etc/kafka-tls", "rw,mode=1777"))
                .withCopyToContainer(Transferable.of(Files.readAllBytes(files.resolve("leaf.crt")), 0644),
                        "/etc/ecomdemo-tls/tls.crt")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(files.resolve("leaf.key")), 0644),
                        "/etc/ecomdemo-tls/tls.key")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(files.resolve("ca.crt")), 0644),
                        "/etc/ecomdemo-tls/ca.crt")
                .withCreateContainerCmdModifier(cmd -> {
                    cmd.withEntrypoint(command.toArray(String[]::new)).withCmd(args.toArray(String[]::new))
                            .withExposedPorts(ExposedPort.tcp(9092));
                    cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(port), ExposedPort.tcp(9092)));
                })
                .waitingFor(Wait.forLogMessage(".*Kafka Server started.*\\s", 1).withStartupTimeout(Duration.ofMinutes(2)));
        broker.start();
        return broker;
    }

    /**
     * The client settings of {@code app}'s ConfigMap, bound into {@link KafkaProperties} as Spring Boot binds
     * the environment, as the admin client gets them. The CA path is the pod's; here it is the test's copy.
     */
    private static Map<String, Object> clientProperties(String bootstrap, String ca) throws IOException {
        Map<String, Object> environment = new HashMap<>();
        PostgresTlsConfigTest.clientSettings("app").forEach((name, value) -> environment.put(name, String.valueOf(value)));
        KafkaProperties kafka = new KafkaProperties();
        new Binder(ConfigurationPropertySources.from(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment)))
                .bind("spring.kafka", Bindable.ofInstance(kafka));

        Map<String, Object> properties = new HashMap<>(kafka.buildAdminProperties());
        assertThat(properties).containsEntry(AdminClientConfig.SECURITY_PROTOCOL_CONFIG, "SSL")
                .containsEntry("ssl.truststore.type", "PEM")
                .containsEntry("ssl.truststore.location", "/etc/ecomdemo-tls/ca.crt")
                .doesNotContainKey("ssl.endpoint.identification.algorithm");
        properties.put("ssl.truststore.location", files.resolve(ca + ".crt").toString());
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000);
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        return properties;
    }

    private static void copy(GenericContainer<?> container, String file, String path) throws IOException {
        container.copyFileToContainer(Transferable.of(Files.readAllBytes(files.resolve(file)), 0644), path);
    }

    private static Map<String, Object> pod() throws IOException {
        return PostgresTlsConfigTest.podSpec(PostgresTlsConfigTest.resource("k8s/data/kafka.yaml", "StatefulSet", "kafka"));
    }

    private static String brokerImage() throws IOException {
        return (String) PostgresTlsConfigTest.container(pod(), "kafka").get("image");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Writes {@code <name>.crt} and {@code <name>.key} (PKCS#8, as Kafka's PEM keystore needs) to {@link #files}:
     * a self-signed CA when {@code ca} is null, else a certificate for {@code localhost} signed by {@code ca}.
     */
    private static void issue(String name, String ca) throws Exception {
        String key = "openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out /tmp/" + name + ".key";
        String certificate = ca == null
                ? "openssl req -x509 -key /tmp/" + name + ".key -subj /CN=" + name + " -days 1 -out /tmp/" + name + ".crt"
                : "openssl req -new -key /tmp/" + name + ".key -subj /CN=localhost -out /tmp/" + name + ".csr"
                        + " && printf 'subjectAltName=DNS:localhost\\n' > /tmp/" + name + ".ext"
                        + " && openssl x509 -req -in /tmp/" + name + ".csr -CA /tmp/" + ca + ".crt -CAkey /tmp/" + ca
                        + ".key -CAcreateserial -days 1 -extfile /tmp/" + name + ".ext -out /tmp/" + name + ".crt";
        var result = issuer.execInContainer("sh", "-c", key + " && " + certificate);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        for (String suffix : List.of(".crt", ".key")) {
            issuer.copyFileFromContainer("/tmp/" + name + suffix, in -> Files.write(files.resolve(name + suffix),
                    in.readAllBytes()));
        }
    }
}
