package com.ecomdemo.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * customer-service's token signing keys, under {@code ecomdemo.auth} (Phase 33).
 *
 * <p>Each key is an RSA private key (PKCS#8, base64 on one line, or PEM) with an id that becomes the
 * {@code kid} of every token it signs. All of them are PUBLISHED at {@code /oauth2/jwks}; only the
 * active one signs. That split is what makes a rotation safe:
 *
 * <ol>
 *   <li>add the new key as a second entry: it is published, verifiers can already check it;
 *   <li>make it active: new tokens use it, tokens signed with the old key still verify;
 *   <li>after one token lifetime (15 minutes) no old-key token is alive: remove the old entry.
 * </ol>
 *
 * @param keys the key pairs; entries with no private key are skipped (unset environment variables)
 * @param activeKeyId the id of the key that signs; unset means the first key
 */
@ConfigurationProperties(prefix = "ecomdemo.auth")
public record SigningKeyProperties(List<Key> keys, String activeKeyId) {

    public SigningKeyProperties {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    /** One key: its {@code kid}, and the private key it signs with. */
    public record Key(String id, String privateKey) {
    }
}
