package com.ecomdemo.jwt;

import com.ecomdemo.shared.TokenClaims;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Mints and caches the token a service presents when it calls another service. See
 * {@link ServiceTokens} for why a service identity exists at all rather than relaying the caller's
 * token.
 *
 * <p><strong>Why cache it.</strong> Signing an HS256 token is cheap — microseconds — so the cache
 * is not really about CPU. It is about the {@code iat}/{@code exp} pair: a fresh token per request
 * means a fresh pair per request, which makes every outbound call unique, defeats any hope of the
 * callee caching a decode, and produces a log full of distinct tokens that cannot be correlated. A
 * handful of tokens per hour is easier to reason about and easier to read in a trace.
 *
 * <p><strong>The margin.</strong> The cached token is replaced a minute before it actually expires,
 * not at expiry. Without that, a token that passes the check here can still arrive expired — the
 * network hop takes time, and the two clocks are not identical. A minute is far more than either
 * effect needs and costs nothing.
 *
 * <p>Thread safety is one volatile reference to an immutable record. Two threads that both find the
 * token stale will both mint one and one will win; minting is idempotent and the loser's token is
 * simply discarded, which is cheaper than holding a lock across the signing call.
 */
public class ServiceTokenProvider {

    /** Replace the token this long before it expires. See the class comment. */
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    /** The floor on a service token's life, so a misconfigured tiny expiry cannot spin. */
    private static final Duration MINIMUM_LIFETIME = Duration.ofMinutes(2);

    private final JwtEncoder encoder;
    private final String issuer;
    private final String subject;
    private final Duration lifetime;

    private volatile CachedToken cached = null;

    /**
     * @param subject the name this service calls itself, written to {@code sub}. It appears in the
     *     callee's logs, so it should be the service's own name and nothing else.
     */
    public ServiceTokenProvider(JwtEncoder encoder, JwtProperties properties, String subject) {
        this.encoder = encoder;
        this.issuer = properties.issuer();
        this.subject = subject;
        Duration configured = properties.expiry() == null ? MINIMUM_LIFETIME : properties.expiry();
        this.lifetime = configured.compareTo(MINIMUM_LIFETIME) < 0 ? MINIMUM_LIFETIME : configured;
    }

    /** A valid token, minted if the cached one is missing or close to expiry. */
    public String token() {
        CachedToken current = cached;
        Instant now = Instant.now();
        if (current != null && now.isBefore(current.renewAt())) {
            return current.value();
        }

        Instant expiresAt = now.plus(lifetime);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(expiresAt)
                // No `uid`: there is no user behind this call, and inventing one would make the
                // callee's logs claim something false about who was served.
                .claim(TokenClaims.ROLES, List.of(ServiceTokens.ROLE))
                .build();

        String value = encoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        cached = new CachedToken(value, expiresAt.minus(REFRESH_MARGIN));
        return value;
    }

    private record CachedToken(String value, Instant renewAt) {
    }
}
