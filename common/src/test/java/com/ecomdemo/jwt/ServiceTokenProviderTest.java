package com.ecomdemo.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.shared.TokenClaims;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * What a service's own token has to contain for another service to accept it, and the caching that
 * keeps it from being minted once per outbound call.
 *
 * <p>The decoder here is built the way {@code JwtKeyConfig} builds one, so "accepts" means what it
 * will mean in inventory-service rather than what this test would like it to mean.
 */
class ServiceTokenProviderTest {

    private static final String KEY_TEXT = "a-test-signing-key-of-32-bytes!!";
    private static final SecretKey KEY =
            new SecretKeySpec(KEY_TEXT.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private static final JwtProperties PROPERTIES =
            new JwtProperties(KEY_TEXT, "ecomdemo-test", Duration.ofMinutes(15));

    private final ServiceTokenProvider provider = new ServiceTokenProvider(
            new NimbusJwtEncoder(new ImmutableSecret<>(KEY)), PROPERTIES, "ecomdemo-app");

    private final JwtDecoder decoder =
            NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();

    @Test
    @DisplayName("the token names the calling service and carries the SERVICE role")
    void mintsATokenAnotherServiceWouldAccept() {
        Jwt token = decoder.decode(provider.token());

        assertThat(token.getSubject()).isEqualTo("ecomdemo-app");
        // getClaimAsString, not getIssuer(): the typed accessor insists on a URL, and this
        // system's issuer is a bare name. Worth knowing, because the decoder's issuer VALIDATOR
        // has no such opinion - it compares strings - so the mismatch only ever shows up in a
        // test that reads the claim back.
        assertThat(token.getClaimAsString("iss")).isEqualTo("ecomdemo-test");
        assertThat(token.getClaimAsStringList(TokenClaims.ROLES)).containsExactly(ServiceTokens.ROLE);
        assertThat(token.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("no user id, because there is no user behind the call")
    void claimsNothingAboutAUser() {
        Jwt token = decoder.decode(provider.token());

        // The tempting alternative is to put the id of whoever happened to trigger the call. It
        // would be empty for the anonymous product listing, and worse than empty for the CSV
        // import, whose background thread would attribute every row to whoever uploaded the file
        // hours earlier. An absent claim is honest; a plausible one is a lie in an audit log.
        assertThat(token.getClaimAsString(TokenClaims.USER_ID)).isNull();
    }

    @Test
    @DisplayName("the same token comes back until it nears expiry, rather than one per call")
    void cachesTheToken() {
        assertThat(provider.token()).isEqualTo(provider.token()).isEqualTo(provider.token());
    }

    @Test
    @DisplayName("an expiry shorter than the refresh margin is raised, not honoured")
    void refusesToMintATokenItWouldImmediatelyReplace() {
        // 15 seconds is shorter than the 60-second refresh margin, so every call would find the
        // cached token already stale and mint another - a token per request, which is what the
        // cache exists to avoid. The floor is what stops a plausible-looking property value from
        // quietly turning the cache off; without it this test would see a different token each
        // time and nothing would report it.
        ServiceTokenProvider impatient = new ServiceTokenProvider(
                new NimbusJwtEncoder(new ImmutableSecret<>(KEY)),
                new JwtProperties(KEY_TEXT, "ecomdemo-test", Duration.ofSeconds(15)),
                "ecomdemo-app");

        assertThat(impatient.token()).isEqualTo(impatient.token());
        assertThat(decoder.decode(impatient.token()).getExpiresAt())
                .isAfter(Instant.now().plus(Duration.ofSeconds(90)));
    }

    @Test
    @DisplayName("a null expiry does not become a null pointer at the first outbound call")
    void survivesAnUnsetExpiry() {
        // `ecomdemo.jwt.expiry` has no default in application.properties, so a service that
        // forgets the line binds it as null. That is a configuration mistake, but it should not
        // surface as an NPE inside an interceptor on an unrelated request.
        ServiceTokenProvider unconfigured = new ServiceTokenProvider(
                new NimbusJwtEncoder(new ImmutableSecret<>(KEY)),
                new JwtProperties(KEY_TEXT, "ecomdemo-test", null),
                "ecomdemo-app");

        assertThat(decoder.decode(unconfigured.token()).getExpiresAt()).isAfter(Instant.now());
    }
}
