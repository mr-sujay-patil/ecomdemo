package com.ecomdemo;

import static com.ecomdemo.PostgresTlsConfigTest.clientSettings;
import static com.ecomdemo.PostgresTlsConfigTest.container;
import static com.ecomdemo.PostgresTlsConfigTest.podSpec;
import static com.ecomdemo.PostgresTlsConfigTest.resource;
import static com.ecomdemo.PostgresTlsConfigTest.spec;
import static com.ecomdemo.PostgresTlsConfigTest.strings;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import com.ecomdemo.support.RedisAcl;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Redis authorizes each service by an ACL user named by its client certificate (Phase 37, KI-065).
 *
 * <p>Before, every client presented its own certificate (Phase 36) and then logged in as {@code default} with the
 * one shared password, so any service could read, write or flush any key. Now Redis logs a connection in as the
 * user named by its certificate's CN, and each user may touch only the keys its service's code uses.
 *
 * <p>The fast half: it reads the manifests, the ACL file and the code's key prefixes. {@code RedisAclIT} runs the
 * real image with this configuration; {@code CatalogCacheAclIT}, {@code AssistantRedisAclIT} and
 * {@code RateLimitAclIT} run each service's real Redis code under its user.
 */
@DisplayName("Redis ACL users per service (Phase 37)")
class RedisAclConfigTest {

    /** Where the ACL file is mounted in the Redis pod. */
    static final String ACL_PATH = "/etc/ecomdemo-acl/users.acl";

    /**
     * The key prefixes each service's code writes, read from the code itself, so a renamed cache or prefix fails
     * here before it fails on the cluster.
     */
    static Map<String, List<String>> keyPrefixesInCode() throws IOException {
        Map<String, List<String>> prefixes = new LinkedHashMap<>();
        // RedisCacheManager's default key prefix is `<cache name>::`.
        String cacheNames = source("catalog-service/src/main/java/com/ecomdemo/cache/CacheNames.java");
        prefixes.put("catalog", List.of(constant(cacheNames, "PRODUCT") + "::", constant(cacheNames, "PRODUCT_LIST") + "::"));
        prefixes.put("assistant", List.of(
                constant(source("assistant-service/src/main/java/com/ecomdemo/assistant/memory/RedisChatMemoryRepository.java"), "PREFIX"),
                constant(source("assistant-service/src/main/java/com/ecomdemo/assistant/actions/PendingActions.java"), "PREFIX")));
        // Spring Cloud Gateway's RedisRateLimiter.getKeys: `request_rate_limiter.{<id>}.tokens` and `.timestamp`.
        prefixes.put("gateway", List.of("request_rate_limiter."));
        return prefixes;
    }

    @Test
    @DisplayName("Redis logs a connection in as the user its certificate's CN names, with the ACL file from Git")
    void redisMapsTheCertificateToAUser() throws IOException {
        String args = String.join(" ", strings(redis().get("args")));

        assertThat(args).contains("--tls-auth-clients yes", "--tls-auth-clients-user CN", "--aclfile " + ACL_PATH);
    }

    @Test
    @DisplayName("Redis no longer has the shared password: no requirepass, no password in the Redis pod")
    void noSharedPassword() throws IOException {
        assertThat(String.join(" ", strings(redis().get("args")))).doesNotContain("requirepass", "REDIS_PASSWORD");
        for (Map<String, Object> container : cacheContainers()) {
            assertThat(String.valueOf(container.get("env"))).as(String.valueOf(container.get("name")))
                    .doesNotContain("REDIS_PASSWORD", "REDISCLI_AUTH", "cache-secrets");
        }
    }

    @Test
    @DisplayName("the ACL file is the cache-acl ConfigMap, mounted read-only in Redis and in the sidecar that reloads it")
    void theAclFileIsMounted() throws IOException {
        Map<String, Object> pod = cachePod();
        String volume = volumeOfConfigMap(pod, "cache-acl");
        for (String name : List.of("redis", "cert-reload")) {
            Map<?, ?> mount = mountOf(container(pod, name), volume);
            assertThat(mount).as(name).isNotNull();
            assertThat(mount.get("mountPath") + "/users.acl").isEqualTo(ACL_PATH);
            assertThat(mount.get("readOnly")).isEqualTo(true);
        }
        String kustomization = Files.readString(ProjectRoot.resolve("k8s/kustomization.yaml"));
        assertThat(kustomization).contains("- data/cache-acl.yaml");
        assertThat(kustomization.indexOf("- data/cache-acl.yaml")).isLessThan(kustomization.indexOf("- data/cache.yaml"));
    }

