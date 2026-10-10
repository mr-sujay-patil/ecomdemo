package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * The databases' TLS settings work on the real images, with the clients' real settings (KI-058).
 *
 * <p>Each database image the manifests use is started with its StatefulSet's own start command (the
 * {@code postgres} container's {@code args}) and the {@code pg_hba.conf} from {@code postgres-hba},
 * with a certificate in {@code /etc/ecomdemo-tls} where cert-manager's Secret is mounted. The client
 * side is {@code app}'s ConfigMap, bound the way Spring Boot binds environment variables, so a
 * misspelt variable name fails here too. Then:
 * <ul>
 *   <li>{@code sslmode=verify-full} with the CA connects, over TLS;</li>
 *   <li>a plain-text client is refused by {@code pg_hba.conf};</li>
 *   <li>a host name the certificate does not carry is refused by the client;</li>
 *   <li>the {@code cert-reload} sidecar's script puts a renewed certificate in service without a restart.</li>
 * </ul>
 */
@DisplayName("PostgreSQL TLS on the real images (KI-058)")
class PostgresTlsIT {

    private static final String SSLMODE = "SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SSLMODE";
    private static final String SSLROOTCERT = "SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SSLROOTCERT";

    /** Issues the test certificates: a container of an image that has openssl, kept for the whole class. */
    private static GenericContainer<?> issuer;

    @TempDir
    static Path files;

    /** The databases with different images: `db` (postgres:18-alpine, like four others) and catalog-db (pgvector). */
    static Stream<String> databases() {
        return Stream.of("db", "catalog-db");
    }

    @BeforeAll
    static void startTheIssuer() throws Exception {
        issuer = new GenericContainer<>("pgvector/pgvector:0.8.6-pg18-trixie").withCommand("sleep", "infinity");
        issuer.start();
        issue("ca", null, "test-ca");
        issue("other-ca", null, "other-ca");
    }

