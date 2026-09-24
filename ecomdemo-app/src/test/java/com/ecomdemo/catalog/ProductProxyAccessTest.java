package com.ecomdemo.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.ecomdemo.clients.catalog.CatalogGateway;
import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.support.WithSecurityRules;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Who may use the public {@code /api/products}, now that the catalogue is a separate service.
 *
 * <h2>Why this class exists, and where it came from</h2>
 *
 * <p>These assertions are not new. They lived in catalog-service's {@code ProductControllerTest}
 * until Phase 20c, where they had been since Phase 8 — and they had to move, because they stopped
 * being true there. catalog-service is internal now: every path on it requires a service token, it
 * has no anonymous callers, and it cannot tell an administrator from a shopper, because the token
 * it receives always says {@code ecomdemo-app}.
 *
 * <p>So the rules moved to the edge, which is the only place a human's token actually arrives —
 * and this is that edge. <strong>Moving a rule without moving its test is how a rule quietly stops
 * being enforced</strong>, and the comment left behind in catalog-service points here so the next
 * reader does not have to take that on trust.
 *
 * <p>What is asserted is deliberately unchanged from the Phase 8 version: reads are the shop window
 * and need no account, writes need ADMIN, and a refused write comes back in the standard error
 * shape. A shopper cannot tell that the catalogue moved, which is the entire point.
 */
@WebMvcTest(ProductController.class)
@WithSecurityRules
@WithMockUser(username = "admin", roles = "ADMIN")
@DisplayName("Public /api/products access rules")
class ProductProxyAccessTest {

    private static final String VALID_BODY = """
            {"name":"Keyboard","description":"clicky","price":8999.00,"stockQuantity":5,"category":"PERIPHERALS"}
            """;

    private static final ProductSnapshot KEYBOARD =
            new ProductSnapshot(1L, "Keyboard", "clicky", new BigDecimal("8999.00"), "PERIPHERALS", 5);

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CatalogGateway catalogue;

    @Test
    @WithAnonymousUser
    @DisplayName("browsing is allowed without an account — the catalogue is the shop window")
    void browsingIsAnonymous() {
        when(catalogue.findAll()).thenReturn(List.of(KEYBOARD));
        when(catalogue.requireProduct(1L)).thenReturn(KEYBOARD);

        assertThat(mvc.get().uri("/api/products")).hasStatus(OK);
        assertThat(mvc.get().uri("/api/products/1")).hasStatus(OK);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("writing without a token is 401, and never reaches catalog-service")
    void writingAnonymouslyIsUnauthorized() {
        assertThat(mvc.post().uri("/api/products").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .hasStatus(UNAUTHORIZED);
        assertThat(mvc.delete().uri("/api/products/1")).hasStatus(UNAUTHORIZED);

        // The second half matters as much as the status: a refused request must not have travelled.
        verify(catalogue, never()).create(any());
        verify(catalogue, never()).delete(any());
    }

    @Test
    @WithMockUser(username = "shopper", roles = "CUSTOMER")
    @DisplayName("a customer is authenticated and still refused — that is authorization")
    void customerCannotWrite() {
        assertThat(mvc.post().uri("/api/products").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .hasStatus(FORBIDDEN);
        assertThat(mvc.delete().uri("/api/products/1")).hasStatus(FORBIDDEN);

        verify(catalogue, never()).create(any());
    }

    @Test
    @WithMockUser(username = "shopper", roles = "CUSTOMER")
    @DisplayName("but a customer may still browse — opening writes to ADMIN closed nothing else")
    void customerCanStillBrowse() {
        when(catalogue.findAll()).thenReturn(List.of(KEYBOARD));

        assertThat(mvc.get().uri("/api/products")).hasStatus(OK);
    }

    @Test
    @WithMockUser(username = "shopper", roles = "CUSTOMER")
    @DisplayName("a refused write comes back in the standard error shape")
    void refusedWriteUsesTheStandardErrorShape() {
        assertThat(mvc.delete().uri("/api/products/1"))
                .hasStatus(FORBIDDEN)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"status":403,"message":"Your account does not have permission to perform this action."}
                        """);
    }
}
