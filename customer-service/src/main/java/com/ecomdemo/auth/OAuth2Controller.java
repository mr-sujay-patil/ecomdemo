package com.ecomdemo.auth;

import com.ecomdemo.security.SigningKeys;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two OAuth2 endpoints the rest of the system relies on, and only those two (Phase 33).
 *
 * <ul>
 *   <li>{@code GET /oauth2/jwks}: the PUBLIC keys every other service verifies tokens with.
 *   <li>{@code POST /oauth2/token}: the <em>client credentials</em> grant, where a service
 *       authenticates as itself and gets a token with its scopes (RFC 6749, section 4.4).
 * </ul>
 *
 * <p><strong>Deliberately minimal, not an authorization server.</strong> No authorization code flow,
 * no refresh tokens, no discovery document: people still log in at {@code /api/auth/login}. A full
 * OAuth2 server (Spring Authorization Server) was considered and not chosen; these two endpoints are
 * the part the system needs, small enough to read in one sitting (see {@code docs/decisions.md}).
 *
 * <p>Neither is reachable through the gateway, which routes {@code /api/**} and the API documents
 * only: services call them on the internal network. Hidden from the OpenAPI document for the same
 * reason: they are not part of the API a client uses.
 */
@Hidden
@RestController
class OAuth2Controller {

    private static final String CLIENT_CREDENTIALS = "client_credentials";

    private final SigningKeys keys;
    private final ServiceClientProperties clients;
    private final TokenService tokens;

    OAuth2Controller(SigningKeys keys, ServiceClientProperties clients, TokenService tokens) {
        this.keys = keys;
        this.clients = clients;
        this.tokens = tokens;
    }

    /**
     * Public keys only. Cacheable for five minutes: a verifier that meets an unknown {@code kid}
     * fetches again anyway, so a longer cache never blocks a rotation.
     */
    @GetMapping(path = "/oauth2/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
                .body(keys.publicJwkSet());
    }

    @PostMapping(path = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> token(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(value = "grant_type", required = false) String grantType,
            @RequestParam(value = "scope", required = false) String scope) {
        String[] credentials = basicCredentials(authorization);
        ServiceClientProperties.Client client = credentials == null ? null : clients.serviceClients().get(credentials[0]);
        if (client == null || !matches(client.secret(), credentials[1])) {
            // One answer for "no such client" and "wrong secret", so the endpoint cannot be used to
            // find out which client ids exist.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"oauth2\"")
                    .body(error("invalid_client", "Unknown client or wrong secret."));
        }
        if (!CLIENT_CREDENTIALS.equals(grantType)) {
            return ResponseEntity.badRequest().body(error("unsupported_grant_type",
                    "Only client_credentials is supported here; people log in at POST /api/auth/login."));
        }
        List<String> granted = client.scopes();
        if (scope != null && !scope.isBlank()) {
            List<String> requested = Arrays.stream(scope.trim().split("\\s+")).toList();
            if (!granted.containsAll(requested)) {
                return ResponseEntity.badRequest().body(error("invalid_scope",
                        "This client may ask only for: " + String.join(" ", granted)));
            }
            granted = requested;
        }
        TokenService.IssuedServiceToken issued = tokens.issueForService(credentials[0], granted);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", issued.value());
        body.put("token_type", "Bearer");
        body.put("expires_in", issued.expiresInSeconds());
        body.put("scope", issued.scope());
        // RFC 6749 5.1: a token response must not be cached anywhere on the way.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** {@code Basic base64(id:secret)} → {id, secret}, or null when absent or malformed. */
    private static String[] basicCredentials(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon <= 0 ? null : new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Constant-time: {@link MessageDigest#isEqual} takes as long for a first-character mismatch as for
     * a last-character one, so response times reveal nothing about how close a guess was. A blank
     * configured secret never matches, which is what disables a client whose variable is unset.
     */
    private static boolean matches(String expected, String presented) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> error(String code, String description) {
        return Map.of("error", code, "error_description", description);
    }
}
