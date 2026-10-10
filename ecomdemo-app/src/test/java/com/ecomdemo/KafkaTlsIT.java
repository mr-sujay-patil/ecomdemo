package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.containers.GenericContainer;

/**
 * Kafka's TLS settings work on the real image, with a client's real settings (KI-059).
 *
 * <p>The broker image is started with the StatefulSet's own environment (its listeners) and start command,
 * and a certificate where cert-manager's Secret is mounted ({@link KafkaTestBroker}). The client side is
 * {@code app}'s Kafka settings, given to Spring Boot as its pod gives them, with the client certificate every
 * client presents since Phase 35 ({@code KafkaClientAuthIT} tests that part). Then:
 * <ul>
 *   <li>an SSL client that trusts the CA and checks the name connects;</li>
 *   <li>a plain-text client gets nowhere;</li>
 *   <li>a host name the certificate does not carry is refused by the client;</li>
 *   <li>the {@code cert-reload} sidecar's script puts a renewed certificate in service without a restart.</li>
 * </ul>
 */
@DisplayName("Kafka TLS on the real image (KI-059)")
class KafkaTlsIT {

    private static GenericContainer<?> issuer;

    @TempDir
    static Path files;

    @BeforeAll
    static void startTheIssuer() throws Exception {
        issuer = KafkaTestBroker.startIssuer();
        KafkaTestBroker.issueCa(issuer, files, "ca");
        KafkaTestBroker.issueCa(issuer, files, "other-ca");
        KafkaTestBroker.issueServer(issuer, files, "leaf", "ca");
        KafkaTestBroker.issueServer(issuer, files, "renewed", "other-ca");
        KafkaTestBroker.issueClient(issuer, files, "app-client", "ca", "ecomdemo");
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
        int port = KafkaTestBroker.freePort();
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("mount"), "app-client", "ca");
        try (GenericContainer<?> broker = KafkaTestBroker.startBroker(files, port, "leaf", "ca")) {
            Map<String, Object> verified = adminProperties("localhost:" + port, mount);
            try (Admin admin = Admin.create(verified)) {
                assertThat(admin.listTopics().names().get(30, TimeUnit.SECONDS)).isNotNull();
            }

            Map<String, Object> plain = new HashMap<>(verified);
            plain.put(AdminClientConfig.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT");
            try (Admin admin = Admin.create(plain)) {
                assertThatThrownBy(() -> admin.listTopics().names().get(30, TimeUnit.SECONDS)).isNotNull();
            }

            try (Admin admin = Admin.create(adminProperties("127.0.0.1:" + port, mount))) {
                assertThatThrownBy(() -> admin.listTopics().names().get(30, TimeUnit.SECONDS))
                        .hasStackTraceContaining("SSL handshake failed");
            }
        }
    }

    @Test
    @DisplayName("the cert-reload script puts a renewed certificate in service without a restart")
    void renewalIsReloaded() throws Exception {
        int port = KafkaTestBroker.freePort();
        // The renewal comes from ANOTHER CA, so a client that trusts only that CA tells the two apart.
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("trusts-other-ca"), "app-client", "other-ca");
        try (GenericContainer<?> broker = KafkaTestBroker.startBroker(files, port, "leaf", "ca")) {
            String reload = String.join("\n", PostgresTlsConfigTest.strings(PostgresTlsConfigTest.container(
                    KafkaTestBroker.pod(), "cert-reload").get("args")));
            broker.execInContainer("sh", "-c", "RELOAD_INTERVAL=1 nohup sh -c \"$1\" >/tmp/reload.log 2>&1 &",
                    "reloader", reload);
            KafkaTestBroker.copy(broker, files.resolve("renewed.crt"), "/etc/ecomdemo-tls/tls.crt");
            KafkaTestBroker.copy(broker, files.resolve("renewed.key"), "/etc/ecomdemo-tls/tls.key");

            // Until the reload, the broker still presents the old certificate and the handshake fails.
            Map<String, Object> properties = adminProperties("localhost:" + port, mount);
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).ignoreExceptions().untilAsserted(() -> {
                try (Admin admin = Admin.create(properties)) {
                    assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).isNotNull();
                }
            });
            assertThat(broker.execInContainer("cat", "/tmp/reload.log").getStdout())
                    .contains("reloaded the Kafka certificate");
            assertThat(broker.isRunning()).isTrue();
        }
    }

    /**
     * The admin client settings Spring Boot builds from {@code app}'s Kafka settings: SSL, with the SSL bundle
     * (the client certificate, and the CA it checks the broker against) and Kafka's own host-name check left on.
     */
    private static Map<String, Object> adminProperties(String bootstrap, Path mount) throws Exception {
        Map<String, Object> properties = new HashMap<>();
        KafkaTestBroker.appKafkaClients(bootstrap, mount).run(context ->
                properties.putAll(context.getBean(KafkaAdmin.class).getConfigurationProperties()));
        assertThat(properties).containsEntry(AdminClientConfig.SECURITY_PROTOCOL_CONFIG, "SSL")
                .containsKeys("ssl.engine.factory.class", "org.springframework.boot.ssl.SslBundle")
                .doesNotContainKeys("ssl.endpoint.identification.algorithm", "ssl.truststore.location");
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000);
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        return properties;
    }
}
