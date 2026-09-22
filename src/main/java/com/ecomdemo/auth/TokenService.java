package com.ecomdemo.auth;

import com.ecomdemo.common.TokenClaims;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.security.AppUserDetails;
import com.ecomdemo.security.JwtConfig;
import com.ecomdemo.security.JwtProperties;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

/**
 * Mints a signed token for an account that has already proved who it is.
 *
 * <p><strong>A JWT is three Base64url segments joined by dots.</strong> A header naming the
 * algorithm, a payload of claims, and a signature over the first two:
 *
 * <pre>
 *   eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiJhc2hhIiwidWlkIjo0fQ . 3nK9r...
 *   └── header ────────┘   └── payload (claims) ────────┘   └ signature
 * </pre>
 *
 * <p>The payload is <em>encoded, not encrypted</em> — anyone holding the token can read the
 * claims, exactly as anyone could read a Base64 Basic header. What the signature buys is
 * integrity: change a single character of the payload and the signature no longer matches, so
 * "role: ADMIN" cannot be edited in. Nothing sensitive goes in a claim, which is why the id,
 * the username and the roles are here and nothing else is.
 *
 * <p><strong>Why the claims are what they are.</strong> Every request after login is authorized
 * from this payload alone, with no database lookup — that is what "stateless" means. So the
 * token carries the account id (the cart and the orders are keyed by it) and the roles (the URL
 * rules are decided from them). The cost of that speed is the flip side: a role changed in the
 * database does not take effect until the current token expires, and a token cannot be revoked
 * at all. A short expiry is the only lever, which is why the default is minutes rather than days.
 */
@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public TokenService(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public TokenResponse issueFor(AppUserDetails user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.expiry());

        // The ROLE_ prefix is Spring Security's own convention and has no business meaning, so
        // it is stripped on the way out and added back by the converter on the way in. A token
        // is a contract with clients; it should not leak the framework we happen to use.
        List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith(AppUserDetails.ROLE_PREFIX)
                        ? authority.substring(AppUserDetails.ROLE_PREFIX.length())
                        : authority)
                .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getUsername())
                .claim(TokenClaims.USER_ID, user.getId())
                .claim(TokenClaims.ROLES, roles)
                .build();

        // The header is signed along with the payload, which is what stops an attacker rewriting
        // `alg` to "none" and presenting an unsigned token — the classic JWT vulnerability. The
        // decoder is also pinned to HS256, so even a validly signed token using a different
        // algorithm is refused.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return TokenResponse.bearer(token, issuedAt, expiresAt);
    }
}
