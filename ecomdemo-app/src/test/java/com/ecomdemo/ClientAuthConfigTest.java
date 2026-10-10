package com.ecomdemo;

import static com.ecomdemo.PostgresTlsConfigTest.clientSettings;
import static com.ecomdemo.PostgresTlsConfigTest.container;
import static com.ecomdemo.PostgresTlsConfigTest.hbaRules;
import static com.ecomdemo.PostgresTlsConfigTest.podSpec;
import static com.ecomdemo.PostgresTlsConfigTest.resource;
import static com.ecomdemo.PostgresTlsConfigTest.spec;
import static com.ecomdemo.PostgresTlsConfigTest.strings;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Kafka and PostgreSQL authenticate their clients by certificate in Kubernetes, and Kafka authorizes them
 * per service (Phase 35).
 *
 * <p>Before, TLS proved the SERVER to the client only (KI-058, KI-059): a database login needed just the
 * password, and any pod that reached Kafka could produce or consume any topic. Now each database client has
 * a certificate whose CN is its database user, each database requires it ({@code clientcert=verify-full}),
 * Kafka requires one on its network listener and maps its CN to a principal, and the authorizer allows each
 * principal only what {@code k8s/data/kafka-acls.yaml} lists.
 *
 * <p>The fast half: it reads the manifests. {@code PostgresClientAuthIT} and {@code KafkaClientAuthIT} run the
 * real images with this configuration.
 */
@DisplayName("Client certificates for Kafka and PostgreSQL in Kubernetes (Phase 35)")
class ClientAuthConfigTest {

    static final String ACLS = "k8s/data/kafka-acls.yaml";

    /** The Kafka clients and their principals; customer-service has a database but no Kafka. */
    static final Map<String, String> KAFKA_PRINCIPALS = Map.of(
            "app", "ecomdemo", "catalog-service", "catalog", "inventory-service", "inventory",
            "notification-service", "notification", "payment-service", "payment");

    @ParameterizedTest(name = "{0} ({1})")
    @CsvSource({"app, db", "catalog-service, catalog-db", "customer-service, customer-db",
            "inventory-service, inventory-db", "notification-service, notification-db", "payment-service, payment-db"})
    @DisplayName("each database client has its own client certificate, whose CN is its database user")
    void eachClientHasACertificate(String service, String database) throws IOException {
        Map<String, Object> spec = spec(resource("k8s/service-certificates.yaml", "Certificate", service + "-client-tls"));
        Map<?, ?> key = (Map<?, ?>) spec.get("privateKey");

        assertThat(spec.get("secretName")).isEqualTo(service + "-client-tls");
        assertThat(spec.get("commonName")).as("PostgreSQL requires CN = the login with clientcert=verify-full")
                .isEqualTo(databaseUser(database));
        assertThat(strings(spec.get("usages"))).contains("client auth").doesNotContain("server auth");
        assertThat(spec).doesNotContainKeys("dnsNames", "ipAddresses");
        assertThat(key.get("algorithm")).as("pgjdbc reads PEM keys as RSA").isEqualTo("RSA");
        assertThat(key.get("encoding")).as("the PEM form pgjdbc and Kafka read").isEqualTo("PKCS8");
        assertThat(((Map<?, ?>) spec.get("issuerRef")).get("name")).isEqualTo("ecomdemo-ca");
    }

