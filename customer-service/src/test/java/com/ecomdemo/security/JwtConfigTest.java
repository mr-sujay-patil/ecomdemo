package com.ecomdemo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.auth.TokenService;
import com.ecomdemo.customer.Role;
import com.ecomdemo.jwt.JwtKeyConfig;
import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.shared.TokenClaims;
import com.ecomdemo.support.TestData;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * Guards how customer-service gets its signing keys, and proves a token it signs is one it accepts
 * back (Phase 33: RS256, a {@code kid}, several published keys).
 *
 * <p>{@link ApplicationContextRunner} builds a tiny context with only {@link JwtConfig} and the
 * shared {@link JwtKeyConfig} (which supplies {@link JwtProperties}), so these are configuration
 * assertions: nothing starts a web server or a database.
 */
class JwtConfigTest {

    private static final String KEY_1 = pkcs8();
    private static final String KEY_2 = pkcs8();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtConfig.class, JwtKeyConfig.class)
            .withPropertyValues("ecomdemo.jwt.issuer=ecomdemo", "ecomdemo.jwt.expiry=15m");

    /** A fresh RSA private key as PKCS#8 base64 on one line: what JWT_SIGNING_KEY holds. */
    private static String pkcs8() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String loginToken(org.springframework.context.ApplicationContext context) {
        TokenService tokens = new TokenService(context.getBean(JwtEncoder.class),
                context.getBean(JwtProperties.class), context.getBean(SigningKeys.class));
        return tokens.issueFor(new AppUserDetails(TestData.user(7L, "asha", Role.CUSTOMER))).accessToken();
    }

    @Test
    @DisplayName("a token this service signs is one it accepts back, and it names its key")
    void roundTripsItsOwnToken() {
        runner.withPropertyValues("ecomdemo.auth.keys[0].id=key-1", "ecomdemo.auth.keys[0].private-key=" + KEY_1)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    String token = loginToken(context);

                    // Encoder, decoder and issuer validator agree; a mismatch anywhere among them
                    // would otherwise only show up as "every request is 401" at runtime.
                    var decoded = context.getBean(JwtDecoder.class).decode(token);
                    assertThat(decoded.getSubject()).isEqualTo("asha");
                    assertThat(decoded.getClaimAsString("iss")).isEqualTo("ecomdemo");
                    assertThat(decoded.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", "key-1");
                });
    }

    @Test
    @DisplayName("the published key set holds public halves only")
    @SuppressWarnings("unchecked")
    void publishesOnlyPublicKeys() {
        runner.withPropertyValues("ecomdemo.auth.keys[0].id=key-1", "ecomdemo.auth.keys[0].private-key=" + KEY_1)
                .run(context -> {
                    List<Map<String, Object>> keys = (List<Map<String, Object>>)
                            context.getBean(SigningKeys.class).publicJwkSet().get("keys");

                    assertThat(keys).singleElement().satisfies(key -> {
                        assertThat(key).containsEntry("kid", "key-1").containsEntry("kty", "RSA")
                                .containsKeys("n", "e");
                        // "d" is the private exponent: publishing it would hand out the signing key.
                        assertThat(key).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
                    });
                });
    }

    @Test
    @DisplayName("during a rotation both keys are published, the new one signs, and old tokens still verify")
    void rotationKeepsOldTokensValid() {
        runner.withPropertyValues("ecomdemo.auth.keys[0].id=key-1", "ecomdemo.auth.keys[0].private-key=" + KEY_1)
                .run(before -> {
                    String oldToken = loginToken(before);

                    runner.withPropertyValues(
                                    "ecomdemo.auth.keys[0].id=key-1", "ecomdemo.auth.keys[0].private-key=" + KEY_1,
                                    "ecomdemo.auth.keys[1].id=key-2", "ecomdemo.auth.keys[1].private-key=" + KEY_2,
                                    "ecomdemo.auth.active-key-id=key-2")
                            .run(after -> {
                                String newToken = loginToken(after);
                                JwtDecoder decoder = after.getBean(JwtDecoder.class);

                                assertThat(decoder.decode(newToken).getHeaders()).containsEntry("kid", "key-2");
                                assertThat(decoder.decode(oldToken).getSubject())
                                        .as("a token signed before the rotation").isEqualTo("asha");
                            });
                });
    }

    @Test
    @DisplayName("a token from another issuer is rejected, even signed with our key")
    void refusesAForeignIssuer() {
        runner.withPropertyValues("ecomdemo.auth.keys[0].id=key-1", "ecomdemo.auth.keys[0].private-key=" + KEY_1)
                .run(context -> {
                    TokenService elsewhere = new TokenService(context.getBean(JwtEncoder.class),
                            new JwtProperties("some-other-system", Duration.ofMinutes(15), null),
                            context.getBean(SigningKeys.class));
                    String token = elsewhere.issueFor(new AppUserDetails(TestData.user(2L, "admin", Role.ADMIN)))
                            .accessToken();

                    // A valid signature is not on its own a reason to trust a token.
                    JwtDecoder decoder = context.getBean(JwtDecoder.class);
                    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
                });
    }

    @Test
    @DisplayName("an expired token is rejected")
    void refusesAnExpiredToken() {
        runner.withPropertyValues("ecomdemo.auth.keys[0].id=key-1", "ecomdemo.auth.keys[0].private-key=" + KEY_1)
                .run(context -> {
                    // Two hours ago, comfortably beyond the decoder's one-minute clock-skew allowance.
                    Instant issuedAt = Instant.now().minus(Duration.ofHours(2));
                    JwtClaimsSet expired = JwtClaimsSet.builder()
                            .issuer("ecomdemo").issuedAt(issuedAt).expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
                            .subject("customer").claim(TokenClaims.USER_ID, 1L)
                            .claim(TokenClaims.ROLES, List.of("CUSTOMER")).build();
                    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("key-1").build();
                    String token = context.getBean(JwtEncoder.class)
                            .encode(JwtEncoderParameters.from(header, expired)).getTokenValue();

                    // Expiry is the ONLY thing that takes a token out of circulation.
                    JwtDecoder decoder = context.getBean(JwtDecoder.class);
                    assertThatThrownBy(() -> decoder.decode(token))
                            .isInstanceOf(JwtValidationException.class)
                            .hasMessageContaining("exp");
                });
    }

    @Test
    @DisplayName("a malformed key is refused at startup, with the command that makes a good one")
    void refusesAMalformedKey() {
        runner.withPropertyValues("ecomdemo.auth.keys[0].id=key-1", "ecomdemo.auth.keys[0].private-key=not-a-key")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("openssl genpkey");
                });
    }

    @Test
    @DisplayName("an active key id that names no configured key is refused at startup")
    void refusesAnUnknownActiveKey() {
        runner.withPropertyValues("ecomdemo.auth.keys[0].id=key-1", "ecomdemo.auth.keys[0].private-key=" + KEY_1,
                        "ecomdemo.auth.active-key-id=key-9")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("key-9");
                });
    }

    @Test
    @DisplayName("with no key configured the service still starts, on a generated key")
    void generatesAKeyWhenNoneIsSet() {
        runner.run(context -> {
            // No setup step and no key in Git. SigningKeys logs a WARN that this key dies with
            // the process, so every token issued before a restart stops working after it.
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SigningKeys.class).active().getKeyID()).startsWith("ephemeral-");
            assertThat(context.getBean(JwtDecoder.class).decode(loginToken(context)).getSubject()).isEqualTo("asha");
        });
    }
}
