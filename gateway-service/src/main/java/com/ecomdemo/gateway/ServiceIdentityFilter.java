package com.ecomdemo.gateway;

import com.ecomdemo.jwt.ServiceTokenProvider;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Gives an anonymous request the gateway's own identity before forwarding it.
 *
 * <p><strong>Why this class exists, and it is not a design flourish.</strong> catalog-service and
 * inventory-service end their filter chains with {@code anyRequest().authenticated()}: they accept no
 * unauthenticated call at all, deliberately, because a service's API is not a public API. Browsing the
 * catalogue IS public, though, so something has to bridge the two — and until Phase 21 that something
 * was {@code CatalogClient} inside the application, which attached a service token to every forwarded
 * call.
 *
 * <p>Deleting the proxy deleted that, and the first cold run said so immediately: {@code GET
 * /api/products} came back <strong>401</strong> through the gateway, from catalog-service, for a path
 * the gateway itself had already permitted. The edge said yes and the service said no.
 *
 * <p><strong>Why adding a token here is safe.</strong> This filter runs AFTER the security chain, so a
 * request that reaches it has already passed the gateway's authorisation rules. The only requests that
 * arrive here without an {@code Authorization} header are the ones {@link GatewaySecurityConfig}
 * explicitly permits anonymously: browsing the catalogue, logging in, registering. An anonymous write
 * was refused with 401 one filter earlier and never gets this far.
 *
 * <p>That does make the edge load-bearing for catalog-service's authorisation, since catalog-service
 * only asks "is this caller authenticated?" and not "may they write?". That was equally true of the
 * proxy this replaces — the difference is that the decision now lives in one place instead of three,
 * and {@code EdgeSecurityIT} asserts it.
 *
 * <p><strong>A caller's own token is never replaced.</strong> When a shopper presents theirs it is
 * relayed untouched, so the downstream service sees the USER, not the gateway. Overwriting it would
 * erase the identity every {@code CurrentUser} call depends on — a cart belonging to "gateway-service"
 * is not a bug that announces itself.
 */
@Component
class ServiceIdentityFilter implements WebFilter, Ordered {

    private final ServiceTokenProvider serviceTokens;

    ServiceIdentityFilter(ServiceTokenProvider serviceTokens) {
        this.serviceTokens = serviceTokens;
    }

    /**
     * After Spring Security (which registers at {@code -100}), and before the routing handler.
     *
     * <p>The order is the safety argument. Running BEFORE the security chain would hand every
     * anonymous request a valid token on its way in — including the ones the edge is about to refuse,
     * which would then be authenticated and refused for the wrong reason, or worse, permitted.
     */
    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null) {
            return chain.filter(exchange);
        }

        // Since Phase 33 the token comes from customer-service (client credentials, cached for its
        // lifetime), so fetching one can mean an HTTP call. It runs on the bounded-elastic scheduler,
        // never on this Netty event-loop thread: a blocking call there would stall every other
        // connection the thread is serving. A cache hit costs a thread hop and nothing more.
        return Mono.fromCallable(serviceTokens::token)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(token -> chain.filter(exchange.mutate()
                        .request(request -> request.headers(headers -> headers.setBearerAuth(token)))
                        .build()));
    }
}
