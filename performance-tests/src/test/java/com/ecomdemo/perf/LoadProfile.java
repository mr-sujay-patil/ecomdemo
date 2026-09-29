package com.ecomdemo.perf;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.stressPeakUsers;

import io.gatling.javaapi.core.OpenInjectionStep;
import java.time.Duration;
import java.util.List;

/**
 * The three shapes of load, all OPEN models.
 *
 * <p>Open means sessions ARRIVE at a rate whether or not earlier ones have finished, which is how
 * shoppers behave: a slow page does not stop the next person clicking a link. A closed model
 * ("keep 50 users busy") slows its own arrivals down when the system slows down, and so hides
 * exactly the queueing a load test is for. This is called coordinated omission.
 *
 * <ul>
 *   <li><b>ramp</b>: 1 -> RATE sessions/s over DURATION. The point where latency bends upwards is
 *       the knee - the throughput the system can take before queues build.
 *   <li><b>steady</b>: 30 s warm-up climb, then RATE sessions/s for DURATION. The one to compare
 *       configurations with: same load, one variable changed.
 *   <li><b>spike</b>: baseline RATE for 30 s, then SPIKE_USERS arriving within 10 s (steps run
 *       one after another, so the burst replaces the baseline for those 10 s), then the baseline
 *       for 60 s. Asks whether the system recovers, and how long the tail stays long afterwards.
 * </ul>
 */
enum LoadProfile {
    RAMP,
    STEADY,
    SPIKE;

    static LoadProfile current() {
        return valueOf(PerfConfig.PROFILE.toUpperCase());
    }

    List<OpenInjectionStep> injection(double rate) {
        Duration duration = PerfConfig.DURATION;
        return switch (this) {
            case RAMP -> List.of(rampUsersPerSec(1).to(rate).during(duration));
            case STEADY -> List.of(
                    rampUsersPerSec(1).to(rate).during(Duration.ofSeconds(30)),
                    constantUsersPerSec(rate).during(duration));
            case SPIKE -> List.of(
                    constantUsersPerSec(rate).during(Duration.ofSeconds(30)),
                    // stressPeakUsers spreads the burst along a smooth step (a Heaviside curve)
                    // rather than firing every session in the same millisecond, which would test
                    // the load generator's own socket handling more than the application.
                    stressPeakUsers(PerfConfig.SPIKE_USERS).during(Duration.ofSeconds(10)),
                    constantUsersPerSec(rate).during(Duration.ofSeconds(60)));
        };
    }
}
