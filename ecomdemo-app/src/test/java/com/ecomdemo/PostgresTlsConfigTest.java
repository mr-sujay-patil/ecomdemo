package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

/**
 * The six PostgreSQL databases are reached over verified TLS only, in Kubernetes (KI-058).
 *
 * <p>Before, no database had a certificate and no client set {@code sslmode}, so every query, and the
 * password exchange, crossed the cluster network in clear text. Each database now has a cert-manager
 * certificate, starts with {@code ssl=on} and an {@code hba_file} that accepts TCP only as
 * {@code hostssl}, and each client sets {@code sslmode=verify-full} with the cluster CA.
 *
 * <p>This is the fast half: it reads the manifests. {@code PostgresTlsIT} runs the real images with the
 * same command and {@code pg_hba.conf}, and connects through JDBC with the clients' settings.
 */
@DisplayName("PostgreSQL is TLS-only in Kubernetes (KI-058)")
class PostgresTlsConfigTest {

    static final String HBA = "k8s/data/postgres-hba.yaml";

    @ParameterizedTest(name = "{0}")
    @CsvSource({"db", "catalog-db", "customer-db", "inventory-db", "notification-db", "payment-db"})
    @DisplayName("each database has a certificate that names its Service")
    void eachDatabaseHasACertificate(String database) throws IOException {
        Map<String, Object> spec = spec(resource("k8s/service-certificates.yaml", "Certificate", database + "-tls"));

        assertThat(spec.get("secretName")).isEqualTo(database + "-tls");
        assertThat(strings(spec.get("dnsNames")))
                .contains(database, database + ".ecomdemo.svc.cluster.local");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"db", "catalog-db", "customer-db", "inventory-db", "notification-db", "payment-db"})
    @DisplayName("each database starts with ssl=on, its certificate and the TLS-only pg_hba.conf, and reloads a renewal")
    void eachDatabaseServesTlsOnly(String database) throws IOException {
        Map<String, Object> pod = podSpec(resource("k8s/data/" + database + ".yaml", "StatefulSet", database));
        String start = String.join(" ", strings(container(pod, "postgres").get("args")));

        assertThat(start).contains("-c ssl=on", "-c ssl_cert_file=/etc/postgres-tls/tls.crt",
                "-c ssl_key_file=/etc/postgres-tls/tls.key", "-c hba_file=/etc/postgresql-hba/pg_hba.conf");
        assertThat(volumeSources(pod)).contains("secret:" + database + "-tls", "configMap:postgres-hba");
        assertThat(container(pod, "cert-reload").get("args").toString()).contains("pg_reload_conf()");
    }

    @Test
    @DisplayName("pg_hba.conf accepts TCP connections only over TLS")
    void hbaAcceptsOnlyTls() throws IOException {
        List<String> rules = hbaRules();

        // Phase 35 appends `clientcert=verify-full` to this line (ClientAuthConfigTest); TLS-only is what this checks.
        assertThat(rules).anyMatch(rule -> rule.startsWith("hostssl all all all scram-sha-256"));
        assertThat(rules).allSatisfy(rule -> assertThat(rule).matches("(local|hostssl) .*"));
    }

    @Test
    @DisplayName("the pg_hba.conf ConfigMap is deployed")
    void hbaIsInTheKustomization() throws IOException {
        assertThat(Files.readString(ProjectRoot.resolve("k8s/kustomization.yaml"))).contains("- data/postgres-hba.yaml");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"app", "catalog-service", "customer-service", "inventory-service", "notification-service",
            "payment-service"})
    @DisplayName("each client verifies the database's certificate and name against the cluster CA")
    void eachClientVerifiesTheDatabase(String service) throws IOException {
        Map<String, Object> data = clientSettings(service);

        assertThat(data).containsEntry("SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SSLMODE", "verify-full")
                .containsEntry("SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SSLROOTCERT", "/etc/ecomdemo-tls/ca.crt");
    }

    /** The data of {@code <service>-config}, the ConfigMap in {@code k8s/services/<service>.yaml}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> clientSettings(String service) throws IOException {
        return (Map<String, Object>) resource("k8s/services/" + service + ".yaml", "ConfigMap", service + "-config")
                .get("data");
    }

    /** The rules of {@code pg_hba.conf} in {@link #HBA}, without comments and blank lines, spaces collapsed. */
    @SuppressWarnings("unchecked")
    static List<String> hbaRules() throws IOException {
        String file = (String) ((Map<String, Object>) resource(HBA, "ConfigMap", "postgres-hba").get("data"))
                .get("pg_hba.conf");
        return file.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.replaceAll("\\s+", " ")).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resource(String path, String kind, String name) throws IOException {
        try (Reader reader = Files.newBufferedReader(ProjectRoot.resolve(path))) {
            for (Object document : new Yaml().loadAll(reader)) {
                Map<String, Object> resource = (Map<String, Object>) document;
                if (resource != null && kind.equals(resource.get("kind"))
                        && name.equals(((Map<String, Object>) resource.get("metadata")).get("name"))) {
                    return resource;
                }
            }
        }
        throw new IllegalStateException("no " + kind + " named " + name + " in " + path);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> spec(Map<String, Object> resource) {
        return (Map<String, Object>) resource.get("spec");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> podSpec(Map<String, Object> statefulSet) {
        return (Map<String, Object>) ((Map<String, Object>) spec(statefulSet).get("template")).get("spec");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> container(Map<String, Object> podSpec, String name) {
        return ((List<Map<String, Object>>) podSpec.get("containers")).stream()
                .filter(container -> name.equals(container.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("no container named " + name));
    }

    /** Each volume as {@code secret:<name>}, {@code configMap:<name>} or {@code emptyDir}. */
    @SuppressWarnings("unchecked")
    private static List<String> volumeSources(Map<String, Object> podSpec) {
        List<String> sources = new ArrayList<>();
        for (Map<String, Object> volume : (List<Map<String, Object>>) podSpec.getOrDefault("volumes", List.of())) {
            if (volume.get("secret") instanceof Map<?, ?> secret) {
                sources.add("secret:" + secret.get("secretName"));
            } else if (volume.get("configMap") instanceof Map<?, ?> configMap) {
                sources.add("configMap:" + configMap.get("name"));
            } else {
                sources.add("emptyDir");
            }
        }
        return sources;
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object list) {
        return list == null ? List.of() : (List<String>) list;
    }
}
