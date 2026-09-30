package com.ecomdemo.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.shared.TokenClaims;
import com.ecomdemo.support.CustomerIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The two OAuth2 endpoints, over a real socket (Phase 33): the published keys, and service tokens
 * from the client credentials grant. The clients come from {@code application-it.properties}.
 */
@DisplayName("OAuth2: JWKS and service tokens")
class OAuth2ApiIT extends CustomerIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private ResponseEntity<Map<String, Object>> token(String clientId, String secret, String grantType, String scope) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (clientId != null) {
            headers.setBasicAuth(clientId, secret);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        if (scope != null) {
            form.add("scope", scope);
        }
        return rest.exchange("/oauth2/token", HttpMethod.POST, new HttpEntity<>(form, headers), JSON);
    }

    @Test
    @DisplayName("a service with the right secret gets a token carrying its scopes, verifiable with the published keys")
    void issuesAScopedServiceToken() {
        ResponseEntity<Map<String, Object>> response =
                token("catalog-service", "it-catalog-secret", "client_credentials", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("token_type", "Bearer").containsEntry("scope", "inventory:read")
                .containsEntry("expires_in", 900);

        // Verified exactly as every other service does it: with the PUBLIC keys from /oauth2/jwks.
        String base = rest.getRootUri();
        JwtDecoder theirs = NimbusJwtDecoder.withJwkSetUri(base + "/oauth2/jwks").build();
        var jwt = theirs.decode((String) body.get("access_token"));
        assertThat(jwt.getSubject()).isEqualTo("catalog-service");
        assertThat(jwt.getClaimAsString(TokenClaims.SCOPE)).isEqualTo("inventory:read");
        assertThat(jwt.getClaimAsStringList(TokenClaims.ROLES)).isNull();
    }

    @Test
    @DisplayName("a wrong secret, an unknown client and a client with no secret all get the same 401")
    void refusesBadClientsAlike() {
        for (ResponseEntity<Map<String, Object>> response : List.of(
                token("catalog-service", "wrong", "client_credentials", null),
                token("no-such-service", "it-catalog-secret", "client_credentials", null),
                token("gateway-service", "", "client_credentials", null),
                token(null, null, "client_credentials", null))) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).containsEntry("error", "invalid_client");
        }
    }

    @Test
    @DisplayName("a scope the client was never given is refused, not quietly dropped")
    void refusesAScopeBeyondTheClients() {
        ResponseEntity<Map<String, Object>> response =
                token("catalog-service", "it-catalog-secret", "client_credentials", "inventory:read inventory:write");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "invalid_scope");
    }

    @Test
    @DisplayName("only the client credentials grant is offered; people log in at /api/auth/login")
    void refusesOtherGrants() {
        ResponseEntity<Map<String, Object>> response =
                token("catalog-service", "it-catalog-secret", "password", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "unsupported_grant_type");
    }

    @Test
    @DisplayName("the JWKS endpoint is public and publishes no private key material")
    @SuppressWarnings("unchecked")
    void publishesPublicKeysOnly() {
        ResponseEntity<Map<String, Object>> response =
                rest.exchange("/oauth2/jwks", HttpMethod.GET, HttpEntity.EMPTY, JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> keys = (List<Map<String, Object>>) response.getBody().get("keys");
        assertThat(keys).isNotEmpty().allSatisfy(key -> {
            assertThat(key).containsKeys("kid", "n", "e").containsEntry("kty", "RSA");
            assertThat(key).doesNotContainKeys("d", "p", "q");
        });
    }
}
