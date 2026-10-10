package com.ecomdemo;

import static com.ecomdemo.PostgresTlsConfigTest.clientSettings;
import static com.ecomdemo.PostgresTlsConfigTest.container;
import static com.ecomdemo.PostgresTlsConfigTest.podSpec;
import static com.ecomdemo.PostgresTlsConfigTest.resource;
import static com.ecomdemo.PostgresTlsConfigTest.spec;
import static com.ecomdemo.PostgresTlsConfigTest.strings;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Redis authenticates its clients by certificate AND password in Kubernetes (Phase 36, KI-063).
 *
 * <p>Before, TLS proved only the SERVER to the client (KI-057): any pod with the password could use the cache,
 * the rate-limit counters and the assistant's conversations. Now Redis asks every client for a certificate from
 * the cluster CA in the TLS handshake, keeps {@code requirepass}, and each of its four clients presents its own
 * certificate through a Spring Boot SSL bundle; the tools inside the Redis pod present the pod's certificate.
 *
 * <p>The fast half: it reads the manifests and the smoke test. {@code RedisClientAuthIT} runs the real image
 * with this configuration.
 */
@DisplayName("Redis client certificates in Kubernetes (Phase 36)")
class RedisClientAuthConfigTest {

    static final String CACHE = "k8s/data/cache.yaml";

    /** The Redis clients and the CN of their client certificate. */
    static final Map<String, String> REDIS_CLIENTS = Map.of(
            "app", "ecomdemo", "catalog-service", "catalog", "gateway-service", "gateway",
            "assistant-service", "assistant");

    /** The arguments that make Redis present ITS certificate as a client: the in-pod tools' identity. */
    static final String POD_CLIENT_CERTIFICATE = "--cert /etc/ecomdemo-tls/tls.crt --key /etc/ecomdemo-tls/tls.key";

    @Test
    @DisplayName("Redis requires a client certificate from the cluster CA, and still the password")
    void redisRequiresACertificateAndThePassword() throws IOException {
        String args = String.join(" ", strings(redis().get("args")));

        assertThat(args).contains("--tls-auth-clients yes", "--tls-ca-cert-file /etc/ecomdemo-tls/ca.crt",
                "--requirepass $(REDIS_PASSWORD)", "--port 0", "--tls-port 6379")
                .doesNotContain("--tls-auth-clients no", "--tls-auth-clients optional");
    }

    @Test
    @DisplayName("every redis-cli in the Redis pod (the probe and the cert-reload sidecar) presents the pod's certificate")
    void inPodToolsPresentACertificate() throws IOException {
        Map<?, ?> probe = (Map<?, ?>) ((Map<?, ?>) redis().get("readinessProbe")).get("exec");
        List<String> calls = new ArrayList<>(List.of(String.join(" ", strings(probe.get("command")))));
        for (Map<String, Object> container : containers()) {
            for (Object line : strings(container.get("args"))) {
                calls.addAll(redisCliCalls(String.valueOf(line)));
            }
        }

        assertThat(calls).as("the probe and the sidecar's reload").hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(call -> assertThat(call).contains("redis-cli", "--tls", POD_CLIENT_CERTIFICATE,
                        "--cacert /etc/ecomdemo-tls/ca.crt"));
    }

    @Test
    @DisplayName("Redis's own certificate may also be used as a client's (`client auth`), for the tools in its pod")
    void redisCertificateServesBothRoles() throws IOException {
        Map<String, Object> spec = spec(resource("k8s/service-certificates.yaml", "Certificate", "cache-tls"));

        // OpenSSL refuses a client certificate whose extended key usage is `server auth` only (RedisClientAuthIT).
        assertThat(strings(spec.get("usages"))).contains("server auth", "client auth");
    }

