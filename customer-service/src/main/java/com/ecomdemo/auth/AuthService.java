package com.ecomdemo.auth;

import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.security.AppUserDetails;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.ecomdemo.auth.throttle.LoginThrottleService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * The one place a password is still checked.
 *
 * <p>Authentication is delegated rather than done here. The {@link AuthenticationManager} runs
 * the same {@code UserDetailsService} + {@code PasswordEncoder} pair that HTTP Basic used in
 * Phase 8 — the phase changed how a request proves who it is, not how a password is verified,
 * and keeping that machinery untouched is the point. What is new is that the verification now
 * happens once, at login, instead of on every single call.
 *
 * <p>Every failure comes back as the same {@code AuthenticationException} and, from
 * {@code GlobalExceptionHandler}, the same 401 with the same message: a wrong password and a
 * username that does not exist must be indistinguishable, or the endpoint becomes a way to
 * discover which accounts are real.
 *
 * <p>Phase 33: every attempt passes the login throttle first ({@link LoginThrottleService}), and
 * every failure is counted against the username and the client address before the 401 goes out.
 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final LoginThrottleService throttle;

    public AuthService(
            AuthenticationManager authenticationManager, TokenService tokenService, LoginThrottleService throttle) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.throttle = throttle;
    }

    /**
     * @param clientAddress the caller's address, as far as it can be known (see
     *     {@code AuthController}); what the per-client throttle counts against
     */
    public TokenResponse login(LoginRequest request, String clientAddress) {
        throttle.checkAllowed(request.username(), clientAddress);
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        } catch (AuthenticationException failed) {
            throttle.recordFailure(request.username(), clientAddress);
            throw failed;
        }
        throttle.recordSuccess(request.username());
        return tokenService.issueFor((AppUserDetails) authentication.getPrincipal());
    }
}
