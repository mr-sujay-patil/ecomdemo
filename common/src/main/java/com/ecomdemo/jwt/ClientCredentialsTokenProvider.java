package com.ecomdemo.jwt;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Gets this service's token from customer-service with the OAuth2 <em>client credentials</em> grant,
 * and keeps it until shortly before it expires (Phase 33).
 *
 * <p><strong>Why ask rather than mint.</strong> Until Phase 33 each service signed its own service
 * token with the shared HS256 secret, so each could equally have signed an administrator's. Now only
 * customer-service holds a signing key. A service proves who it is with its own client id and secret
 * (HTTP Basic on {@code POST /oauth2/token}), and gets back a token carrying the scopes
 * customer-service has on record for it, and nothing more.
 *
 * <p><strong>Why cache it.</strong> The token is good for its whole lifetime (15 minutes), so asking
 * per request would add a network hop to every outbound call for nothing. It is replaced a minute
 * before it expires: a token that passes the check here must still be valid when it arrives, after
 * the network hop and on another machine's clock.
 *
 * <p>Thread safety is one volatile reference to an immutable record. Two threads that both find the
 * token stale will both fetch one and one will win: fetching is idempotent, and the loser's token is
 * simply discarded, which is cheaper than holding a lock across an HTTP call.
 */
public class ClientCredentialsTokenProvider implements ServiceTokenProvider {

    /** Replace the token this long before it expires. See the class comment. */
    static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final RestClient client;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    private volatile CachedToken cached = null;

    public ClientCredentialsTokenProvider(String tokenUri, String clientId, String clientSecret) {
        this(defaultClient(), tokenUri, clientId, clientSecret, Clock.systemUTC());
    }

    ClientCredentialsTokenProvider(
            RestClient client, String tokenUri, String clientId, String clientSecret, Clock clock) {
        this.client = client;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    /**
     * Short timeouts: a service that cannot reach customer-service should fail its call quickly and
     * visibly, not hang every outbound request behind it.
     */
    private static RestClient defaultClient() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String token() {
        CachedToken current = cached;
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.renewAt())) {
            return current.value();
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            // Said at the first outbound call, not at startup: a service that never calls another
            // one should not refuse to start over a secret it does not use.
            throw new IllegalStateException(("%s has no client secret, so it cannot get a service "
                    + "token from %s. Set SERVICE_CLIENT_SECRET for it (and the matching "
                    + "*_CLIENT_SECRET in customer-service); see .env.example.").formatted(clientId, tokenUri));
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        Map<String, Object> response = client.post()
                .uri(tokenUri)
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (response == null || !(response.get("access_token") instanceof String value)) {
            throw new IllegalStateException("customer-service answered " + tokenUri + " without an access_token");
        }
        long expiresIn = response.get("expires_in") instanceof Number seconds ? seconds.longValue() : 0;
        cached = new CachedToken(value, now.plusSeconds(expiresIn).minus(REFRESH_MARGIN));
        return value;
    }

    private record CachedToken(String value, Instant renewAt) {
    }
}
