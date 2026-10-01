package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.DockerImageName;

/**
 * What a user saw of KI-039: messages and consumer offsets that vanish when the broker container is
 * recreated, which {@code docker compose down} followed by {@code up} does.
 *
 * <p>The broker is started from {@code compose.yaml}'s own definition ({@link ComposeKafka}: its image,
 * its environment, and where its data volume is mounted), with a real Docker named volume, exactly as
 * compose does. It then gets a topic, three messages and a consumer group's committed offset; the
 * container is stopped AND REMOVED; and a new container is started on the same volume. If the broker
 * was configured to write to the volume, everything is still there. If it wrote to the container's own
 * filesystem, as compose's configuration did until KI-039, the second broker starts empty.
 *
 * <p>Everything is driven by the command-line tools inside the container (the same ones compose's
 * healthcheck and the smoke test use), so no port is published and no listener address has to be
 * reachable from the host. The hostname {@code kafka} is what the environment's advertised listener
 * and controller quorum point at, so the container is given it.
 */
@DisplayName("Kafka keeps its data when the container is recreated (KI-039)")
class ComposeKafkaPersistenceIT {

    private static final String TOPIC = "persistence-probe";
    private static final String GROUP = "persistence-probe-group";
    private static final String BIN = "/opt/kafka/bin/";
    private static final String BOOTSTRAP = "--bootstrap-server localhost:9092";

    /** A volume of this run's own, so a test never reads another run's data and nothing is shared. */
    private static final String VOLUME = "ecomdemo-kafka-persistence-" + UUID.randomUUID().toString().substring(0, 8);

    private final ComposeKafka compose = ComposeKafka.load();

    @AfterAll
    static void removeTheVolume() {
        try {
            DockerClientFactory.lazyClient().removeVolumeCmd(VOLUME).exec();
        } catch (RuntimeException alreadyGone) {
            // Nothing to clean up if the volume was never created.
        }
    }

    @Test
    @DisplayName("topics, messages and a consumer group's offset survive the container being removed")
    void dataSurvivesRecreatingTheContainer() throws Exception {
        GenericContainer<?> first = broker();
        first.start();
        try {
            awaitReady(first);
            run(first, BIN + "kafka-topics.sh " + BOOTSTRAP + " --create --topic " + TOPIC
                    + " --partitions 1 --replication-factor 1");
            run(first, "printf 'one\\ntwo\\nthree\\n' | " + BIN + "kafka-console-producer.sh " + BOOTSTRAP
                    + " --topic " + TOPIC);
            // A group reads two of the three and commits its position: offsets are the thing that
            // went missing, and the thing that made KI-040 reachable.
            run(first, BIN + "kafka-console-consumer.sh " + BOOTSTRAP + " --topic " + TOPIC + " --group " + GROUP
                    + " --from-beginning --max-messages 2 --timeout-ms 30000");
        } finally {
            // Stops AND removes the container: its writable layer is gone, as after `docker compose down`.
            // Only what was written to the named volume remains.
            first.stop();
        }

        GenericContainer<?> second = broker();
        second.start();
        try {
            awaitReady(second);

            assertThat(run(second, BIN + "kafka-topics.sh " + BOOTSTRAP + " --list").getStdout())
                    .as("the topic was created by the first broker; if it is gone, that broker wrote to its own "
                            + "filesystem and not to the volume mounted at %s", compose.dataMount())
                    .contains(TOPIC);

            assertThat(run(second, BIN + "kafka-console-consumer.sh " + BOOTSTRAP + " --topic " + TOPIC
                            + " --from-beginning --max-messages 3 --timeout-ms 30000").getStdout().lines().toList())
                    .as("the three messages written before the container was removed")
                    .containsExactly("one", "two", "three");

            // The group's committed position: CURRENT-OFFSET 2 of LOG-END-OFFSET 3, so a lag of 1.
            String described = run(second, BIN + "kafka-consumer-groups.sh " + BOOTSTRAP + " --describe --group "
                    + GROUP).getStdout();
            String row = described.lines().filter(line -> line.contains(TOPIC)).findFirst().orElse("");
            assertThat(Arrays.asList(row.trim().split("\\s+")))
                    .as("the consumer group's row for %s in:%n%s", TOPIC, described)
                    .containsSubsequence("0", "2", "3", "1");
        } finally {
            second.stop();
        }
    }

    /** A broker as compose starts it: its image, its environment, a named volume at its data mount. */
    private GenericContainer<?> broker() {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(compose.image()));
        compose.environment().forEach(container::withEnv);
        container.withEnv("KAFKA_HEAP_OPTS", "-Xmx256m -Xms256m");
        // A real Docker NAMED volume, as compose creates, not a host path. withFileSystemBind() would
        // treat the name as a relative host path, make a root-owned directory of it next to the code,
        // and the broker (which runs as a non-root user) could not write there. A Bind whose source is
        // a bare name is a named volume: Docker creates it and gives it the image's ownership of the
        // mount point, so the broker can write to it.
        container.setBinds(List.of(new Bind(VOLUME, new Volume(compose.dataMount()))));
        // The environment's advertised listener and controller quorum say "kafka": resolve it to itself.
        container.withCreateContainerCmdModifier(command -> command.withHostName("kafka"));
        return container;
    }

    /** Polls what compose's own healthcheck polls, until the broker is serving requests. */
    private static void awaitReady(GenericContainer<?> container) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            if (container.execInContainer("sh", "-c", BIN + "kafka-cluster.sh cluster-id " + BOOTSTRAP).getExitCode() == 0) {
                return;
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("the broker did not become ready in 120 s:\n" + container.getLogs());
    }

    private static ExecResult run(GenericContainer<?> container, String command) throws IOException, InterruptedException {
        ExecResult result = container.execInContainer("sh", "-c", command);
        assertThat(result.getExitCode())
                .as("`%s` exited with %d:%n%s", command, result.getExitCode(), result.getStderr())
                .isZero();
        return result;
    }
}