    @AfterAll
    static void stopTheIssuer() {
        if (issuer != null) {
            issuer.stop();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    @DisplayName("verify-full connects over TLS; plain text, and a name the certificate lacks, are refused")
    void tlsOnlyAndVerified(String database) throws Exception {
        issue(database + "-1", "ca", "localhost");
        try (GenericContainer<?> postgres = startDatabase(database, database + "-1", "ca")) {
            String url = "jdbc:postgresql://localhost:" + postgres.getMappedPort(5432) + "/" + login(database).get("POSTGRES_DB");

            try (Connection connection = DriverManager.getConnection(url, clientProperties(database, "ca"))) {
                assertThat(sslInUse(connection)).isTrue();
            }

            Properties plain = clientProperties(database, "ca");
            plain.setProperty("sslmode", "disable");
            assertThatThrownBy(() -> DriverManager.getConnection(url, plain).close())
                    .isInstanceOf(SQLException.class).hasMessageContaining("no encryption");

            String byAddress = url.replace("//localhost:", "//127.0.0.1:");
            assertThatThrownBy(() -> DriverManager.getConnection(byAddress, clientProperties(database, "ca")).close())
                    .isInstanceOf(SQLException.class);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    @DisplayName("the cert-reload script puts a renewed certificate in service without a restart")
    void renewalIsReloaded(String database) throws Exception {
        issue(database + "-old", "ca", "localhost");
        // The renewal comes from ANOTHER CA, so a client that trusts only that CA tells the two apart.
        issue(database + "-new", "other-ca", "localhost");
        try (GenericContainer<?> postgres = startDatabase(database, database + "-old", "ca")) {
            String url = "jdbc:postgresql://localhost:" + postgres.getMappedPort(5432) + "/" + login(database).get("POSTGRES_DB");
            assertThatThrownBy(() -> DriverManager.getConnection(url, clientProperties(database, "other-ca")).close())
                    .isInstanceOf(SQLException.class);

            // The sidecar's own script, in the same container (in the pod they share these directories and
            // the socket), checking every second instead of every 30.
            String reload = String.join("\n", PostgresTlsConfigTest.strings(PostgresTlsConfigTest.container(
                    pod(database), "cert-reload").get("args")));
            postgres.execInContainer("sh", "-c", "RELOAD_INTERVAL=1 nohup sh -c \"$1\" >/tmp/reload.log 2>&1 &",
                    "reloader", reload);
            postgres.copyFileToContainer(Transferable.of(Files.readAllBytes(files.resolve(database + "-new.crt")), 0644),
                    "/etc/ecomdemo-tls/tls.crt");
            postgres.copyFileToContainer(Transferable.of(Files.readAllBytes(files.resolve(database + "-new.key")), 0644),
                    "/etc/ecomdemo-tls/tls.key");

            // Until the reload, the server still presents the old certificate and the handshake fails.
            await().atMost(Duration.ofSeconds(30)).ignoreExceptionsInstanceOf(SQLException.class).untilAsserted(() -> {
                try (Connection connection = DriverManager.getConnection(url, clientProperties(database, "other-ca"))) {
                    assertThat(sslInUse(connection)).isTrue();
                }
            });
            assertThat(postgres.execInContainer("cat", "/tmp/reload.log").getStdout())
                    .contains("reloaded the PostgreSQL certificate");
            assertThat(postgres.isRunning()).isTrue();
        }
    }

    /** The database container, started as its StatefulSet starts it, with the leaf certificate where the Secret is. */
    private static GenericContainer<?> startDatabase(String database, String leaf, String ca) throws IOException {
        Map<String, Object> container = PostgresTlsConfigTest.container(pod(database), "postgres");
        List<String> command = PostgresTlsConfigTest.strings(container.get("command"));
        List<String> args = PostgresTlsConfigTest.strings(container.get("args"));
        String hba = String.join("\n", PostgresTlsConfigTest.hbaRules()) + "\n";

        GenericContainer<?> postgres = new GenericContainer<>((String) container.get("image"))
                // The manifest's database and user: the cert-reload script connects as them.
                .withEnv(login(database)).withEnv("POSTGRES_PASSWORD", "test")
                .withExposedPorts(5432)
                // The pod's emptyDir volumes.
                .withTmpFs(Map.of("/etc/postgres-tls", "rw"))
                .withCopyToContainer(Transferable.of(Files.readAllBytes(files.resolve(leaf + ".crt")), 0644),
                        "/etc/ecomdemo-tls/tls.crt")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(files.resolve(leaf + ".key")), 0644),
                        "/etc/ecomdemo-tls/tls.key")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(files.resolve(ca + ".crt")), 0644),
                        "/etc/ecomdemo-tls/ca.crt")
                .withCopyToContainer(Transferable.of(hba, 0644), "/etc/postgresql-hba/pg_hba.conf")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(command.toArray(String[]::new))
                        .withCmd(args.toArray(String[]::new)))
                // The image starts a temporary server to initialise the database, then the real one.
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        postgres.start();
        return postgres;
    }

    /**
     * The driver properties of {@code app}'s ConfigMap, bound as Spring Boot binds the environment (Boot maps
     * {@code SPRING_DATASOURCE_...} names only in a source named {@code systemEnvironment}), plus the login. The CA path is the pod's; here it points at the test's copy of the given CA.
     */
    private static Properties clientProperties(String database, String ca) throws IOException {
        Map<String, Object> environment = new HashMap<>();
        PostgresTlsConfigTest.clientSettings("app").forEach((name, value) -> environment.put(name, String.valueOf(value)));
        HikariConfig hikari = new HikariConfig();
        new Binder(ConfigurationPropertySources.from(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment)))
                .bind("spring.datasource.hikari", Bindable.ofInstance(hikari));

        Properties properties = new Properties();
        properties.putAll(hikari.getDataSourceProperties());
        assertThat(properties).containsEntry("sslmode", environment.get(SSLMODE))
                .containsEntry("sslrootcert", environment.get(SSLROOTCERT));
        properties.setProperty("sslrootcert", files.resolve(ca + ".crt").toString());
        properties.setProperty("user", login(database).get("POSTGRES_USER"));
        properties.setProperty("password", "test");
        return properties;
    }

    /** POSTGRES_DB and POSTGRES_USER, as the StatefulSet's postgres container sets them. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> login(String database) throws IOException {
        Map<String, String> login = new HashMap<>();
        for (Map<String, Object> variable : (List<Map<String, Object>>) PostgresTlsConfigTest.container(pod(database),
                "postgres").get("env")) {
            if (variable.get("value") instanceof String value) {
                login.put((String) variable.get("name"), value);
            }
        }
        return login;
    }

    private static boolean sslInUse(Connection connection) throws SQLException {
        try (ResultSet result = connection.createStatement()
                .executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
            return result.next() && result.getBoolean(1);
        }
    }

    private static Map<String, Object> pod(String database) throws IOException {
        return PostgresTlsConfigTest.podSpec(
                PostgresTlsConfigTest.resource("k8s/data/" + database + ".yaml", "StatefulSet", database));
    }

    /**
     * Writes {@code <name>.crt} and {@code <name>.key} to {@link #files}: a self-signed CA when {@code ca} is
     * null, else a leaf for {@code subject} signed by {@code ca}.
     */
    private static void issue(String name, String ca, String subject) throws Exception {
        String key = "openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out /tmp/" + name + ".key";
        String certificate = ca == null
                ? "openssl req -x509 -key /tmp/" + name + ".key -subj /CN=" + subject + " -days 1 -out /tmp/" + name + ".crt"
                : "openssl req -new -key /tmp/" + name + ".key -subj /CN=" + subject + " -out /tmp/" + name + ".csr"
                        + " && printf 'subjectAltName=DNS:" + subject + "\\n' > /tmp/" + name + ".ext"
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
