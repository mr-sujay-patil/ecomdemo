package com.ecomdemo.auth;

import com.ecomdemo.common.TokenClaims;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.common.ApiError;
import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.security.JwtConfig;
import com.ecomdemo.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * The token lifecycle over real HTTP and real PostgreSQL: get one, use it, and watch the
 * application refuse the ones it should.
 *
 * <p>This is the layer where the "Done when" of the phase is actually settled. The unit tests
 * prove the decoder rejects a bad token; only here does a bad token travel over a socket,
 * through the real filter chain, and come back as a 401 with this application's error shape.
 *
 * <p>{@link JwtEncoder} is injected so that a <em>correctly signed but expired</em> token can be
 * minted with the running application's own key. Waiting for a real token to expire would mean
 * waiting out the decoder's clock-skew allowance as well — a minute of build time to learn
 * nothing extra.
 */
class AuthApiIT extends IntegrationTest {

    private static final String SHOPPER = "it-auth-shopper";

    @Autowired
    private JwtEncoder jwtEncoder;

    private TestRestTemplate shopper;

    @BeforeEach
    void signIn() {
        shopper = asCustomer(SHOPPER);
    }

    @Test
    @DisplayName("login returns a usable token, and the token opens a protected endpoint")
    void loginReturnsAWorkingToken() {
        // Given: an account that exists
        rest.postForEntity(
                "/api/customers/register",
                new com.ecomdemo.customer.dto.RegisterRequest(
                        "it-auth-fresh", IT_PASSWORD, "Fresh account"),
                CustomerResponse.class);

        // When
        ResponseEntity<TokenResponse> response = rest.postForEntity(
                "/api/auth/login", new LoginRequest("it-auth-fresh", IT_PASSWORD), TokenResponse.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TokenResponse token = response.getBody();
        assertThat(token).isNotNull();
        assertThat(token.tokenType()).isEqualTo("Bearer");
        assertThat(token.accessToken().split("\\.")).as("header.payload.signature").hasSize(3);
        assertThat(token.expiresIn()).as("short-lived, because it cannot be revoked").isPositive();
        assertThat(token.expiresAt()).isAfter(Instant.now());

        // And it works: this is a request the same client was refused before logging in
        ResponseEntity<CustomerResponse> profile =
                withToken(token.accessToken()).getForEntity("/api/customers/me", CustomerResponse.class);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getBody()).isNotNull();
        assertThat(profile.getBody().username()).isEqualTo("it-auth-fresh");
    }

    @Test
    @DisplayName("the payload is readable without any key, so nothing secret may go in it")
    void theTokenIsEncodedNotEncrypted() {
        String token = login(SHOPPER, IT_PASSWORD);

        String payload = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertThat(payload)
                .contains("\"sub\":\"" + SHOPPER + "\"")
                .contains("\"roles\":[\"CUSTOMER\"]")
                .doesNotContain(IT_PASSWORD);
    }

    @Test
    @DisplayName("a tampered token is rejected: the signature is what makes claims trustworthy")
    void aTamperedTokenIsRejected() {
        // Given a genuine CUSTOMER token, rewritten to claim ADMIN
        String[] parts = login(SHOPPER, IT_PASSWORD).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"CUSTOMER\"", "\"ADMIN\"");
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        // When: the forged token is used for something only an ADMIN may do
        ResponseEntity<ApiError> response = withToken(forged)
                .getForEntity("/api/customers/me", ApiError.class);

        // Then: 401, not 403. It never became an identity at all — the signature no longer
        // covers the payload, so the filter rejected the token before any rule was consulted.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(401);
        assertThat(response.getBody().message()).contains("invalid or has expired");
    }

    @Test
    @DisplayName("an expired token is rejected, even though it is correctly signed")
    void anExpiredTokenIsRejected() {
        // Given a token this application really signed, whose whole lifetime is in the past
        Instant issuedAt = Instant.now().minus(Duration.ofHours(2));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ecomdemo")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
                .subject(SHOPPER)
                .claim(TokenClaims.USER_ID, 1L)
                .claim(TokenClaims.ROLES, List.of("CUSTOMER"))
                .build();
        String expired = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        // When
        ResponseEntity<ApiError> response =
                withToken(expired).getForEntity("/api/customers/me", ApiError.class);

        // Then: a valid signature is not on its own a reason to accept a token. Expiry is the
        // only thing that takes one out of circulation, since nothing consults a database.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("invalid or has expired");
    }

    @Test
    @DisplayName("nonsense in the Authorization header is a 401, not a 500")
    void aMalformedTokenIsRejected() {
        ResponseEntity<ApiError> response =
                withToken("not-even-a-jwt").getForEntity("/api/customers/me", ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(401);
    }

    @Test
    @DisplayName("no token at all is a 401 that says how to get one")
    void noTokenIsRejectedWithUsefulAdvice() {
        ResponseEntity<ApiError> response = rest.getForEntity("/api/customers/me", ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        // Different wording from the invalid-token case on purpose: this caller has never
        // identified itself, and telling it where to log in is useful rather than revealing.
        assertThat(response.getBody().message()).contains("/api/auth/login");
        assertThat(response.getBody().message()).doesNotContain("expired");
    }

    @Test
    @DisplayName("a wrong password and an unknown username are the same 401, word for word")
    void loginFailuresAreIndistinguishable() {
        ResponseEntity<ApiError> wrongPassword = rest.postForEntity(
                "/api/auth/login", new LoginRequest(SHOPPER, "not-the-password"), ApiError.class);
        ResponseEntity<ApiError> noSuchAccount = rest.postForEntity(
                "/api/auth/login", new LoginRequest("nobody-here-at-all", "not-the-password"), ApiError.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(noSuchAccount.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).isNotNull();
        assertThat(noSuchAccount.getBody()).isNotNull();
        // A difference here would be a username-enumeration oracle: an attacker could find real
        // accounts without ever guessing a password.
        assertThat(wrongPassword.getBody().message()).isEqualTo(noSuchAccount.getBody().message());
    }

    @Test
    @DisplayName("the roles in the token decide what it may do")
    void aCustomerTokenCannotActAsAnAdmin() {
        // Authorization is read from the token's claims, with no database lookup — so this is
        // really a test that the roles claim reached the filter chain intact.
        // Read as String: a 200 here carries a cart, not an ApiError, and asking the client to
        // deserialise one as the other fails on the shape rather than on the status.
        ResponseEntity<String> asShopper = shopper.getForEntity("/api/cart", String.class);
        assertThat(asShopper.getStatusCode()).as("a CUSTOMER may use a cart").isEqualTo(HttpStatus.OK);

        ResponseEntity<ApiError> adminOnCart = asAdmin().getForEntity("/api/cart", ApiError.class);
        assertThat(adminOnCart.getStatusCode()).as("an ADMIN has no cart").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(adminOnCart.getBody()).isNotNull();
        assertThat(adminOnCart.getBody().status()).isEqualTo(403);
    }
}
