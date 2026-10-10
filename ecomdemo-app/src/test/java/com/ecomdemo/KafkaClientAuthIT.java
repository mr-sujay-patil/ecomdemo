package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SslAuthenticationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

/**
 * Kafka authenticates its clients by certificate and authorizes them by ACL, on the real image (Phase 35).
 *
 * <p>One broker for the class, started as its StatefulSet starts it ({@link KafkaTestBroker}), and the manifest's
 * own {@code acls} container script, which syncs {@code k8s/data/kafka-acls.yaml} through the loopback listener
 * and then marks the pod ready. The clients are a service's own: {@code app}'s Kafka settings, given to Spring
 * Boot as its pod gives them, with the certificate of the identity under test where the pod mounts its client
 * certificate. Then:
 * <ul>
 *   <li>the ACLs are exactly the table, and a second sync changes nothing but what is not in it;</li>
 *   <li>app's certificate writes its topic, reads its saga topic in its group, and is refused another service's;</li>
 *   <li>notification-service's certificate cannot read payments.completed, and reads orders.placed in a
 *       {@code @RetryableTopic} group (the prefixed group ACL);</li>
 *   <li>no certificate, or one from another CA, fails the handshake; a CN of ANONYMOUS is no super user;</li>
 *   <li>a renewed client certificate is used by the next connection, with no restart.</li>
 * </ul>
 */
@DisplayName("Kafka client certificates and ACLs on the real image (Phase 35)")
class KafkaClientAuthIT {

    private static GenericContainer<?> issuer;
    private static GenericContainer<?> broker;
    private static String bootstrap;

    @TempDir
    static Path files;

    @BeforeAll
    static void startTheBroker() throws Exception {
        issuer = KafkaTestBroker.startIssuer();
        KafkaTestBroker.issueCa(issuer, files, "ca");
        KafkaTestBroker.issueCa(issuer, files, "other-ca");
        KafkaTestBroker.issueServer(issuer, files, "broker", "ca");
        KafkaTestBroker.issueClient(issuer, files, "ecomdemo", "ca", "ecomdemo");
        KafkaTestBroker.issueClient(issuer, files, "notification", "ca", "notification");
        KafkaTestBroker.issueClient(issuer, files, "other-ca-ecomdemo", "other-ca", "ecomdemo");
        KafkaTestBroker.issueClient(issuer, files, "anonymous", "ca", "ANONYMOUS");

        int port = KafkaTestBroker.freePort();
        bootstrap = "localhost:" + port;
        broker = KafkaTestBroker.startBroker(files, port, "broker", "ca");

        // The `acls` container of the pod: its own script and ConfigMap, run in the broker's container (in the
        // pod they share the network, so 127.0.0.1:9094 is the same listener). It marks itself ready when done.
        Map<String, String> configMap = ClientAuthConfigTest.aclConfigMap();
        broker.copyFileToContainer(Transferable.of(configMap.get("acls.txt"), 0644), "/etc/kafka-acls/acls.txt");
        broker.copyFileToContainer(Transferable.of(configMap.get("sync.sh"), 0644), "/etc/kafka-acls/sync.sh");
        String acls = String.join("\n", PostgresTlsConfigTest.strings(
                PostgresTlsConfigTest.container(KafkaTestBroker.pod(), "acls").get("args")));
        broker.execInContainer("sh", "-c", "KAFKA_HEAP_OPTS=-Xmx64m nohup sh -c \"$1\" >/tmp/acls.log 2>&1 &", "acls", acls);

        for (String topic : List.of("orders.created", "orders.placed", "payments.completed", "catalog.product-changed")) {
            inPod("/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "127.0.0.1:9094", "--create", "--topic", topic,
                    "--partitions", "1", "--replication-factor", "1");
        }
        for (String topic : List.of("orders.placed", "payments.completed")) {
            ExecResult sent = broker.execInContainer("sh", "-c", "echo '{\"for\":\"" + topic + "\"}' | "
                    + "/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server 127.0.0.1:9094 --topic " + topic);
            assertThat(sent.getExitCode()).as(sent.getStderr()).isZero();
        }

        // What the pod's readiness probe waits for.
        await().atMost(Duration.ofMinutes(4)).pollInterval(Duration.ofSeconds(2)).until(() ->
                broker.execInContainer("test", "-f", "/tmp/acls-synced").getExitCode() == 0);
    }

    @AfterAll
    static void stop() {
        for (GenericContainer<?> container : new GenericContainer<?>[] {broker, issuer}) {
            if (container != null) {
                container.stop();
            }
        }
    }

