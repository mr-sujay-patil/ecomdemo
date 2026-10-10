package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.outbox.internal.KafkaClientCertificateConfig;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * The Kafka broker of {@code k8s/data/kafka.yaml}, run as its StatefulSet runs it, and a service's Kafka clients
 * built as the service builds them, for {@code KafkaTlsIT} (KI-059) and {@code KafkaClientAuthIT} (Phase 35).
 *
 * <p>The broker: the manifest's image, environment and start command, with a certificate where cert-manager's
 * Secret is mounted. One change: it advertises {@code localhost} on a port of this machine, because the test
 * reaches it from outside the container network.
 *
 * <p>The clients: {@code app}'s Kafka settings from its ConfigMap, given to Spring Boot as environment variables
 * (so a misspelt name fails here too), with Boot's Kafka and SSL auto-configuration and the outbox library's
 * {@link KafkaClientCertificateConfig}. Paths under {@code /etc/ecomdemo-client-tls} point at a test directory
 * laid out like that mount.
 */
final class KafkaTestBroker {

    static final String CLIENT_MOUNT = "/etc/ecomdemo-client-tls";

    private KafkaTestBroker() {
    }

    static Map<String, Object> pod() throws IOException {
        return PostgresTlsConfigTest.podSpec(PostgresTlsConfigTest.resource("k8s/data/kafka.yaml", "StatefulSet", "kafka"));
    }

    static String image() throws IOException {
        return (String) PostgresTlsConfigTest.container(pod(), "kafka").get("image");
    }

    /** A container of the broker's image that only runs openssl for the tests: started, to be stopped by the caller. */
    static GenericContainer<?> startIssuer() throws IOException {
        GenericContainer<?> issuer = new GenericContainer<>(image())
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sleep", "infinity"));
        issuer.start();
        return issuer;
    }

    /** A self-signed CA: {@code <name>.crt} and {@code <name>.key} in {@code files}. */
    static void issueCa(GenericContainer<?> issuer, Path files, String name) throws Exception {
        run(issuer, files, name, "openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out /tmp/" + name + ".key"
                + " && openssl req -x509 -key /tmp/" + name + ".key -subj /CN=" + name + " -days 1 -out /tmp/" + name + ".crt");
    }

    /** The broker's certificate for {@code localhost}, signed by {@code ca}, with the PKCS#8 key Kafka's PEM keystore needs. */
    static void issueServer(GenericContainer<?> issuer, Path files, String name, String ca) throws Exception {
        run(issuer, files, name, "openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out /tmp/" + name + ".key"
                + signed(name, ca, "localhost", "subjectAltName=DNS:localhost"));
    }

    /**
     * A client certificate for {@code commonName}, signed by {@code ca}, as cert-manager issues the
     * {@code <service>-client-tls} certificates: RSA, unencrypted PKCS#8, the `client auth` usage.
     */
    static void issueClient(GenericContainer<?> issuer, Path files, String name, String ca, String commonName)
            throws Exception {
        run(issuer, files, name, "openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /tmp/" + name + ".key"
                + signed(name, ca, commonName, "extendedKeyUsage=clientAuth"));
    }

    private static String signed(String name, String ca, String commonName, String extension) {
        return " && openssl req -new -key /tmp/" + name + ".key -subj /CN=" + commonName + " -out /tmp/" + name + ".csr"
                + " && printf '" + extension + "\\n' > /tmp/" + name + ".ext"
                + " && openssl x509 -req -in /tmp/" + name + ".csr -CA /tmp/" + ca + ".crt -CAkey /tmp/" + ca
                + ".key -CAcreateserial -days 1 -extfile /tmp/" + name + ".ext -out /tmp/" + name + ".crt";
    }

    private static void run(GenericContainer<?> issuer, Path files, String name, String command) throws Exception {
        var result = issuer.execInContainer("sh", "-c", command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        for (String suffix : List.of(".crt", ".key")) {
            issuer.copyFileFromContainer("/tmp/" + name + suffix, in -> Files.write(files.resolve(name + suffix),
                    in.readAllBytes()));
        }
    }

    /** The broker, started as its StatefulSet starts it, with {@code leaf} as its certificate and {@code ca} as the CA. */
    static GenericContainer<?> startBroker(Path files, int port, String leaf, String ca) throws IOException {
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
                .withCopyToContainer(Transferable.of(Files.readAllBytes(files.resolve(leaf + ".crt")), 0644),
                        "/etc/ecomdemo-tls/tls.crt")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(files.resolve(leaf + ".key")), 0644),
                        "/etc/ecomdemo-tls/tls.key")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(files.resolve(ca + ".crt")), 0644),
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

    static void copy(GenericContainer<?> container, Path file, String path) throws IOException {
        container.copyFileToContainer(Transferable.of(Files.readAllBytes(file), 0644), path);
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * A directory laid out like the pod's client-certificate mount ({@code tls.crt}, {@code tls.key},
     * {@code ca.crt}), holding the client certificate {@code client} and the CA {@code ca} from {@code files}.
     * Calling it again with the same {@code directory} replaces the files, as a renewal does.
     */
    static Path clientMount(Path files, Path directory, String client, String ca) throws IOException {
        Files.createDirectories(directory);
        Files.copy(files.resolve(client + ".crt"), directory.resolve("tls.crt"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(files.resolve(client + ".key"), directory.resolve("tls.key"), StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(directory.resolve("tls.key"), PosixFilePermissions.fromString("rw-------"));
        Files.copy(files.resolve(ca + ".crt"), directory.resolve("ca.crt"), StandardCopyOption.REPLACE_EXISTING);
        return directory;
    }

    /**
     * {@code app}'s Kafka clients, as the service builds them: its ConfigMap's {@code SPRING_KAFKA_*} and Kafka SSL
     * bundle settings as environment variables, the bootstrap address replaced, the client-certificate paths
     * pointing at {@code mount}.
     */
    static ApplicationContextRunner appKafkaClients(String bootstrap, Path mount) throws IOException {
        Map<String, Object> environment = new HashMap<>();
        PostgresTlsConfigTest.clientSettings("app").forEach((name, value) -> {
            if (name.startsWith("SPRING_KAFKA_") || name.startsWith("SPRING_SSL_BUNDLE_PEM_KAFKA_")) {
                environment.put(name, String.valueOf(value).replace(CLIENT_MOUNT, mount.toString()));
            }
        });
        assertThat(environment).containsEntry("SPRING_KAFKA_SSL_BUNDLE", "kafka");
        environment.put("SPRING_KAFKA_BOOTSTRAP_SERVERS", bootstrap);
        return new ApplicationContextRunner()
                // Boot maps SPRING_KAFKA_... names only in a source named systemEnvironment.
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                environment)))
                .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class, KafkaAutoConfiguration.class))
                .withUserConfiguration(KafkaClientCertificateConfig.class)
                .withPropertyValues(
                        // As every service's application.properties: a new group reads a topic from its start.
                        "spring.kafka.consumer.auto-offset-reset=earliest",
                        "spring.kafka.admin.properties.request.timeout.ms=5000",
                        "spring.kafka.admin.properties.default.api.timeout.ms=15000",
                        "spring.kafka.consumer.properties.request.timeout.ms=5000",
                        "spring.kafka.consumer.properties.default.api.timeout.ms=15000",
                        "spring.kafka.producer.properties.max.block.ms=15000");
    }
}
