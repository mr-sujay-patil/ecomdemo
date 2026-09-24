package com.ecomdemo.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.jwt.ServiceTokenProvider;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
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
    private SecretKey jwtSigningKey;

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
        SecretKey otherKey = new javax.crypto.spec.SecretKeySpec(
                "a-completely-different-32-byte-key!!".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256");
        String forged = new ServiceTokenProvider(
                new NimbusJwtEncoder(new ImmutableSecret<>(otherKey)), jwtProperties, "impostor")
                .token();

        mvc.perform(get("/api/inventory/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a service token is accepted, for reads and for writes alike")
    void acceptsAServiceToken() throws Exception {
        String token = new ServiceTokenProvider(
                new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey)), jwtProperties, "ecomdemo-app")
                .token();

        mvc.perform(get("/api/inventory/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // A write too, because the chain could conceivably admit a GET and reject a POST — and
        // because a reservation is the call whose failure would break checkout rather than a
        // listing.
        mvc.perform(post("/api/inventory/1/reserve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"units\":1,\"productName\":\"Anything\"}"))
                // The reservation has no stock to take, so it fails — on BUSINESS grounds, which
                // is exactly the point. A 409 means the request got through the filter chain and
                // was understood; 401 or 403 would mean it never arrived. What the stock rules
                // then decide belongs to the tests that own them.
                .andExpect(status().isConflict());
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