    @Test
    @DisplayName("pg_hba.conf requires the password AND a certificate on every TCP connection")
    void hbaRequiresACertificate() throws IOException {
        List<String> tcp = hbaRules().stream().filter(rule -> !rule.startsWith("local ")).toList();

        assertThat(tcp).containsExactly("hostssl all all all scram-sha-256 clientcert=verify-full");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"db", "catalog-db", "customer-db", "inventory-db", "notification-db", "payment-db"})
    @DisplayName("each database verifies client certificates against the cluster CA")
    void eachDatabaseHasTheCa(String database) throws IOException {
        Map<String, Object> pod = podSpec(resource("k8s/data/" + database + ".yaml", "StatefulSet", database));

        assertThat(String.join(" ", strings(container(pod, "postgres").get("args"))))
                .contains("-c ssl_ca_file=/etc/ecomdemo-tls/ca.crt");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"app", "catalog-service", "customer-service", "inventory-service", "notification-service",
            "payment-service"})
    @DisplayName("each database client sends its certificate, mounted from its own Secret")
    void eachClientSendsItsCertificate(String service) throws IOException {
        assertThat(clientSettings(service))
                .containsEntry("SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SSLCERT", "/etc/ecomdemo-client-tls/tls.crt")
                .containsEntry("SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SSLKEY", "/etc/ecomdemo-client-tls/tls.key");
        assertThat(clientCertificateMount(service)).isEqualTo("/etc/ecomdemo-client-tls");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"app", "catalog-service", "customer-service", "inventory-service", "notification-service",
            "payment-service"})
    @DisplayName("the client key is readable by the service and by no one else, or the PostgreSQL driver refuses it")
    void clientKeyIsPrivate(String service) throws IOException {
        Map<String, Object> pod = podSpec(resource("k8s/services/" + service + ".yaml", "Deployment", service));
        Map<?, ?> secret = clientCertificateVolume(pod, service);
        Matcher gid = Pattern.compile("addgroup --system --gid (\\d+) ecomdemo")
                .matcher(Files.readString(ProjectRoot.resolve("Dockerfile")));
        assertThat(gid.find()).isTrue();

        // pgjdbc: a key file owned by root may be at most 0640, and Secret files are root's (0644 by default).
        assertThat(secret.get("defaultMode")).as("0440").isEqualTo(0440);
        // ...and group-readable is readable by the service only if the files' group is the service's group.
        assertThat(((Map<?, ?>) pod.get("securityContext")).get("fsGroup")).isEqualTo(Integer.valueOf(gid.group(1)));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"app", "catalog-service", "inventory-service", "notification-service", "payment-service"})
    @DisplayName("each Kafka client presents its certificate through an SSL bundle that reloads on renewal")
    void eachKafkaClientPresentsItsCertificate(String service) throws IOException {
        assertThat(clientSettings(service))
                .containsEntry("SPRING_KAFKA_SECURITY_PROTOCOL", "SSL")
                .containsEntry("SPRING_KAFKA_SSL_BUNDLE", "kafka")
                .containsEntry("SPRING_SSL_BUNDLE_PEM_KAFKA_KEYSTORE_CERTIFICATE", "file:/etc/ecomdemo-client-tls/tls.crt")
                .containsEntry("SPRING_SSL_BUNDLE_PEM_KAFKA_KEYSTORE_PRIVATEKEY", "file:/etc/ecomdemo-client-tls/tls.key")
                .containsEntry("SPRING_SSL_BUNDLE_PEM_KAFKA_TRUSTSTORE_CERTIFICATE", "file:/etc/ecomdemo-client-tls/ca.crt")
                .containsEntry("SPRING_SSL_BUNDLE_PEM_KAFKA_RELOAD_ON_UPDATE", "true")
                // Kafka's own SSL settings would be ignored beside a bundle; none may be left to mislead.
                .doesNotContainKeys("SPRING_KAFKA_PROPERTIES_SSL_TRUSTSTORE_TYPE",
                        "SPRING_KAFKA_PROPERTIES_SSL_TRUSTSTORE_LOCATION",
                        "SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM");
    }

    @Test
    @DisplayName("the broker requires a client certificate from the cluster CA, and authorizes by ACL")
    void brokerAuthenticatesAndAuthorizes() throws IOException {
        Map<String, String> env = KafkaTlsConfigTest.brokerEnvironment();

        assertThat(env).containsEntry("KAFKA_SSL_CLIENT_AUTH", "required")
                .containsEntry("KAFKA_SSL_TRUSTSTORE_TYPE", "PEM")
                .containsEntry("KAFKA_SSL_TRUSTSTORE_LOCATION", "/etc/ecomdemo-tls/ca.crt")
                .containsEntry("KAFKA_AUTHORIZER_CLASS_NAME", "org.apache.kafka.metadata.authorizer.StandardAuthorizer")
                .containsEntry("KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND", "false")
                // The plain listeners' identity; safe only because they are loopback-only (KafkaTlsConfigTest).
                .containsEntry("KAFKA_SUPER_USERS", "User:ANONYMOUS");
    }

    @Test
    @DisplayName("a certificate's CN becomes the principal, but never ANONYMOUS or anything but a plain name")
    void principalMapping() throws IOException {
        String rules = KafkaTlsConfigTest.brokerEnvironment().get("KAFKA_SSL_PRINCIPAL_MAPPING_RULES");
        Matcher rule = Pattern.compile("^RULE:(.*)/\\$1/,DEFAULT$").matcher(rules);
        assertThat(rule.matches()).as(rules).isTrue();
        Pattern subject = Pattern.compile(rule.group(1));

        assertThat(principal(subject, "CN=catalog")).isEqualTo("catalog");
        assertThat(principal(subject, "CN=ANONYMOUS")).as("never the super user").isEqualTo("CN=ANONYMOUS");
        assertThat(principal(subject, "CN=catalog,O=other")).isEqualTo("CN=catalog,O=other");
        assertThat(principal(subject, "CN=*")).isEqualTo("CN=*");
    }

    @Test
    @DisplayName("the ACLs name only the five Kafka clients, with only the rights a client needs")
    void aclsAreLeastPrivilege() throws IOException {
        List<Acl> acls = acls();
        Set<String> principals = new TreeSet<>();
        acls.forEach(acl -> principals.add(acl.principal()));

        assertThat(principals).containsExactlyInAnyOrderElementsOf(KAFKA_PRINCIPALS.values());
        for (Acl acl : acls) {
            if (acl.type().equals("group")) {
                assertThat(acl.operations()).as(acl.toString()).containsExactly("READ");
            } else {
                assertThat(acl.type()).as(acl.toString()).isEqualTo("topic");
                assertThat(acl.name()).as("a topic is granted by name, never by prefix: " + acl).doesNotEndWith("*");
                assertThat(acl.operations()).as(acl.toString()).isSubsetOf("CREATE", "DESCRIBE", "READ", "WRITE");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // principal, topic, operations: one producer, consumer and creator per service, read from the code
            "ecomdemo, orders.created, CREATE WRITE",
            "ecomdemo, payments.completed, READ WRITE",
            "catalog, catalog.product-changed, CREATE READ WRITE",
            "catalog, inventory.stock-changed, READ",
            "inventory, inventory.stock-changed, CREATE WRITE",
            "inventory, orders.created-dlt, WRITE",
            "payment, inventory.stock-reserved, READ",
            "payment, payments.failed-dlt, CREATE DESCRIBE",
            "notification, orders.placed-dlt, READ WRITE"})
    @DisplayName("a service gets exactly what its code does on a topic")
    void aclMatchesTheCode(String principal, String topic, String operations) throws IOException {
        assertThat(acls()).filteredOn(acl -> acl.principal().equals(principal) && acl.name().equals(topic))
                .singleElement().extracting(Acl::operations)
                .isEqualTo(List.of(operations.split(" ")));
    }

    @Test
    @DisplayName("notification-service has no right on another service's topic (the smoke test reads payments.completed)")
    void notificationCannotReadPayments() throws IOException {
        assertThat(acls()).filteredOn(acl -> acl.principal().equals("notification"))
                .extracting(Acl::name).allMatch(name -> name.startsWith("orders.placed") || name.startsWith("ecomdemo-notification"));
    }

    @Test
    @DisplayName("the broker's pod syncs the ACLs before it reports Ready, and the ConfigMap is deployed")
    void aclsAreAppliedByThePod() throws IOException {
        Map<String, Object> pod = podSpec(resource("k8s/data/kafka.yaml", "StatefulSet", "kafka"));
        Map<String, Object> acls = container(pod, "acls");

        assertThat(String.join(" ", strings(acls.get("args")))).contains("sh /etc/kafka-acls/sync.sh", "/tmp/acls-synced");
        assertThat(acls.get("readinessProbe").toString()).contains("/tmp/acls-synced");
        assertThat(pod.get("volumes").toString()).contains("name=kafka-acls");
        assertThat(Files.readString(ProjectRoot.resolve("k8s/kustomization.yaml"))).contains("- data/kafka-acls.yaml");
    }

    /** One line of {@code acls.txt}: a principal's operations on one topic or group (a trailing * is a prefix). */
    record Acl(String principal, String type, String name, List<String> operations) {
    }

    /** The lines of {@code acls.txt}, comments removed. */
    @SuppressWarnings("unchecked")
    static List<Acl> acls() throws IOException {
        String table = (String) ((Map<String, Object>) resource(ACLS, "ConfigMap", "kafka-acls").get("data")).get("acls.txt");
        List<Acl> acls = new ArrayList<>();
        for (String line : table.lines().toList()) {
            String[] fields = line.replaceAll("#.*", "").strip().split("\\s+");
            if (fields.length == 4) {
                acls.add(new Acl(fields[0], fields[1], fields[2], List.of(fields[3].split(","))));
            } else {
                assertThat(fields).as("a line of acls.txt: " + line).containsExactly("");
            }
        }
        return acls;
    }

    /** The data of the ConfigMap {@code kafka-acls}: the table and the sync script. */
    @SuppressWarnings("unchecked")
    static Map<String, String> aclConfigMap() throws IOException {
        return (Map<String, String>) resource(ACLS, "ConfigMap", "kafka-acls").get("data");
    }

    /** What Kafka's rule does: the first group of the pattern if it matches the whole DN, else the DN. */
    private static String principal(Pattern subject, String distinguishedName) {
        Matcher matcher = subject.matcher(distinguishedName);
        return matcher.matches() ? matcher.group(1) : distinguishedName;
    }

    /** POSTGRES_USER of a database's StatefulSet. */
    @SuppressWarnings("unchecked")
    static String databaseUser(String database) throws IOException {
        Map<String, Object> postgres = container(podSpec(resource("k8s/data/" + database + ".yaml", "StatefulSet", database)),
                "postgres");
        Map<String, String> env = new HashMap<>();
        for (Map<String, Object> variable : (List<Map<String, Object>>) postgres.get("env")) {
            env.put((String) variable.get("name"), String.valueOf(variable.get("value")));
        }
        return env.get("POSTGRES_USER");
    }

    /** The {@code secret} of the pod's volume whose Secret is {@code <service>-client-tls}. */
    @SuppressWarnings("unchecked")
    private static Map<?, ?> clientCertificateVolume(Map<String, Object> pod, String service) {
        return ((List<Map<String, Object>>) pod.get("volumes")).stream()
                .map(v -> v.get("secret")).filter(secret -> secret instanceof Map<?, ?> s
                        && (service + "-client-tls").equals(s.get("secretName")))
                .map(secret -> (Map<?, ?>) secret).findFirst()
                .orElseThrow(() -> new AssertionError("no volume of the Secret " + service + "-client-tls"));
    }

    /** Where the service's main container mounts the volume whose Secret is {@code <service>-client-tls}. */
    @SuppressWarnings("unchecked")
    private static String clientCertificateMount(String service) throws IOException {
        Map<String, Object> pod = podSpec(resource("k8s/services/" + service + ".yaml", "Deployment", service));
        String volume = ((List<Map<String, Object>>) pod.get("volumes")).stream()
                .filter(v -> v.get("secret") instanceof Map<?, ?> secret
                        && (service + "-client-tls").equals(secret.get("secretName")))
                .map(v -> (String) v.get("name")).findFirst().orElse(null);
        return ((List<Map<String, Object>>) container(pod, service).get("volumeMounts")).stream()
                .filter(mount -> mount.get("name").equals(volume))
                .map(mount -> (String) mount.get("mountPath")).findFirst().orElse(null);
    }
}
