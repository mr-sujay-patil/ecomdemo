package com.ecomdemo.perf;

import io.gatling.javaapi.core.Simulation;
import java.util.ArrayList;

/**
 * Write load: cart, checkout, then wait for the saga to settle. Every session touches
 * ecomdemo-app, inventory-service, payment-service, catalog-service and Kafka, and writes to
 * three databases - the journey where a connection pool runs out first.
 */
public class CheckoutSimulation extends Simulation {

    {
        Scenarios scenarios = new Scenarios();
        var assertions = new ArrayList<>(Scenarios.assertions());
        assertions.add(Scenarios.checkoutAssertion());
        setUp(scenarios.checkout().injectOpen(LoadProfile.current().injection()))
                .protocols(scenarios.protocol())
                .assertions(assertions);
    }

    @Override
    public void after() {
        System.out.println(SettleTimes.summary());
    }
}
