package com.ecomdemo.payment;

import com.ecomdemo.jwt.ServiceTokens;
import com.ecomdemo.support.TestJwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecomdemo.jwt.JwtProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Nothing can ask this service to charge anything: only the probes and metrics are reachable. */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Payment security")
class PaymentSecurityTest {

    /** No broker in this test; a void's outbox row is written, never sent. */
    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private MockMvc mvc;


    @Autowired
    private JwtProperties jwtProperties;

    private String serviceToken() {
        return TestJwt.service("ecomdemo-app", ServiceTokens.PAYMENT_SETTLE);
    }

    private String customerToken() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject("shopper")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("roles", List.of("CUSTOMER"))
                .build();
        return TestJwt.sign(claims);
    }

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

    // --- Phase 32: the settlement question, for services only ---------------------------------------

    @Test
    @DisplayName("the settlement endpoint wants a token: none is 401")
    void settlementNeedsAToken() throws Exception {
        mvc.perform(post("/internal/saga/orders/1/settle")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1.00}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("and the payment:settle scope: a shopper's token is 403")
    void settlementRefusesAShopper() throws Exception {
        mvc.perform(post("/internal/saga/orders/1/settle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1.00}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Phase 33: until now any SERVICE token could ask for a settlement, the gateway's included. Only
     * the application holds {@code payment:settle}; every other service's token is refused.
     */
    @Test
    @DisplayName("a service without the payment:settle scope is 403")
    void settlementRefusesAnotherService() throws Exception {
        String gateways = TestJwt.service("gateway-service", ServiceTokens.CATALOG_READ);

        mvc.perform(post("/internal/saga/orders/1/settle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + gateways)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1.00}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a service may settle an order, and an unpaid one comes back VOIDED")
    void aServiceMaySettle() throws Exception {
        mvc.perform(post("/internal/saga/orders/424242/settle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":12.50}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("VOIDED"));
    }

    @Test
    @DisplayName("a settlement without an amount is 400, not a void of nothing")
    void settlementValidatesItsBody() throws Exception {
        mvc.perform(post("/internal/saga/orders/1/settle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
