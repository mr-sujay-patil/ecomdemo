package com.ecomdemo.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.customer.Role;
import com.ecomdemo.security.AppUserDetails;
import com.ecomdemo.security.JwtConfig;
import com.ecomdemo.security.JwtProperties;
import com.ecomdemo.support.TestData;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Unit tests for {@link TokenService}: what actually ends up inside a token.
 *
 * <p>A real encoder is used rather than a mock. Mocking it would leave nothing worth asserting —
 * the whole value of this class is the claim set it builds, and the only honest way to check
 * that is to sign a token and read it back. The key here is a fixed 32-byte string, so the test
 * is deterministic and nothing about it depends on configuration.
 */
class TokenServiceTest {

    private static final String KEY_TEXT = "a-test-signing-key-of-32-bytes!!";

    private static final SecretKey KEY =
            new SecretKeySpec(KEY_TEXT.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private static final JwtProperties PROPERTIES =
            new JwtProperties(KEY_TEXT, "ecomdemo-test", Duration.ofMinutes(15));

    private final TokenService tokenService =
            new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(KEY)), PROPERTIES);

    private final JwtDecoder decoder =
            NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();

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
        Object userIdClaim = jwt.getClaim(JwtConfig.Claims.USER_ID);
        assertThat(userIdClaim).hasToString("7");
        assertThat(jwt.getClaimAsStringList(JwtConfig.Claims.ROLES)).containsExactly("CUSTOMER");
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
        assertThat(jwt.getClaimAsStringList(JwtConfig.Claims.ROLES)).containsExactly("ADMIN");
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
        // Given a token minted with somebody else's key
        SecretKey otherKey = new SecretKeySpec(
                "a-completely-different-32-byte-k".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        TokenService impostor =
                new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(otherKey)), PROPERTIES);
        String token = impostor.issueFor(new AppUserDetails(TestData.admin())).accessToken();

        // Then: HMAC signs and verifies with the SAME secret, so not holding it means not being
        // able to mint one either — which is also why sharing the key with a second service
        // would hand that service the power to issue tokens.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }
}
