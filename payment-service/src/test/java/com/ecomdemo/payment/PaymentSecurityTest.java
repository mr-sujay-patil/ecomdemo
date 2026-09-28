package com.ecomdemo.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Nothing can ask this service to charge anything: only the probes and metrics are reachable. */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Payment security")
class PaymentSecurityTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("leaves the health probe open")
    void healthIsOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("refuses everything else, including anything that looks like a payment API")
    void everythingElseIsRefused() throws Exception {
        mvc.perform(post("/api/payments")).andExpect(status().isForbidden());
        mvc.perform(get("/api/payments/1")).andExpect(status().isForbidden());
    }
}