    @Test
    @DisplayName("the broker's ACLs are the table; a second sync only removes what is not in it")
    void aclsAreTheTable() throws Exception {
        int bindings = ClientAuthConfigTest.acls().stream().mapToInt(acl -> acl.operations().size()).sum();
        assertThat(inPod("cat", "/tmp/acls.log")).contains("ACLs in sync with acls.txt: " + bindings + " bindings");
        assertThat(inPod("/opt/kafka/bin/kafka-acls.sh", "--bootstrap-server", "127.0.0.1:9094", "--list").lines()
                .filter(line -> line.contains("principal=")).count()).isEqualTo(bindings);

        // An ACL added by hand is not in the table: the next sync takes it away and adds nothing.
        inPod("/opt/kafka/bin/kafka-acls.sh", "--bootstrap-server", "127.0.0.1:9094", "--add", "--allow-principal",
                "User:notification", "--operation", "Read", "--topic", "payments.completed");
        String second = inPod("sh", "-c", "KAFKA_HEAP_OPTS=-Xmx64m sh /etc/kafka-acls/sync.sh");
        assertThat(second).contains("removed ALLOW READ on TOPIC payments.completed (LITERAL) for User:notification")
                .doesNotContain("added").contains(bindings + " bindings");
        assertThat(inPod("sh", "-c", "KAFKA_HEAP_OPTS=-Xmx64m sh /etc/kafka-acls/sync.sh"))
                .doesNotContain("added").doesNotContain("removed");
    }

