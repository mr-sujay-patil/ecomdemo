package com.ecomdemo.support;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;

/**
 * Redis's ACL as Kubernetes gives it to Redis (Phase 37): the {@code users.acl} of the {@code cache-acl}
 * ConfigMap in {@code k8s/data/cache-acl.yaml}, and a Redis that runs with it.
 *
 * <p>In this repository's own tests per service (catalog's cache, the assistant's memory, the gateway's rate
 * limiter) the question is "can this service's REAL code do its work under its ACL user, and nothing is refused
 * on the way". Those tests run without TLS, and on the cluster a service user can only be reached through a
 * client certificate (it has no password). So {@link #start} gives every enabled user one test password, appended
 * to its line, and changes nothing else: the user's keys and commands are the file's. How a certificate becomes
 * the user is proven separately, with TLS, by {@code RedisAclIT} in ecomdemo-app.
 *
 * <p>In this common test-jar because three services need it, as {@link RedisContainerConfig} is.
 */
public final class RedisAcl {

    /** Where the ACL lives in Git. */
    public static final String MANIFEST = "k8s/data/cache-acl.yaml";

    /** The password every enabled user gets in {@link #start}, for a plain-text test only. */
    public static final String TEST_PASSWORD = "it-acl-password";

    /** The user the Redis pod's own tools log in as (the CN of cache-tls). */
    public static final String ADMIN = "cache-admin";

    private RedisAcl() {
    }

    /** The ACL file, exactly as the ConfigMap mounts it into the Redis pod. */
    @SuppressWarnings("unchecked")
    public static String file() throws IOException {
        try (Reader reader = Files.newBufferedReader(ProjectRoot.resolve(MANIFEST))) {
            for (Object document : new Yaml().loadAll(reader)) {
                Map<String, Object> resource = (Map<String, Object>) document;
                if (resource != null && "ConfigMap".equals(resource.get("kind"))
                        && "cache-acl".equals(((Map<String, Object>) resource.get("metadata")).get("name"))) {
                    return (String) ((Map<String, Object>) resource.get("data")).get("users.acl");
                }
            }
        }
        throw new IllegalStateException("no ConfigMap cache-acl in " + MANIFEST);
    }

    /** The {@code user ...} lines of the file, comments and blank lines left out, spaces collapsed. */
    public static List<String> users() throws IOException {
        return file().lines().map(String::strip).filter(line -> line.startsWith("user "))
                .map(line -> line.replaceAll("\\s+", " ")).toList();
    }

    /** The file with {@link #TEST_PASSWORD} added to every enabled user; nothing else changes. */
    public static String withTestPasswords() throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : file().lines().toList()) {
            String user = line.strip();
            lines.add(user.startsWith("user ") && user.matches(".*\\son(\\s.*)?$") ? line + " >" + TEST_PASSWORD : line);
        }
        return String.join("\n", lines) + "\n";
    }

    /**
     * {@code redis:8-alpine} (the manifest's image) on its plain port, with the ACL file as {@code --aclfile} and a
     * test password on each user (see the class comment). Started; the caller stops it.
     */
    public static GenericContainer<?> start() throws IOException {
        GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
                .withCommand("redis-server", "--save", "", "--appendonly", "no", "--aclfile", "/etc/ecomdemo-acl/users.acl")
                .withCopyToContainer(Transferable.of(withTestPasswords().getBytes(StandardCharsets.UTF_8), 0644),
                        "/etc/ecomdemo-acl/users.acl")
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\s", 1));
        redis.start();
        return redis;
    }

    /** Runs redis-cli in the container as {@code user} (with the test password); stdout and stderr together. */
    public static String cli(GenericContainer<?> redis, String user, String... command) throws Exception {
        List<String> call = new ArrayList<>(List.of("redis-cli", "--no-auth-warning", "--user", user, "--pass", TEST_PASSWORD));
        call.addAll(List.of(command));
        ExecResult result = redis.execInContainer(call.toArray(String[]::new));
        return (result.getStdout() + result.getStderr()).strip();
    }

    /**
     * Redis's record of every command or key it REFUSED since the container started ({@code ACL LOG}), one line
     * per field. Empty means nothing was refused. A test that only watched the client would miss a refusal the
     * code swallows: the cache's error handler logs and carries on, the rate limiter lets the request through.
     */
    public static String refusals(GenericContainer<?> redis) throws Exception {
        return cli(redis, ADMIN, "ACL", "LOG");
    }

    /** Empties {@code ACL LOG}, so a test sees only the refusals of what it runs itself. */
    public static void forgetRefusals(GenericContainer<?> redis) throws Exception {
        if (!"OK".equals(cli(redis, ADMIN, "ACL", "LOG", "RESET"))) {
            throw new IllegalStateException("ACL LOG RESET failed");
        }
    }
}
