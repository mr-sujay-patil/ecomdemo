package com.ecomdemo.auth;

import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.security.AppUserDetails;
import com.ecomdemo.security.SigningKeys;
import com.ecomdemo.shared.TokenClaims;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues every token in the system: a person's at login, a service's for the client credentials
 * grant.
 *
 * <p>Signed RS256 with the ACTIVE key, whose id goes in the header as {@code kid} (Phase 33). The
 * {@code kid} is how a verifier holding several published keys knows which one to check with, and
 * what lets a rotation happen without anyone being told.
 */
@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final SigningKeys keys;

    public TokenService(JwtEncoder jwtEncoder, JwtProperties properties, SigningKeys keys) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.keys = keys;
    }

    /**
     * A person's token: {@code sub} = username, {@code uid} = account id, {@code roles} = the roles
     * without Spring's {@code ROLE_} prefix.
     */
    public TokenResponse issueFor(AppUserDetails user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.expiry());
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
        return TokenResponse.bearer(sign(claims), issuedAt, expiresAt);
    }

    /**
     * A service's token: {@code sub} = its client id, {@code scope} = what it may do, and no roles
     * and no user id, because there is no person behind the call. An absent claim is honest; a
     * plausible one would be a lie in an audit log.
     */
    public IssuedServiceToken issueForService(String clientId, Collection<String> scopes) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.expiry());
        String scope = String.join(" ", scopes);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(clientId)
                .claim(TokenClaims.SCOPE, scope)
                .build();
        return new IssuedServiceToken(sign(claims), properties.expiry().toSeconds(), scope);
    }

    private String sign(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.active().getKeyID()).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** A service token and what the token endpoint reports about it. */
    public record IssuedServiceToken(String value, long expiresInSeconds, String scope) {
    }
}