    @Test
    @DisplayName("the cert-reload sidecar also loads a changed ACL file (ACL LOAD), so an ACL change needs no restart")
    void theSidecarReloadsTheAcl() throws IOException {
        String script = strings(container(cachePod(), "cert-reload").get("args")).getFirst();

        assertThat(script).contains(ACL_PATH, "ACL LOAD");
    }

    @Test
    @DisplayName("the users are exactly: default (off), cache-admin, and one per Redis client named by its certificate's CN")
    void oneUserPerClient() throws IOException {
        Map<String, String> users = users();

        List<String> expected = new ArrayList<>(List.of("default", RedisAcl.ADMIN));
        expected.addAll(RedisClientAuthConfigTest.REDIS_CLIENTS.values());
        assertThat(users.keySet()).containsExactlyInAnyOrderElementsOf(expected);
        for (String service : RedisClientAuthConfigTest.REDIS_CLIENTS.keySet()) {
            Map<String, Object> certificate = spec(resource("k8s/service-certificates.yaml", "Certificate",
                    service + "-client-tls"));
            assertThat(users).containsKey((String) certificate.get("commonName"));
        }
    }

    @Test
    @DisplayName("default is disabled and may do nothing, so a certificate that names no user is refused every command")
    void defaultIsOff() throws IOException {
        List<String> rules = rules("default");

        assertThat(rules).contains("reset", "off").doesNotContain("on", "nopass", "allkeys", "allcommands");
        assertThat(rules).noneMatch(rule -> rule.startsWith("+") || rule.startsWith("~") || rule.startsWith("&")
                || rule.startsWith(">") || rule.startsWith("#"));
    }

    @Test
    @DisplayName("cache-admin is the CN of the Redis pod's own certificate (cache-tls), used by the tools in that pod")
    void adminIsThePodsCertificate() throws IOException {
        Map<String, Object> certificate = spec(resource("k8s/service-certificates.yaml", "Certificate", "cache-tls"));

        assertThat(certificate.get("commonName")).isEqualTo(RedisAcl.ADMIN);
        assertThat(rules(RedisAcl.ADMIN)).contains("on", "+@all");
    }

