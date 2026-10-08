package com.ecomdemo.inventory;

import com.ecomdemo.jwt.ServiceTokens;
import com.ecomdemo.support.TestJwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.shared.TokenClaims;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Who may call this service, and who may not.
 *
 * <p><strong>This test exists because its absence cost an afternoon.</strong> inventory-service was
 * extracted with the resource-server starter on its classpath and no filter chain of its own, so
 * Spring Boot's fallback secured every path with a generated password. Nothing failed to compile,
 * every unit test passed, and the service started and reported itself healthy — the health probe
 * being the one path the fallback leaves open. It was the smoke test, at the very end, that found
 * it: a 500 from the product listing, three layers away from the cause.
 *
 * <p>So the assertions below are deliberately about the <em>chain</em>, not about stock. The first
 * one is the regression; the second is what compose's healthcheck depends on, which the fallback
 * happened to satisfy and a hand-written chain easily would not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Inventory security")
class InventorySecurityTest {

    /** No broker in this test; nothing here gets as far as publishing. */
    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private MockMvc mvc;


    @Autowired
    private JwtProperties jwtProperties;

    @Test
    @DisplayName("a call with no token is rejected, not served")
    void refusesAnUnauthenticatedCall() throws Exception {
        mvc.perform(get("/api/inventory/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a call with a token signed by somebody else is rejected")
    void refusesAForgedToken() throws Exception {
        // The same shape of token, signed with a different key. This is the assertion that would
        // fail if the decoder were ever built without the issuer and signature validators - a
        // token nobody in this system minted must not be usable.
        String forged = TestJwt.serviceSignedBy(TestJwt.generate("impostor-key"), "impostor",
                ServiceTokens.INVENTORY_READ, ServiceTokens.INVENTORY_WRITE);

        mvc.perform(get("/api/inventory/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a service token is accepted, for reads and for writes alike")
    void acceptsAServiceToken() throws Exception {
        String token = TestJwt.service("ecomdemo-app", ServiceTokens.INVENTORY_READ, ServiceTokens.INVENTORY_WRITE);

        mvc.perform(get("/api/inventory/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // A write too, because the chain could conceivably admit a GET and reject a PUT.
        mvc.perform(put("/api/inventory/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                // Anything but 401 or 403 means the request got through the filter chain.
                .andExpect(status().is2xxSuccessful());
    }

    /**
     * KI-011: checkout stopped calling these in Phase 24 (the saga reserves from the event) and
     * the endpoints were removed. A caller that still tries gets a 404, not a quiet stock change.
     */
    @Test
    @DisplayName("the old HTTP reserve and release endpoints are gone (KI-011)")
    void reserveAndReleaseAreGone() throws Exception {
        String token = TestJwt.service("ecomdemo-app", ServiceTokens.INVENTORY_READ, ServiceTokens.INVENTORY_WRITE);

        for (String action : new String[] {"reserve", "release"}) {
            mvc.perform(post("/api/inventory/1/" + action)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"units\":1,\"productName\":\"Anything\"}"))
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * Phase 33: least privilege between services. A token holding {@code inventory:read} alone may
     * look stock up and never set it. Until Phase 33 every service token carried the same SERVICE
     * role, and any service could have emptied the stock of any product.
     */
    @Test
    @DisplayName("a read-only service token can read stock but not change it")
    void aReadScopeCannotWrite() throws Exception {
        String readOnly = TestJwt.service("read-only-client", ServiceTokens.INVENTORY_READ);

        mvc.perform(get("/api/inventory/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnly))
                .andExpect(status().isOk());
        mvc.perform(put("/api/inventory/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnly)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a service token with no inventory scope cannot even read")
    void aForeignScopeCannotRead() throws Exception {
        String gateways = TestJwt.service("gateway-service", ServiceTokens.CATALOG_READ);

        mvc.perform(get("/api/inventory/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + gateways))
                .andExpect(status().isForbidden());
    }

    /**
     * The Phase 31 regression. A CUSTOMER's token is perfectly valid - signed by us, not expired -
     * and until the security review that was all this chain asked for, so a shopper who reached
     * this port directly could set stock levels. No shopper has a reason to call this service at
     * all, so every path refuses them: reads, the admin write and the order close alike.
     */
    @Test
    @DisplayName("a CUSTOMER's valid token is refused on every path (Phase 31, OWASP API5)")
    void refusesACustomer() throws Exception {
        String customer = userToken("shopper", 7, "CUSTOMER");

        mvc.perform(get("/api/inventory/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + customer))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/inventory/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1000}"))
                .andExpect(status().isForbidden());

        // Phase 32: closing an order releases its stock, so a shopper must not be able to do it.
        mvc.perform(post("/api/inventory/orders/1/close")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"mine now\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an ADMIN's token may read and set a stock level (the gateway's /api/inventory rule)")
    void acceptsAnAdmin() throws Exception {
        String admin = userToken("boss", 1, "ADMIN");

        mvc.perform(get("/api/inventory/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk());
        mvc.perform(put("/api/inventory/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isOk());
    }

    /** A user's token, shaped like the ones customer-service issues. */
    private String userToken(String username, long userId, String... roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(java.time.Duration.ofMinutes(15)))
                .claim(TokenClaims.USER_ID, userId)
                .claim(TokenClaims.ROLES, List.of(roles))
                .build();
        return TestJwt.sign(claims);
    }

    @Test
    @DisplayName("the readiness probe is open, because compose's healthcheck has no credentials")
    void leavesTheHealthProbeOpen() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the Prometheus endpoint is open, because the scraper has no credentials either")
    void leavesTheMetricsEndpointOpen() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }
}
