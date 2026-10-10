package com.ecomdemo;

import static com.ecomdemo.PostgresTlsConfigTest.clientSettings;
import static com.ecomdemo.PostgresTlsConfigTest.container;
import static com.ecomdemo.PostgresTlsConfigTest.podSpec;
import static com.ecomdemo.PostgresTlsConfigTest.resource;
import static com.ecomdemo.PostgresTlsConfigTest.spec;
import static com.ecomdemo.PostgresTlsConfigTest.strings;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Kafka is reached over verified TLS only, in Kubernetes (KI-059).
 *
 * <p>Before, the broker's only network listener was {@code PLAINTEXT}, so every event (orders, payments,
 * stock) crossed the cluster network in clear text, and anything that could reach the pod could produce
 * or consume. Now the network listener is {@code SSL} with a cert-manager certificate, the plain
 * listeners (controller, inter-broker, the in-pod tools) are bound to 127.0.0.1, and each client uses
 * {@code SSL} with the cluster CA.
 *
 * <p>The fast half: it reads the manifests. {@code KafkaTlsIT} runs the image with the manifest's own
 * listeners and start command, and connects with a client's own settings.
 */
@DisplayName("Kafka is TLS-only on the network in Kubernetes (KI-059)")
class KafkaTlsConfigTest {

    @Test
    @DisplayName("the broker has a certificate that names its Service, in the PKCS#8 form Kafka reads")
    void brokerHasACertificate() throws IOException {
        Map<String, Object> spec = spec(resource("k8s/service-certificates.yaml", "Certificate", "kafka-tls"));

        assertThat(spec.get("secretName")).isEqualTo("kafka-tls");
        assertThat(strings(spec.get("dnsNames"))).contains("kafka", "kafka.ecomdemo.svc.cluster.local");
        assertThat(((Map<?, ?>) spec.get("privateKey")).get("encoding")).isEqualTo("PKCS8");
    }

    @Test
    @DisplayName("the only listener on the network is SSL; the plain ones are bound to the loopback")
    void onlyTheSslListenerIsOnTheNetwork() throws IOException {
        Map<String, String> env = brokerEnvironment();
        Map<String, String> protocols = new HashMap<>();
        for (String entry : env.get("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP").split(",")) {
            String[] nameAndProtocol = entry.split(":");
            protocols.put(nameAndProtocol[0], nameAndProtocol[1]);
        }

        for (String listener : env.get("KAFKA_LISTENERS").split(",")) {
            String name = listener.substring(0, listener.indexOf(':'));
            if (!"SSL".equals(protocols.get(name))) {
                assertThat(listener).as("a plain listener must not be reachable from other pods")
                        .contains("://127.0.0.1:");
            }
        }
        assertThat(env.get("KAFKA_LISTENERS")).contains("INTERNAL://:9092");
        assertThat(protocols).containsEntry("INTERNAL", "SSL");
        assertThat(env.get("KAFKA_ADVERTISED_LISTENERS")).contains("INTERNAL://kafka:9092");
    }

    @Test
    @DisplayName("the broker's keystore is the certificate, and a cert-reload sidecar applies a renewal")
    void keystoreAndReload() throws IOException {
        Map<String, Object> pod = podSpec(resource("k8s/data/kafka.yaml", "StatefulSet", "kafka"));

        assertThat(brokerEnvironment()).containsEntry("KAFKA_SSL_KEYSTORE_TYPE", "PEM")
                .containsEntry("KAFKA_SSL_KEYSTORE_LOCATION", "/etc/kafka-tls/keystore.pem");
        assertThat(String.join(" ", strings(container(pod, "kafka").get("args"))))
                .contains("/etc/ecomdemo-tls/tls.key", "/etc/kafka-tls/keystore.pem");
        assertThat(container(pod, "cert-reload").get("args").toString())
                .contains("listener.name.internal.ssl.keystore.location");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"app", "catalog-service", "inventory-service", "notification-service", "payment-service"})
    @DisplayName("each client speaks SSL and trusts the cluster CA")
    void eachClientUsesTls(String service) throws IOException {
        // Since Phase 35 the CA is the truststore of the client's SSL bundle (with its client certificate,
        // ClientAuthConfigTest), no longer Kafka's own ssl.truststore.* settings.
        assertThat(clientSettings(service))
                .containsEntry("SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
                .containsEntry("SPRING_KAFKA_SECURITY_PROTOCOL", "SSL")
                .containsEntry("SPRING_KAFKA_SSL_BUNDLE", "kafka")
                .containsEntry("SPRING_SSL_BUNDLE_PEM_KAFKA_TRUSTSTORE_CERTIFICATE", "file:/etc/ecomdemo-client-tls/ca.crt")
                .doesNotContainKey("SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM");
    }

    /** The broker container's environment, name to value (the image reads its settings from it). */
    @SuppressWarnings("unchecked")
    static Map<String, String> brokerEnvironment() throws IOException {
        Map<String, Object> broker = container(podSpec(resource("k8s/data/kafka.yaml", "StatefulSet", "kafka")), "kafka");
        Map<String, String> env = new HashMap<>();
        for (Map<String, Object> variable : (List<Map<String, Object>>) broker.get("env")) {
            env.put((String) variable.get("name"), String.valueOf(variable.get("value")));
        }
        return env;
    }
}