    @Test
    @DisplayName("no user has a password or nopass: the certificate is the only way in, and AUTH cannot switch users")
    void noPasswords() throws IOException {
        for (Map.Entry<String, String> user : users().entrySet()) {
            List<String> rules = rules(user.getKey());
            assertThat(rules).as(user.getKey()).startsWith("reset").doesNotContain("nopass")
                    .noneMatch(rule -> rule.startsWith(">") || rule.startsWith("#"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"ecomdemo", "catalog", "gateway", "assistant"})
    @DisplayName("a service user is enabled, names its commands one by one, has no channels, and no admin or flush command")
    void serviceUsersAreLeastPrivilege(String user) throws IOException {
        List<String> rules = rules(user);

        assertThat(rules).contains("on", "+hello", "+ping", "+client|setinfo", "+info")
                .doesNotContain("allkeys", "allchannels", "allcommands", "~*", "&*", "+flushall", "+flushdb",
                        "+config", "+acl", "+debug", "+shutdown", "+monitor", "+client", "+function", "+script",
                        "+swapdb", "+move", "+copy", "+rename", "+unlink")
                .noneMatch(rule -> rule.startsWith("+@") || rule.startsWith("&"));
        // Only the gateway uses another database (1, application.yml); the others stay in database 0.
        assertThat(rules.contains("+select")).isEqualTo("gateway".equals(user));
        // KEYS only where Spring Data Redis needs it: a whole-cache clear of the catalogue.
        assertThat(rules.contains("+keys")).isEqualTo("catalog".equals(user));
    }

    @Test
    @DisplayName("every key prefix the code uses is allowed to its own service's user, and to no other service's")
    void keyPatternsFollowTheCode() throws IOException {
        Map<String, List<String>> inCode = keyPrefixesInCode();
        assertThat(inCode.values()).allSatisfy(prefixes -> assertThat(prefixes).allMatch(p -> !p.isBlank()));

        for (String user : RedisClientAuthConfigTest.REDIS_CLIENTS.values()) {
            List<String> patterns = rules(user).stream().filter(rule -> rule.startsWith("~")).map(rule -> rule.substring(1))
                    .toList();
            List<String> own = inCode.getOrDefault(user, List.of());
            // Exactly its own prefixes, each as `<prefix>*`: no wider pattern, nothing the code does not use.
            assertThat(patterns).as(user).containsExactlyInAnyOrderElementsOf(own.stream().map(p -> p + "*").toList());
            for (Map.Entry<String, List<String>> other : inCode.entrySet()) {
                if (!other.getKey().equals(user)) {
                    for (String prefix : other.getValue()) {
                        assertThat(patterns).as(user + " must not match " + other.getKey() + "'s " + prefix)
                                .noneMatch(pattern -> globMatches(pattern, prefix + "x"));
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"app", "catalog-service", "gateway-service", "assistant-service"})
    @DisplayName("a Redis client sends no password and no user name: its certificate names the user")
    void clientsSendNoPassword(String service) throws IOException {
        assertThat(clientSettings(service)).doesNotContainKeys("SPRING_DATA_REDIS_PASSWORD", "SPRING_DATA_REDIS_USERNAME",
                "SPRING_DATA_REDIS_URL");
        Map<String, Object> deployment = podSpec(resource("k8s/services/" + service + ".yaml", "Deployment", service));
        assertThat(String.valueOf(container(deployment, service).get("env"))).doesNotContain("SPRING_DATA_REDIS_PASSWORD");
    }

    @Test
    @DisplayName("k8s-up.sh gives the services no Redis password any more, and takes the old one out of their Secrets")
    void k8sUpRemovesTheSharedPassword() throws IOException {
        String up = Files.readString(ProjectRoot.resolve("scripts/k8s-up.sh"));

        assertThat(up).doesNotContainPattern("ensure_key \\S+ SPRING_DATA_REDIS_PASSWORD")
                .doesNotContainPattern("ensure_key cache-secrets REDIS_PASSWORD");
        for (String secret : List.of("app-secrets", "catalog-service-secrets", "gateway-service-secrets",
                "assistant-service-secrets")) {
            assertThat(up).contains(secret);
        }
        assertThat(up).containsPattern("remove_key \"\\$svc_secret\" SPRING_DATA_REDIS_PASSWORD");
    }

    @Test
    @DisplayName("the smoke test checks each client's user on the cluster: its own key, another service's, FLUSHALL, an unknown CN")
    void smokeTestChecksTheUsers() throws IOException {
        String smoke = Files.readString(ProjectRoot.resolve("scripts/smoke-test.sh"));

        assertThat(smoke).contains("Phase 37", "is refused another service's key (NOPERM)", "is refused FLUSHALL",
                "a cluster certificate whose CN is not a Redis user gets NOAUTH");
    }

    /** Each user line as {@code name -> the rest}, from the file the ConfigMap mounts. */
    static Map<String, String> users() throws IOException {
        Map<String, String> users = new LinkedHashMap<>();
        for (String line : RedisAcl.users()) {
            String[] parts = line.split(" ", 3);
            assertThat(users.put(parts[1], parts.length > 2 ? parts[2] : "")).as("user " + parts[1] + " twice").isNull();
        }
        return users;
    }

    static List<String> rules(String user) throws IOException {
        String rules = users().get(user);
        assertThat(rules).as("user " + user).isNotNull();
        return Arrays.asList(rules.split(" "));
    }

    /** Redis's glob for the patterns used here: {@code *} only. */
    private static boolean globMatches(String pattern, String key) {
        return key.matches(Pattern.quote(pattern).replace("*", "\\E.*\\Q"));
    }

    private static String source(String path) throws IOException {
        return Files.readString(ProjectRoot.resolve(path));
    }

    private static String constant(String source, String name) {
        Matcher matcher = Pattern.compile("static final String " + name + " = \"([^\"]*)\";").matcher(source);
        assertThat(matcher.find()).as(name).isTrue();
        return matcher.group(1);
    }

    private static Map<String, Object> redis() throws IOException {
        return container(cachePod(), "redis");
    }

    static Map<String, Object> cachePod() throws IOException {
        return podSpec(resource(RedisClientAuthConfigTest.CACHE, "Deployment", "cache"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cacheContainers() throws IOException {
        return (List<Map<String, Object>>) cachePod().get("containers");
    }

    @SuppressWarnings("unchecked")
    static String volumeOfConfigMap(Map<String, Object> pod, String configMap) {
        return ((List<Map<String, Object>>) pod.get("volumes")).stream()
                .filter(v -> v.get("configMap") instanceof Map<?, ?> c && configMap.equals(c.get("name")))
                .map(v -> (String) v.get("name")).findFirst()
                .orElseThrow(() -> new AssertionError("no volume of the ConfigMap " + configMap));
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> mountOf(Map<String, Object> container, String volume) {
        return ((List<Map<String, Object>>) container.getOrDefault("volumeMounts", List.of())).stream()
                .filter(mount -> volume.equals(mount.get("name"))).findFirst().orElse(null);
    }
}
