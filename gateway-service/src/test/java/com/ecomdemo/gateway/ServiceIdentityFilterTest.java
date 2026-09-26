package com.ecomdemo.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.jwt.ServiceTokenProvider;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The two branches of {@link ServiceIdentityFilter}, and the order that makes it safe.
 *
 * <p>A unit test on purpose. What this filter does is decide, per request, whether to add one header —
 * and the interesting cases are "no credentials" and "somebody else's credentials", both of which are
 * a two-line exchange to construct. Standing up a context and four services to observe a header would
 * be slower and would prove less, because the header is checked here directly rather than inferred
 * from what a downstream service happened to accept.
 */
@DisplayName("Service identity at the edge")
class ServiceIdentityFilterTest {

    private static final String SERVICE_TOKEN = "minted.service.token";

    private final ServiceTokenProvider serviceTokens = mock(ServiceTokenProvider.class);

    private final ServiceIdentityFilter filter = new ServiceIdentityFilter(serviceTokens);

    /** Captures whatever the filter passed down the chain, so the header can be read off it. */
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    private final WebFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @Test
    @DisplayName("an anonymous request is given the gateway's own token, or the service refuses it")
    void anonymousRequestsAreGivenTheGatewaysIdentity() {
        when(serviceTokens.token()).thenReturn(SERVICE_TOKEN);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/products"));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .as("catalog-service ends its chain with anyRequest().authenticated(), so an "
                        + "anonymous browse must arrive carrying SOMETHING")
                .isEqualTo("Bearer " + SERVICE_TOKEN);
    }

    /**
     * THE ONE THAT MATTERS MOST.
     *
     * <p>Replacing a shopper's token with the gateway's would erase the identity every
     * {@code CurrentUser} call downstream depends on — and the symptom would not be an error. It would
     * be a cart belonging to "gateway-service", shared by every logged-in shopper at once.
     */
    @Test
    @DisplayName("a caller's OWN token is relayed untouched, never replaced")
    void aCallersTokenIsNeverReplaced() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/cart").header(HttpHeaders.AUTHORIZATION, "Bearer shoppers.own.token"));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer shoppers.own.token");
        verify(serviceTokens, never()).token();
    }

    /**
     * The order is the security argument, so it is asserted rather than left to a comment.
     *
     * <p>Spring Security's chain registers at {@code -100}. A filter that minted tokens BEFORE it
     * would hand a valid credential to every anonymous request on the way in, including the ones the
     * edge is about to refuse — turning a 401 into an authenticated request that the downstream
     * service would happily serve.
     */
    @Test
    @DisplayName("it runs AFTER the security chain, not before")
    void itRunsAfterSpringSecurity() {
        int springSecurityChainOrder = -100;

        assertThat(filter.getOrder())
                .as("minting before the edge has decided would authenticate requests it is about to "
                        + "refuse")
                .isGreaterThan(springSecurityChainOrder);
        assertThat(filter.getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE);
    }
}
