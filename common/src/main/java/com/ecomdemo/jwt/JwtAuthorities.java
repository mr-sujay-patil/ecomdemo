package com.ecomdemo.jwt;

import com.ecomdemo.shared.TokenClaims;
import java.util.ArrayList;
import java.util.Collection;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Turns a token's claims into the authorities the security rules check.
 *
 * <p>Two kinds of caller, two claims (Phase 33):
 * <ul>
 *   <li>a PERSON's token carries {@code roles} ({@code CUSTOMER}, {@code ADMIN}) → {@code ROLE_*};
 *   <li>a SERVICE's token carries {@code scope} ({@code catalog:read inventory:read}) →
 *       {@code SCOPE_*}. It has no roles: a service is not a customer or an administrator, and the
 *       old shared {@code ROLE_SERVICE} let every service call every endpoint any service could.
 * </ul>
 * A rule then names exactly who may call it, for example
 * {@code hasAnyAuthority("ROLE_ADMIN", "SCOPE_inventory:write")}.
 */
public final class JwtAuthorities {

    public static final String ROLE_PREFIX = "ROLE_";

    public static final String SCOPE_PREFIX = "SCOPE_";

    private JwtAuthorities() {
    }

    /** {@code roles} → {@code ROLE_*} plus {@code scope} → {@code SCOPE_*}, for any decoder. */
    public static Converter<Jwt, Collection<GrantedAuthority>> authorities() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName(TokenClaims.ROLES);
        roles.setAuthorityPrefix(ROLE_PREFIX);
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthoritiesClaimName(TokenClaims.SCOPE);
        scopes.setAuthorityPrefix(SCOPE_PREFIX);
        return jwt -> {
            Collection<GrantedAuthority> all = new ArrayList<>(roles.convert(jwt));
            all.addAll(scopes.convert(jwt));
            return all;
        };
    }

    public static JwtAuthenticationConverter converter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities());
        return converter;
    }
}
