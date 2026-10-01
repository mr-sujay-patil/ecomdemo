package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The broker writes its data where its volume is mounted (KI-039), in compose and in Kubernetes.
 *
 * <p>compose mounted {@code kafka-data} at {@code /var/lib/kafka/data} and never told the broker to
 * write there. The image's default log directory is under {@code /tmp}, INSIDE the container, so every
 * {@code docker compose down} deleted the topics, the consumer groups' offsets and the messages while
 * the PostgreSQL volumes survived. Nothing failed and nothing logged: the broker simply started empty
 * every time. The Kubernetes manifest had set {@code KAFKA_LOG_DIRS} all along, which is why this was
 * a compose-only defect, and why the two are compared here.
 *
 * <p>This is the fast half. {@code ComposeKafkaPersistenceIT} is the proof that the broker honours the
 * setting: it recreates the container on the same volume and reads the messages back.
 */
@DisplayName("Kafka's data directory is its volume (KI-039)")
class KafkaStorageConfigTest {

    private static final String WHY = "Without KAFKA_LOG_DIRS the broker writes to the image's default under /tmp, inside "
            + "the container, and every `docker compose down` deletes its topics, offsets and messages";

    @Test
    @DisplayName("compose: KAFKA_LOG_DIRS is the directory the kafka-data volume is mounted at")
    void composeBrokerWritesToItsVolume() {
        ComposeKafka kafka = ComposeKafka.load();

        assertThat(kafka.environment()).as(WHY).containsEntry("KAFKA_LOG_DIRS", kafka.dataMount());
    }

    @Test
    @DisplayName("Kubernetes: KAFKA_LOG_DIRS is the directory the data volume claim is mounted at")
    void kubernetesBrokerWritesToItsVolume() throws IOException {
        Map<String, Object> container = kubernetesKafkaContainer();

        assertThat(kubernetesLogDirs(container)).as(WHY).isEqualTo(kubernetesDataMount(container));
    }

    @Test
    @DisplayName("compose and Kubernetes keep the broker's data in the same place")
    void bothDeploymentsAgree() throws IOException {
        assertThat(ComposeKafka.load().environment().get("KAFKA_LOG_DIRS"))
                .isEqualTo(kubernetesLogDirs(kubernetesKafkaContainer()));
    }

    /** The {@code kafka} container of the StatefulSet in {@code k8s/data/kafka.yaml} (a multi-document file). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> kubernetesKafkaContainer() throws IOException {
        try (Reader reader = Files.newBufferedReader(ProjectRoot.resolve("k8s/data/kafka.yaml"))) {
            for (Object document : new Yaml().loadAll(reader)) {
                Map<String, Object> resource = (Map<String, Object>) document;
                if (resource != null && "StatefulSet".equals(resource.get("kind"))) {
                    Map<String, Object> spec = (Map<String, Object>) ((Map<String, Object>) resource.get("spec")).get("template");
                    List<Map<String, Object>> containers = (List<Map<String, Object>>)
                            ((Map<String, Object>) spec.get("spec")).get("containers");
                    return containers.stream().filter(c -> "kafka".equals(c.get("name"))).findFirst().orElseThrow();
                }
            }
        }
        throw new IllegalStateException("no StatefulSet in k8s/data/kafka.yaml");
    }

    @SuppressWarnings("unchecked")
    private static String kubernetesLogDirs(Map<String, Object> container) {
        return ((List<Map<String, Object>>) container.get("env")).stream()
                .filter(variable -> "KAFKA_LOG_DIRS".equals(variable.get("name")))
                .map(variable -> (String) variable.get("value"))
                .findFirst()
                .orElse(null);
    }

    /** The mount of the claim named {@code data}, which {@code volumeClaimTemplates} provisions. */
    @SuppressWarnings("unchecked")
    private static String kubernetesDataMount(Map<String, Object> container) {
        return ((List<Map<String, Object>>) container.get("volumeMounts")).stream()
                .filter(mount -> "data".equals(mount.get("name")))
                .map(mount -> (String) mount.get("mountPath"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the kafka container mounts no volume named 'data'"));
    }
}
