package com.ecomdemo.clients.customer;

import com.ecomdemo.shared.ConflictException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link CustomerGateway} over HTTP.
 *
 * <h2>The one client that forwards a credential</h2>
 *
 * <p>Every other client in this package authenticates as the SERVICE. This one has to pass a
 * password through on login, and a caller's own bearer token through on a profile read — because
 * those two operations are about the human, and a service identity cannot stand in for one.
 *
 * <p><strong>That is a real cost of proxying rather than having a gateway.</strong> The application
 * sees a plaintext password in transit on every login. It does not store it, log it, or keep it
 * beyond the call, but it is in that process's memory, which it would not be if the browser talked to
 * customer-service directly through an API gateway. Phase 21 is the API gateway, and this is one of
 * the things it fixes. Recorded in {@code docs/decisions.md}.
 *
 * <p>The status mapping is what earns this class its existence, as with the other clients: a remote
 * {@code 401} becomes {@code BadCredentialsException} so the edge answers a failed login the way it
 * always did, and a {@code 409} becomes {@code ConflictException} so "that username is taken" reads
 * the same to a client whichever service decided it.
 */
@Component
public class CustomerClient implements CustomerGateway {

    private final RestClient rest;

    CustomerClient(RestClient customerRestClient) {
        this.rest = customerRestClient;
    }

    @Override
    public TokenView login(LoginCommand credentials) {
        return rest.post()
                .uri("/api/auth/login")
                .body(credentials)
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value(),
                        (request, response) -> {
                            // Deliberately NOT echoing which half was wrong. Telling a caller that the
                            // username exists but the password does not is an account enumeration
                            // oracle, and customer-service is careful about it - so is this.
                            throw new BadCredentialsException("Invalid username or password");
                        })
                .body(TokenView.class);
    }

    @Override
    public CustomerView register(RegisterCommand registration) {
        return rest.post()
                .uri("/api/customers/register")
                .body(registration)
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(),
                        (request, response) -> {
                            throw new ConflictException(
                                    "Username %s is already taken".formatted(registration.username()));
                        })
                .body(CustomerView.class);
    }

    @Override
    public CustomerView currentProfile(String bearerToken) {
        return rest.get()
                .uri("/api/customers/me")
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .retrieve()
                .body(CustomerView.class);
    }

    @Override
    public CustomerView updateCurrentProfile(String bearerToken, UpdateProfileCommand update) {
        return rest.put()
                .uri("/api/customers/me")
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .body(update)
                .retrieve()
                .body(CustomerView.class);
    }
}
