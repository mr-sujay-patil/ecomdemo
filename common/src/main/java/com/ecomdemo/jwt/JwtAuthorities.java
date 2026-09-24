package com.ecomdemo.jwt;

import com.ecomdemo.shared.TokenClaims;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Turns the {@code roles} claim of a token into Spring Security authorities, the same way in every
 * service.
 *
 * <p>Spring's default reads {@code scope}/{@code scp} and prefixes with {@code SCOPE_}. This system
 * issues its own tokens and thinks in roles, so it reads {@code roles} and prefixes with
 * {@code ROLE_} — which is what makes the untouched {@code hasRole(...)} expressions keep working.
 *
 * <p>It is in {@code common} for the reason every contract here ends up in {@code common}: two
 * services that disagree about the prefix do not fail to compile, they fail at runtime with a 403
 * that says nothing about why.
 */
public final class JwtAuthorities {

    /**
     * The one definition. {@code hasRole("ADMIN")} is shorthand for the authority
     * {@code ROLE_ADMIN}, so anything that builds authorities by hand has to agree with this.
     */
    public static final String ROLE_PREFIX = "ROLE_";

    private JwtAuthorities() {
    }

    /** The converter to hand to {@code oauth2ResourceServer().jwt(...)}. */
    public static JwtAuthenticationConverter converter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenClaims.ROLES);
        authorities.setAuthorityPrefix(ROLE_PREFIX);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
