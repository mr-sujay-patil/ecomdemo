package com.ecomdemo.auth;

import com.ecomdemo.security.SigningKeyProperties;
import com.ecomdemo.security.SigningKeys;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.util.List;

import com.ecomdemo.shared.TokenClaims;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.customer.Role;
import com.ecomdemo.security.AppUserDetails;
import com.ecomdemo.security.JwtConfig;
import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.support.TestData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Unit tests for {@link TokenService}: what actually ends up inside a token.
 *
 * <p>A real encoder is used rather than a mock. Mocking it would leave nothing worth asserting —
 * the whole value of this class is the claim set it builds, and the only honest way to check
 * that is to sign a token and read it back. The key is an RSA pair generated for the run (Phase 33:
 * RS256), and verification uses only its PUBLIC half, as every other service does.
 */
class TokenServiceTest {

    /** A generated RSA key pair, as customer-service uses when no key is configured. */
    private static final SigningKeys KEYS = SigningKeys.from(new SigningKeyProperties(List.of(), null));

    private static final JwtProperties PROPERTIES =
            new JwtProperties("ecomdemo-test", Duration.ofMinutes(15), null);

    private final TokenService tokenService =
            new TokenService(new NimbusJwtEncoder(new ImmutableJWKSet<>(KEYS.all())), PROPERTIES, KEYS);

    private final JwtDecoder decoder = publicKeyDecoder(KEYS);

    private static JwtDecoder publicKeyDecoder(SigningKeys keys) {
        try {
            return NimbusJwtDecoder.withPublicKey(keys.active().toRSAPublicKey()).build();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the token carries the account id, the username and the roles")
    void issuesTheClaimsTheApplicationAuthorizesFrom() {
        // Given
        AppUserDetails asha = new AppUserDetails(TestData.user(7L, "asha", Role.CUSTOMER));

        // When
        TokenResponse response = tokenService.issueFor(asha);

        // Then: these three claims are the whole basis of every later authorization decision,
        // because nothing reads the database again
        var jwt = decoder.decode(response.accessToken());
        assertThat(jwt.getSubject()).isEqualTo("asha");
        // Assigned to Object first: getClaim is generic (<T> T), so passing it straight to
        // assertThat leaves the compiler unable to choose an overload.
        Object userIdClaim = jwt.getClaim(TokenClaims.USER_ID);
        assertThat(userIdClaim).hasToString("7");
        assertThat(jwt.getClaimAsStringList(TokenClaims.ROLES)).containsExactly("CUSTOMER");
        // Read as a string, not via getIssuer(): that accessor insists on a URL, and this
        // application's issuer is a plain name. The decoder's issuer validator compares strings.
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("ecomdemo-test");
    }

    @Test
    @DisplayName("the roles claim has no ROLE_ prefix")
    void stripsTheFrameworkPrefix() {
        // Given: the authority really is ROLE_ADMIN on the UserDetails
        AppUserDetails admin = new AppUserDetails(TestData.user(2L, "admin", Role.ADMIN));
        assertThat(admin.getAuthorities()).singleElement().hasToString("ROLE_ADMIN");

        // When
        var jwt = decoder.decode(tokenService.issueFor(admin).accessToken());

        // Then: ROLE_ is Spring Security's convention, not part of this API's contract. It is
        // stripped here and added back by the converter in SecurityConfig.
        assertThat(jwt.getClaimAsStringList(TokenClaims.ROLES)).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("the token expires after the configured duration, and says so")
    void setsAShortExpiry() {
        // When
        Instant before = Instant.now();
        TokenResponse response = tokenService.issueFor(new AppUserDetails(TestData.customer()));

        // Then
        var jwt = decoder.decode(response.accessToken());
        assertThat(jwt.getExpiresAt()).isNotNull();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        // The client is told the same thing in seconds, so it can count down without parsing
        assertThat(response.expiresIn()).isEqualTo(900L);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresAt()).isAfter(before);
    }

    @Test
    @DisplayName("the payload is readable by anyone: it is encoded, not encrypted")
    void thePayloadIsOnlyBase64() {
        // When
        String token = tokenService.issueFor(new AppUserDetails(TestData.customer())).accessToken();

        // Then: three dot-separated segments, and the middle one decodes without any key at all.
        // This is not a flaw being documented — it is why nothing secret may ever go in a claim.
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"sub\":\"customer\"").contains("\"uid\":1");
    }

    @Test
    @DisplayName("a payload edited by hand no longer verifies")
    void tamperingBreaksTheSignature() {
        // Given a genuine CUSTOMER token...
        String token = tokenService.issueFor(new AppUserDetails(TestData.customer())).accessToken();
        String[] parts = token.split("\\.");

        // ...rewritten to claim ADMIN, and re-encoded. This is the attack the signature exists
        // to stop, and the only reason it is safe to authorize from claims at all.
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"CUSTOMER\"", "\"ADMIN\"");
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        // Then
        assertThat(forged).isNotEqualTo(token);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> decoder.decode(forged))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void aDifferentKeyDoesNotVerify() {
        // Given a token minted with somebody else's RSA key
        SigningKeys other = SigningKeys.from(new SigningKeyProperties(List.of(), null));
        TokenService impostor =
                new TokenService(new NimbusJwtEncoder(new ImmutableJWKSet<>(other.all())), PROPERTIES, other);
        String token = impostor.issueFor(new AppUserDetails(TestData.admin())).accessToken();

        // Then: our public key verifies only what our private key signed. And holding the public key,
        // as every other service does since Phase 33, is no help in minting one: that is the point
        // of asymmetric signing, and what HS256's one shared secret could not give.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    @DisplayName("a service token names the service and its scopes, and claims no user and no role")
    void issuesAScopedServiceToken() {
        TokenService.IssuedServiceToken issued =
                tokenService.issueForService("catalog-service", List.of("inventory:read"));

        var token = decoder.decode(issued.value());
        assertThat(token.getSubject()).isEqualTo("catalog-service");
        assertThat(token.getClaimAsString(TokenClaims.SCOPE)).isEqualTo("inventory:read");
        assertThat(token.getClaimAsString(TokenClaims.USER_ID)).isNull();
        assertThat(token.getClaimAsStringList(TokenClaims.ROLES)).isNull();
        assertThat(issued.expiresInSeconds()).isEqualTo(900L);
    }
}
