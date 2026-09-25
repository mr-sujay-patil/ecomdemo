package com.ecomdemo.clients.customer;

/**
 * What a caller needs from customer-service.
 *
 * <p><strong>Only what the public edge forwards.</strong> There is no "get any account by id" here,
 * and its absence is deliberate — it was absent from {@code CustomerService} for the same reason.
 * Nothing in this system has a legitimate need to read somebody else's profile, so the gateway does
 * not offer the ability and cannot be misused into it.
 *
 * <p>Notice what is NOT here either: no "does this password match", no "is this token valid". The
 * first belongs exclusively to the service that owns the hashes. The second is not a question anyone
 * should ask over a network — every service verifies a token locally, with the shared key, which is
 * the entire point of Phase 9 having made it stateless.
 */
public interface CustomerGateway {

    /** Exchanges credentials for a token. The only call that carries a password. */
    TokenView login(LoginCommand credentials);

    /** Creates an account. Public: a person with no account cannot authenticate as one. */
    CustomerView register(RegisterCommand registration);

    /** The CALLER's own profile, identified by the token this client forwards. */
    CustomerView currentProfile(String bearerToken);

    /** Updates the caller's own profile. */
    CustomerView updateCurrentProfile(String bearerToken, UpdateProfileCommand update);
}
