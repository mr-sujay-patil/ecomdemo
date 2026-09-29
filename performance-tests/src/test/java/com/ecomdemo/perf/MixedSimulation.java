package com.ecomdemo.perf;

import io.gatling.javaapi.core.Simulation;
import java.util.ArrayList;

/**
 * Both journeys at once, in a shop-like ratio: most visitors browse, a few buy.
 * {@code PERF_CHECKOUT_SHARE} (default 0.2) of the sessions - baseline and spike alike - check out.
 *
 * <p>The mix is where the two interfere. Every checkout changes stock, a stock change evicts
 * cache entries, and browsers then pay for cache misses they did nothing to cause - something
 * neither simulation on its own can show.
 */
public class MixedSimulation extends Simulation {

    private static final double CHECKOUT_SHARE = Double.parseDouble(
            System.getenv().getOrDefault("PERF_CHECKOUT_SHARE", "0.2"));

    {
        Scenarios scenarios = new Scenarios();
        LoadProfile profile = LoadProfile.current();
        var assertions = new ArrayList<>(Scenarios.assertions());
        assertions.add(Scenarios.checkoutAssertion());
        setUp(
                        scenarios.browse().injectOpen(
                                profile.injection(1 - CHECKOUT_SHARE)),
                        scenarios.checkout().injectOpen(
                                profile.injection(CHECKOUT_SHARE)))
                .protocols(scenarios.protocol())
                .assertions(assertions);
    }

    @Override
    public void after() {
        System.out.println(SettleTimes.summary());
    }
}
