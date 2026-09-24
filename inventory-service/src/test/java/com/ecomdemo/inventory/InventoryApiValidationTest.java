package com.ecomdemo.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.jwt.ServiceTokenProvider;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
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
 * A malformed request body is the caller's mistake, and the status has to say so.
 *
 * <p>This exists because of a 500 that {@code InventorySecurityTest} tripped over by accident. One
 * {@code QuantityRequest} record served all three write endpoints, each needing a different two of
 * its three fields, so no field could carry {@code @NotNull} — and {@code @Positive} alone is
 * satisfied by {@code null}. A body without {@code units} passed validation and reached
 * {@code request.units()}, where unboxing a null {@link Integer} threw. The caller sent a bad
 * request and was told the server was broken.
 *
 * <p>The annotations on the test class match {@code InventorySecurityTest} exactly, so both share
 * one cached Spring context instead of starting a second.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Inventory request validation")
class InventoryApiValidationTest {

    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecretKey jwtSigningKey;

    @Autowired
    private JwtProperties jwtProperties;

    private String token;

    @BeforeEach
    void mintACallerToken() {
        token = new ServiceTokenProvider(
                new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey)), jwtProperties, "ecomdemo-app")
                .token();
    }

    @Test
    @DisplayName("a reservation with no units is a 400, not a 500")
    void rejectsAReservationMissingItsUnits() throws Exception {
        perform(post("/api/inventory/1/reserve"), "{\"productName\":\"Anything\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a reservation of zero units is a 400")
    void rejectsAReservationOfNothing() throws Exception {
        perform(post("/api/inventory/1/reserve"), "{\"units\":0}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a release with no units is a 400, not a 500")
    void rejectsAReleaseMissingItsUnits() throws Exception {
        perform(post("/api/inventory/1/release"), "{}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a stock level with no quantity is a 400, not a 500")
    void rejectsALevelMissingItsQuantity() throws Exception {
        perform(put("/api/inventory/1"), "{}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a negative stock level is a 400, but zero is allowed")
    void rejectsANegativeLevelAndAcceptsZero() throws Exception {
        perform(put("/api/inventory/1"), "{\"quantity\":-1}").andExpect(status().isBadRequest());

        // Zero is not a mistake: it is how a product goes out of stock, and the constraint is
        // PositiveOrZero for exactly this case.
        perform(put("/api/inventory/1"), "{\"quantity\":0}").andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String body) throws Exception {
        return mvc.perform(request
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
