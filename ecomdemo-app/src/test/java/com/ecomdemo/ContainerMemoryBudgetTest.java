package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every JVM container leaves enough memory OUTSIDE its heap.
 *
 * <p>A container's memory is split two ways: the heap, which {@code -XX:MaxRAMPercentage} sizes as a
 * share of the limit, and everything else a JVM needs — metaspace, the code cache, thread stacks,
 * direct buffers — which is a roughly FIXED cost, measured at 107-179 MiB per service on the WSL2
 * workstation. If the share leaves less than that, the heap can grow into memory the JVM also needs
 * for itself, and the kernel kills the container before Java can report an {@code OutOfMemoryError}.
 * Until the pre-Phase-23 hardening that was the case for four services: 75% of 320 MiB left 80.
 *
 * <p>Two numbers in two files, with nothing to keep them consistent — which is how they drifted. This
 * reads the same bytes Docker reads: the share from the Dockerfile and the compose override, each
 * service's default limit from {@code compose.yaml}.
 */
@DisplayName("container memory budget")
class ContainerMemoryBudgetTest {

    /** Measured non-heap peak was 179 MiB; the floor leaves room for tracing agents and growth. */
    private static final long MIN_NON_HEAP_MIB = 256;

    private static final Pattern SHARE = Pattern.compile("MaxRAMPercentage=(\\d+(?:\\.\\d+)?)");
    private static final Pattern LIMIT = Pattern.compile("memory: \\$\\{(\\w+)_MEMORY_LIMIT:-(\\d+)([MG])}");

    @Test
    @DisplayName("limit x (1 - heap share) leaves at least 256 MiB outside the heap, for every service")
    void everyServiceHasRoomOutsideItsHeap() throws IOException {
        double share = heapShare();
        Map<String, Long> limits = limitsInMib();

        // Seven JVM services since Phase 24 (payment-service). Fewer means the pattern stopped
        // matching, not that a service is fine.
        assertThat(limits).as("memory limits found in compose.yaml").hasSize(7);
        limits.forEach((service, limitMib) -> assertThat(limitMib * (1 - share / 100))
                .as("%s: %d MiB limit at a %.0f%% heap share leaves this much for non-heap", service, limitMib, share)
                .isGreaterThanOrEqualTo(MIN_NON_HEAP_MIB));
    }

    @Test
    @DisplayName("the Dockerfile and the application's compose override agree on the heap share")
    void oneHeapShare() throws IOException {
        // compose.yaml repeats JAVA_OPTS for the app as an overridable default. Two copies of one
        // number is how 75% survived in one place after being changed in the other.
        Matcher compose = SHARE.matcher(Files.readString(ProjectRoot.resolve("compose.yaml")));
        assertThat(compose.find()).isTrue();
        assertThat(Double.parseDouble(compose.group(1))).isEqualTo(heapShare());
    }

    private static double heapShare() throws IOException {
        Matcher dockerfile = SHARE.matcher(Files.readString(ProjectRoot.resolve("Dockerfile")));
        assertThat(dockerfile.find()).as("Dockerfile sets MaxRAMPercentage").isTrue();
        return Double.parseDouble(dockerfile.group(1));
    }

    private static Map<String, Long> limitsInMib() throws IOException {
        Matcher matcher = LIMIT.matcher(Files.readString(ProjectRoot.resolve("compose.yaml")));
        Map<String, Long> limits = new LinkedHashMap<>();
        while (matcher.find()) {
            long value = Long.parseLong(matcher.group(2));
            limits.put(matcher.group(1), "G".equals(matcher.group(3)) ? value * 1024 : value);
        }
        return limits;
    }
}