    @Test
    @DisplayName("the Redis clients are exactly the four services that set SPRING_DATA_REDIS_HOST")
    void everyRedisClientIsListed() throws IOException {
        List<String> clients = new ArrayList<>();
        try (Stream<Path> files = Files.list(ProjectRoot.resolve("k8s/services"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                String service = file.getFileName().toString().replace(".yaml", "");
                if (clientSettings(service).containsKey("SPRING_DATA_REDIS_HOST")) {
                    clients.add(service);
                }
            }
        }

        assertThat(clients).containsExactlyInAnyOrderElementsOf(REDIS_CLIENTS.keySet());
    }

    @ParameterizedTest(name = "{0} ({1})")
    @CsvSource({"gateway-service, gateway", "assistant-service, assistant"})
    @DisplayName("the gateway and the assistant have a client certificate, as the database clients do since Phase 35")
    void newClientCertificates(String service, String commonName) throws IOException {
        Map<String, Object> spec = spec(resource("k8s/service-certificates.yaml", "Certificate", service + "-client-tls"));
        Map<?, ?> key = (Map<?, ?>) spec.get("privateKey");

        assertThat(spec.get("secretName")).isEqualTo(service + "-client-tls");
        assertThat(spec.get("commonName")).isEqualTo(commonName);
        assertThat(strings(spec.get("usages"))).contains("client auth").doesNotContain("server auth");
        assertThat(spec).doesNotContainKeys("dnsNames", "ipAddresses");
        assertThat(key.get("algorithm")).isEqualTo("RSA");
        assertThat(key.get("encoding")).isEqualTo("PKCS8");
        assertThat(key.get("rotationPolicy")).isEqualTo("Always");
        assertThat(((Map<?, ?>) spec.get("issuerRef")).get("name")).isEqualTo("ecomdemo-ca");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"app", "catalog-service", "gateway-service", "assistant-service"})
    @DisplayName("each Redis client presents its certificate through the `redis` SSL bundle, which reloads on renewal")
    void eachClientPresentsItsCertificate(String service) throws IOException {
        Map<String, Object> settings = clientSettings(service);

        assertThat(settings).containsEntry("SPRING_DATA_REDIS_SSL_ENABLED", "true")
                .containsEntry("SPRING_DATA_REDIS_SSL_BUNDLE", "redis")
                .containsEntry("SPRING_SSL_BUNDLE_PEM_REDIS_KEYSTORE_CERTIFICATE", "file:/etc/ecomdemo-client-tls/tls.crt")
                .containsEntry("SPRING_SSL_BUNDLE_PEM_REDIS_KEYSTORE_PRIVATEKEY", "file:/etc/ecomdemo-client-tls/tls.key")
                .containsEntry("SPRING_SSL_BUNDLE_PEM_REDIS_TRUSTSTORE_CERTIFICATE", "file:/etc/ecomdemo-client-tls/ca.crt")
                .containsEntry("SPRING_SSL_BUNDLE_PEM_REDIS_RELOAD_ON_UPDATE", "true");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"app", "catalog-service", "gateway-service", "assistant-service"})
    @DisplayName("each Redis client mounts its own client certificate, the key readable by the service only")
    void eachClientMountsItsCertificate(String service) throws IOException {
        Map<String, Object> pod = podSpec(resource("k8s/services/" + service + ".yaml", "Deployment", service));
        Map<String, Object> volume = volumeOfSecret(pod, service + "-client-tls");
        Matcher gid = Pattern.compile("addgroup --system --gid (\\d+) ecomdemo")
                .matcher(Files.readString(ProjectRoot.resolve("Dockerfile")));
        assertThat(gid.find()).isTrue();

        assertThat(((Map<?, ?>) volume.get("secret")).get("defaultMode")).as("0440").isEqualTo(0440);
        assertThat(((Map<?, ?>) pod.get("securityContext")).get("fsGroup")).isEqualTo(Integer.valueOf(gid.group(1)));
        assertThat(mountPath(container(pod, service), (String) volume.get("name"))).isEqualTo("/etc/ecomdemo-client-tls");
    }

    @Test
    @DisplayName("the smoke test's redis-cli on the cluster presents the pod's certificate, and counts 25 certificates")
    void smokeTestPresentsACertificate() throws IOException {
        String smoke = Files.readString(ProjectRoot.resolve("scripts/smoke-test.sh"));
        Matcher k8sTls = Pattern.compile("then REDIS_TLS=\"([^\"]*)\"").matcher(smoke);

        assertThat(k8sTls.find()).isTrue();
        assertThat(k8sTls.group(1)).contains("--tls", "--cacert /etc/ecomdemo-tls/ca.crt", POD_CLIENT_CERTIFICATE);
        assertThat(smoke).contains("cert-manager has all 25 certificates Ready");
    }

    /** Each {@code redis-cli} command in a shell script, its backslash-continued lines joined. */
    static List<String> redisCliCalls(String script) {
        List<String> calls = new ArrayList<>();
        for (String command : script.replace("\\\n", " ").lines().toList()) {
            int at = command.indexOf("redis-cli");
            if (at >= 0) {
                calls.add(command.substring(at).replaceAll("\\s+", " "));
            }
        }
        return calls;
    }

    private static Map<String, Object> redis() throws IOException {
        return container(podSpec(resource(CACHE, "Deployment", "cache")), "redis");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> containers() throws IOException {
        return (List<Map<String, Object>>) podSpec(resource(CACHE, "Deployment", "cache")).get("containers");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> volumeOfSecret(Map<String, Object> pod, String secretName) {
        return ((List<Map<String, Object>>) pod.get("volumes")).stream()
                .filter(v -> v.get("secret") instanceof Map<?, ?> s && secretName.equals(s.get("secretName")))
                .findFirst().orElseThrow(() -> new AssertionError("no volume of the Secret " + secretName));
    }

    @SuppressWarnings("unchecked")
    private static String mountPath(Map<String, Object> container, String volume) {
        return ((List<Map<String, Object>>) container.get("volumeMounts")).stream()
                .filter(mount -> volume.equals(mount.get("name"))).map(mount -> (String) mount.get("mountPath"))
                .findFirst().orElse(null);
    }
}
