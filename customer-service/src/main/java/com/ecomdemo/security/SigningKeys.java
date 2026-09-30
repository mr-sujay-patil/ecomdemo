package com.ecomdemo.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The RSA keys customer-service signs tokens with, and the only place in the system that holds a
 * private key (Phase 33).
 *
 * <p><strong>With no key configured it generates one, and says so.</strong> Tests and a quick
 * {@code ./mvnw spring-boot:run} work with no setup. The cost is named in the warning: the key dies
 * with the process, so a restart invalidates every token, people's and services' alike. Unlike the
 * old random HS256 fallback this no longer breaks OTHER services: they fetch whatever key this one
 * publishes. compose and Kubernetes set a persistent key ({@code JWT_SIGNING_KEY}).
 */
public final class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private final List<RSAKey> keys;
    private final RSAKey active;

    SigningKeys(List<RSAKey> keys, String activeKeyId) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("at least one signing key is required");
        }
        this.keys = List.copyOf(keys);
        this.active = activeKeyId == null || activeKeyId.isBlank()
                ? keys.getFirst()
                : keys.stream().filter(key -> key.getKeyID().equals(activeKeyId)).findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "ecomdemo.auth.active-key-id '%s' names none of the configured keys %s"
                                        .formatted(activeKeyId, keys.stream().map(JWK::getKeyID).toList())));
    }

    /** Builds the key set from configuration, or generates a throwaway key when none is set. */
    public static SigningKeys from(SigningKeyProperties properties) {
        List<RSAKey> keys = new ArrayList<>();
        for (SigningKeyProperties.Key key : properties.keys()) {
            if (key.privateKey() == null || key.privateKey().isBlank()) {
                continue;
            }
            if (key.id() == null || key.id().isBlank()) {
                throw new IllegalStateException("every ecomdemo.auth.keys entry needs an id (the token's kid)");
            }
            keys.add(parse(key.id(), key.privateKey()));
        }
        if (keys.isEmpty()) {
            RSAKey generated = generate("ephemeral-" + UUID.randomUUID());
            log.warn("""
                    No signing key is configured (JWT_SIGNING_KEY), so a random RSA key was generated \
                    for this run, kid {}. Everything works, but EVERY TOKEN BECOMES INVALID WHEN THIS \
                    SERVICE RESTARTS, including the service tokens other services have cached. Set \
                    JWT_SIGNING_KEY (see .env.example) for a key that survives restarts.""",
                    generated.getKeyID());
            keys.add(generated);
        }
        return new SigningKeys(keys, properties.activeKeyId());
    }

    /** The key that signs new tokens. */
    public RSAKey active() {
        return active;
    }

    /** Every key, private halves included: what the encoder selects from. */
    public JWKSet all() {
        return new JWKSet(List.<JWK>copyOf(keys));
    }

    /** What {@code /oauth2/jwks} publishes: the PUBLIC halves of every key, and nothing else. */
    public Map<String, Object> publicJwkSet() {
        return all().toPublicJWKSet().toJSONObject();
    }

    static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).keyUse(KeyUse.SIGNATURE).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads a PKCS#8 RSA private key, as one line of base64 (what fits in an environment variable) or
     * as PEM, and derives its public half: an RSA private key in CRT form carries the modulus and the
     * public exponent, so nobody has to configure the public key separately.
     */
    static RSAKey parse(String keyId, String text) {
        String base64 = text.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).keyUse(KeyUse.SIGNATURE).build();
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException(("signing key '%s' is not a PKCS#8 RSA private key. Generate one with: "
                    + "openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 | "
                    + "openssl pkcs8 -topk8 -nocrypt -outform DER | base64 -w0").formatted(keyId), e);
        }
    }
}