    @Test
    @DisplayName("app's certificate writes and reads its own topics, and is refused another service's")
    void appIsAllowedItsOwnTopicsOnly() throws Exception {
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("app"), "ecomdemo", "ca");
        KafkaTestBroker.appKafkaClients(bootstrap, mount).run(context -> {
            ProducerFactory<?, ?> producers = context.getBean(ProducerFactory.class);
            try (@SuppressWarnings("unchecked") Producer<Object, Object> producer =
                    (Producer<Object, Object>) producers.createProducer()) {
                // An idempotent producer (Kafka's default) with only WRITE on its topics: no cluster right needed.
                assertThat(producer.send(new ProducerRecord<>("orders.created", "1", "{}")).get(30, TimeUnit.SECONDS)
                        .topic()).isEqualTo("orders.created");
                assertThatThrownBy(() -> producer.send(new ProducerRecord<>("catalog.product-changed", "1", "{}"))
                        .get(30, TimeUnit.SECONDS)).hasRootCauseInstanceOf(TopicAuthorizationException.class);
            }
            // The saga listener's group: READ on payments.completed and on the group order-service.
            ConsumerFactory<?, ?> consumers = context.getBean(ConsumerFactory.class);
            try (Consumer<?, ?> consumer = consumers.createConsumer("order-service", "it")) {
                consumer.subscribe(List.of("payments.completed"));
                assertThat(pollOnce(consumer).count()).isOne();
            }
            // A group that is not its own.
            try (Consumer<?, ?> consumer = consumers.createConsumer("payment-service", "it")) {
                consumer.subscribe(List.of("payments.completed"));
                assertThatThrownBy(() -> pollOnce(consumer)).isInstanceOf(
                        org.apache.kafka.common.errors.GroupAuthorizationException.class);
            }
        });
    }

    @Test
    @DisplayName("notification-service's certificate cannot read payments.completed, and reads orders.placed")
    void notificationIsLimitedToItsTopics() throws Exception {
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("notification"), "notification", "ca");
        KafkaTestBroker.appKafkaClients(bootstrap, mount).run(context -> {
            ConsumerFactory<?, ?> consumers = context.getBean(ConsumerFactory.class);
            try (Consumer<?, ?> consumer = consumers.createConsumer("ecomdemo-notification", "it")) {
                // The smoke test's check, as the CLI does it: assigned, no group needed.
                consumer.assign(List.of(new TopicPartition("payments.completed", 0)));
                assertThatThrownBy(() -> consumer.poll(Duration.ofSeconds(10)))
                        .isInstanceOf(TopicAuthorizationException.class)
                        .hasMessageContaining("payments.completed");
            }
            // @RetryableTopic's groups are the listener's group plus a suffix: the ACL on the prefix covers them.
            try (Consumer<?, ?> consumer = consumers.createConsumer("ecomdemo-notification-retry-0", "it")) {
                consumer.subscribe(List.of("orders.placed"));
                assertThat(pollOnce(consumer).count()).isOne();
            }
        });
    }

    @Test
    @DisplayName("no certificate, or one from another CA, fails the handshake; CN=ANONYMOUS is not the super user")
    void unauthenticatedClientsAreRefused() throws Exception {
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("trusting"), "ecomdemo", "ca");
        Map<String, Object> admin = new HashMap<>();
        KafkaTestBroker.appKafkaClients(bootstrap, mount).run(context ->
                admin.putAll(context.getBean(KafkaAdmin.class).getConfigurationProperties()));

        // Kafka's own settings with the CA and no keystore: a TLS client that checks the broker, with no certificate.
        Map<String, Object> noCertificate = new HashMap<>(admin);
        noCertificate.remove("ssl.engine.factory.class");
        noCertificate.remove("org.springframework.boot.ssl.SslBundle");
        noCertificate.put("ssl.truststore.type", "PEM");
        noCertificate.put("ssl.truststore.location", mount.resolve("ca.crt").toString());
        assertThat(listTopicsFailure(noCertificate)).isInstanceOf(SslAuthenticationException.class);

        // A certificate for the right name, from a CA the broker does not trust.
        Path otherCa = KafkaTestBroker.clientMount(files, files.resolve("other-ca"), "other-ca-ecomdemo", "ca");
        KafkaTestBroker.appKafkaClients(bootstrap, otherCa).run(context -> assertThat(listTopicsFailure(
                context.getBean(KafkaAdmin.class).getConfigurationProperties())).isInstanceOf(SslAuthenticationException.class));

        // A valid certificate whose CN is ANONYMOUS, the plain listeners' super user: the principal is its full DN.
        Path anonymous = KafkaTestBroker.clientMount(files, files.resolve("anonymous"), "anonymous", "ca");
        KafkaTestBroker.appKafkaClients(bootstrap, anonymous).run(context -> {
            try (Consumer<?, ?> consumer = context.getBean(ConsumerFactory.class).createConsumer()) {
                assertThatThrownBy(() -> consumer.partitionsFor("orders.created"))
                        .isInstanceOf(TopicAuthorizationException.class);
            }
        });
    }

    @Test
    @DisplayName("a renewed client certificate is used by the next connection, without a restart")
    void renewedCertificateIsUsed() throws Exception {
        // The service starts with a certificate that is not allowed orders.created, and cert-manager then
        // "renews" it with one that is: the difference is visible on the next connection.
        Path mount = KafkaTestBroker.clientMount(files, files.resolve("renewal"), "notification", "ca");
        KafkaTestBroker.appKafkaClients(bootstrap, mount)
                .withPropertyValues("spring.ssl.bundle.watch.file.quiet-period=1s")
                .run(context -> {
                    ConsumerFactory<?, ?> consumers = context.getBean(ConsumerFactory.class);
                    try (Consumer<?, ?> consumer = consumers.createConsumer()) {
                        assertThatThrownBy(() -> consumer.partitionsFor("orders.created"))
                                .isInstanceOf(TopicAuthorizationException.class);
                    }

                    KafkaTestBroker.clientMount(files, mount, "ecomdemo", "ca");

                    await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).ignoreExceptions()
                            .untilAsserted(() -> {
                                try (Consumer<?, ?> consumer = consumers.createConsumer()) {
                                    assertThat(consumer.partitionsFor("orders.created")).hasSize(1);
                                }
                            });
                    // The outbox relay and the dead-letter code copy these settings: they get the current bundle too.
                    assertThat(consumers.getConfigurationProperties().get("org.springframework.boot.ssl.SslBundle"))
                            .hasToString("the current SSL bundle 'kafka'");
                    assertThat(context.getBean(ProducerFactory.class).getConfigurationProperties()
                            .get("org.springframework.boot.ssl.SslBundle")).hasToString("the current SSL bundle 'kafka'");
                });
    }

    /** Polls until the consumer gets records (its group starts at the earliest offset), or for 30 seconds. */
    private static ConsumerRecords<?, ?> pollOnce(Consumer<?, ?> consumer) {
        ConsumerRecords<?, ?> records = ConsumerRecords.empty();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (records.isEmpty() && System.nanoTime() < deadline) {
            records = consumer.poll(Duration.ofSeconds(1));
        }
        return records;
    }

    private static Throwable listTopicsFailure(Map<String, Object> properties) {
        try (Admin admin = Admin.create(properties)) {
            admin.listTopics().names().get(30, TimeUnit.SECONDS);
            throw new AssertionError("the broker answered");
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && !(cause instanceof SslAuthenticationException)) {
                cause = cause.getCause();
            }
            return cause;
        }
    }

    /** Runs a command in the broker's container, as the in-pod tools run; it must succeed. */
    private static String inPod(String... command) throws Exception {
        ExecResult result = broker.execInContainer(command);
        assertThat(result.getExitCode()).as(String.join(" ", command) + ": " + result.getStderr()).isZero();
        return result.getStdout();
    }
}
