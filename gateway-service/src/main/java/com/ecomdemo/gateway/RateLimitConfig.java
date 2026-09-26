package com.ecomdemo.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * What the rate limiter counts against.
 *
 * <p><strong>The key is the entire design decision.</strong> A limiter with one shared bucket is a
 * denial-of-service tool: the first busy client exhausts it and everybody else is refused. A limiter
 * keyed per caller protects the system from any one of them, which is what a rate limit is for.
 *
 * <p>So: the authenticated username when there is one, and the client's IP address when there is
 * not. Both halves matter. Keying only by IP would put every customer behind one office NAT into the
 * same bucket. Keying only by user would leave login and registration — the two anonymous endpoints,
 * and the two most worth protecting from a brute-force attempt — with no key at all.
 *
 * <p><strong>The IP fallback is honest about its limits.</strong> Behind a real load balancer the
 * remote address is the balancer's, and the client's is in {@code X-Forwarded-For} — a header the
 * client itself can forge unless something trustworthy overwrites it. This project has no such hop
 * yet, so the remote address is the truth here; when Phase 25 puts an ingress in front, this is one
 * of the places that must change, and a comment is cheaper than rediscovering it.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    private static final String ANONYMOUS_PREFIX = "ip:";

    private static final String USER_PREFIX = "user:";

    private static final String UNKNOWN_CLIENT = ANONYMOUS_PREFIX + "unknown";

    /**
     * Named {@code rateLimitKeyResolver} because {@code application.yml} refers to it by name.
     *
     * <p>A {@code #{@beanName}} reference in configuration resolves at runtime, so a rename here
     * with the YAML left behind fails when the first request arrives rather than at startup. That is
     * why {@code GatewayRateLimitConfigurationTest} asserts the bean name the configuration asks
     * for — a string in a YAML file is not checked by the compiler, and this project has already
     * paid for one contract written from memory.
     */
    @Bean
    KeyResolver rateLimitKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(authentication -> authentication != null && authentication.isAuthenticated())
                .map(authentication -> USER_PREFIX + authentication.getName())
                .defaultIfEmpty(clientAddress(exchange))
                .switchIfEmpty(Mono.just(clientAddress(exchange)));
    }

    private static String clientAddress(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return UNKNOWN_CLIENT;
        }
        return ANONYMOUS_PREFIX + remote.getAddress().getHostAddress();
    }
}
