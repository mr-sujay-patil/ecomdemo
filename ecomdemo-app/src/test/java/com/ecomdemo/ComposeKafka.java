package com.ecomdemo;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * The broker exactly as {@code compose.yaml} defines it: its image, its environment, and where its
 * data volume is mounted (KI-039).
 *
 * <p>Read from the real file rather than copied into a test, so that a test of "does this broker keep
 * its data?" tests the configuration people actually run, and a change to that configuration is a
 * change to what is tested.
 *
 * @param image the broker image, for example {@code apache/kafka:4.2.1}
 * @param environment every {@code environment:} entry, as the container sees it
 * @param dataVolume the named volume meant to hold the broker's data
 * @param dataMount where that volume is mounted inside the container
 */
record ComposeKafka(String image, Map<String, String> environment, String dataVolume, String dataMount) {

    static ComposeKafka load() {
        try (Reader reader = Files.newBufferedReader(ProjectRoot.resolve("compose.yaml"))) {
            return from(new Yaml().load(reader));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ComposeKafka from(Map<String, Object> compose) {
        Map<String, Object> kafka = (Map<String, Object>) ((Map<String, Object>) compose.get("services")).get("kafka");

        Map<String, String> environment = new LinkedHashMap<>();
        ((Map<String, Object>) kafka.get("environment")).forEach((name, value) -> environment.put(name, String.valueOf(value)));

        String volume = "kafka-data";
        String mount = ((List<String>) kafka.get("volumes")).stream()
                .filter(entry -> entry.startsWith(volume + ":"))
                .map(entry -> entry.substring(volume.length() + 1))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "compose.yaml's kafka service no longer mounts the volume '" + volume + "'"));
        return new ComposeKafka((String) kafka.get("image"), environment, volume, mount);
    }
}
