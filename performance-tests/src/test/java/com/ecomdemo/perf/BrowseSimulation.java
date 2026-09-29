package com.ecomdemo.perf;

import io.gatling.javaapi.core.Simulation;

/**
 * Read-only load: list the catalogue, look at three products. The journey the cache exists for,
 * so it is the one the cache on/off comparison runs.
 *
 * <p>{@code PERF_PROFILE=ramp|steady|spike PERF_RATE=<sessions/s>}; see {@link PerfConfig}.
 */
public class BrowseSimulation extends Simulation {

    {
        Scenarios scenarios = new Scenarios();
        setUp(scenarios.browse().injectOpen(LoadProfile.current().injection(PerfConfig.RATE)))
                .protocols(scenarios.protocol())
                .assertions(Scenarios.assertions());
    }
}
