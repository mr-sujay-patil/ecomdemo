package com.ecomdemo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.auth.TokenService;
import com.ecomdemo.customer.Role;
import com.ecomdemo.support.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * Guards how the signing key is obtained, and proves a token this application mints is one it
 * will accept back.
 *
 * <p>{@link ApplicationContextRunner} builds a tiny context containing only {@link JwtConfig},
 * so these are configuration assertions: nothing starts a web server or a database. It is the
 * same tool {@code DatasourceConfigurationTest} uses for the profile settings.
 */
class JwtConfigTest {

    private static final String VALID_KEY = "a-test-signing-key-of-32-bytes!!";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtConfig.class)
            .withPropertyValues("ecomdemo.jwt.issuer=ecomdemo", "ecomdemo.jwt.expiry=15m");

    @Test
    @DisplayName("a token this application signs is one it accepts back")
    void roundTripsItsOwnToken() {
        runner.withPropertyValues("ecomdemo.jwt.secret=" + VALID_KEY).run(context -> {
            assertThat(context).hasNotFailed();
            TokenService tokenService = new TokenService(
                    context.getBean(JwtEncoder.class), context.getBean(JwtProperties.class));

            String token = tokenService
                    .issueFor(new AppUserDetails(TestData.user(7L, "asha", Role.CUSTOMER)))
                    .accessToken();

            // This is the assertion that matters most in the file: it proves the encoder, the
            // decoder and the issuer validator agree. A mismatch anywhere among them would
            // otherwise only show up as "every request is 401" at runtime.
            var decoded = context.getBean(JwtDecoder.class).decode(token);
            assertThat(decoded.getSubject()).isEqualTo("asha");
            assertThat(decoded.getClaimAsString("iss")).isEqualTo("ecomdemo");
        });
    }

    @Test
    @DisplayName("a token from another issuer is rejected, even signed with our key")
    void refusesAForeignIssuer() {
        runner.withPropertyValues("ecomdemo.jwt.secret=" + VALID_KEY).run(context -> {
            // Given a token signed with THIS key but claiming to come from somewhere else — the
            // shape of a leaked-key incident, or of a token borrowed from a sibling environment
            TokenService elsewhere = new TokenService(
                    context.getBean(JwtEncoder.class),
                    new JwtProperties(VALID_KEY, "some-other-system", Duration.ofMinutes(15)));
            String token = elsewhere
                    .issueFor(new AppUserDetails(TestData.user(2L, "admin", Role.ADMIN)))
                    .accessToken();

            // Then: a valid signature is not on its own a reason to trust a token.
            // The bean is looked up outside the lambda so that only decode() can throw.
            JwtDecoder decoder = context.getBean(JwtDecoder.class);
            assertThatThrownBy(() -> decoder.decode(token))
                    .isInstanceOf(JwtValidationException.class);
        });
    }

    @Test
    @DisplayName("an expired token is rejected")
    void refusesAnExpiredToken() {
        runner.withPropertyValues("ecomdemo.jwt.secret=" + VALID_KEY).run(context -> {
            // Given a correctly signed token whose whole lifetime is in the past. It is built
            // here rather than through TokenService because that class can only ever mint a
            // token starting now — and waiting for a real one to expire would mean waiting out
            // the decoder's clock-skew allowance as well, which is a minute the suite should not
            // spend. Two hours ago is comfortably beyond any skew.
            Instant issuedAt = Instant.now().minus(Duration.ofHours(2));
            JwtClaimsSet expired = JwtClaimsSet.builder()
                    .issuer("ecomdemo")
                    .issuedAt(issuedAt)
                    .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
                    .subject("customer")
                    .claim(JwtConfig.Claims.USER_ID, 1L)
                    .claim(JwtConfig.Claims.ROLES, List.of("CUSTOMER"))
                    .build();
            String token = context.getBean(JwtEncoder.class)
                    .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), expired))
                    .getTokenValue();

            // Then: expiry is checked by the decoder, not by anything we wrote — and because
            // nothing consults a database, it is the ONLY thing that ever takes a token out of
            // circulation. A signature that verifies is not enough.
            JwtDecoder decoder = context.getBean(JwtDecoder.class);
            assertThatThrownBy(() -> decoder.decode(token))
                    .isInstanceOf(JwtValidationException.class)
                    .hasMessageContaining("exp");
        });
    }

    @Test
    @DisplayName("a key shorter than 256 bits is refused at startup, not padded")
    void refusesAWeakKey() {
        runner.withPropertyValues("ecomdemo.jwt.secret=too-short").run(context -> {
            // Failing to start is the correct outcome. Quietly padding or hashing a short secret
            // would leave an application running with a weaker signature than its configuration
            // claims, and nothing would ever say so.
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("at least 32");
        });
    }

    @Test
    @DisplayName("with no key configured the application still starts, on a generated one")
    void generatesAKeyWhenNoneIsSet() {
        runner.withPropertyValues("ecomdemo.jwt.secret=").run(context -> {
            // No manual setup step, and no secret in Git. The cost is that this key dies with the
            // process, so every token issued before a restart stops working after it — JwtConfig
            // logs a WARN saying exactly that.
            assertThat(context).hasNotFailed();
            SecretKey key = context.getBean(SecretKey.class);
            assertThat(key.getEncoded()).hasSize(32);
            assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        });
    }
}
