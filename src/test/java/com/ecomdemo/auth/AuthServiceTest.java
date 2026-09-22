package com.ecomdemo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.customer.Role;
import com.ecomdemo.security.AppUserDetails;
import com.ecomdemo.support.TestData;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** Unit tests for {@link AuthService}: the one place a password is still checked. */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private TokenService tokenService;

    @InjectMocks
    private AuthService authService;

    @Captor
    private ArgumentCaptor<Authentication> authenticationCaptor;

    private static final LoginRequest REQUEST = new LoginRequest("asha", "correct-horse-battery-staple");

    @Test
    @DisplayName("a correct password is exchanged for a token")
    void issuesATokenForTheAuthenticatedAccount() {
        // Given
        AppUserDetails asha = new AppUserDetails(TestData.user(7L, "asha", Role.CUSTOMER));
        when(authenticationManager.authenticate(any()))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(asha, null, asha.getAuthorities()));
        TokenResponse expected = TokenResponse.bearer("a.b.c", Instant.now(), Instant.now().plusSeconds(900));
        when(tokenService.issueFor(asha)).thenReturn(expected);

        // When
        TokenResponse actual = authService.login(REQUEST);

        // Then
        assertThat(actual).isSameAs(expected);
        // The password reaches the AuthenticationManager and stops there: this service never
        // compares anything itself, and never holds the plain text in a field.
        verify(authenticationManager).authenticate(authenticationCaptor.capture());
        assertThat(authenticationCaptor.getValue().getPrincipal()).isEqualTo("asha");
        assertThat(authenticationCaptor.getValue().getCredentials()).isEqualTo("correct-horse-battery-staple");
        assertThat(authenticationCaptor.getValue().isAuthenticated())
                .as("the token handed to the manager is a request to authenticate, not a claim to be")
                .isFalse();
    }

    @Test
    @DisplayName("a wrong password issues nothing")
    void doesNotIssueATokenForBadCredentials() {
        // Given
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        // When / Then: the exception is left to propagate. GlobalExceptionHandler turns every
        // AuthenticationException into one identical 401, which is what keeps a wrong password
        // and an unknown username indistinguishable.
        assertThatThrownBy(() -> authService.login(REQUEST)).isInstanceOf(BadCredentialsException.class);
        verify(tokenService, never()).issueFor(any());
    }

    @Test
    @DisplayName("an unknown username issues nothing either")
    void doesNotIssueATokenForAnUnknownAccount() {
        // Given
        when(authenticationManager.authenticate(any()))
                .thenThrow(new UsernameNotFoundException("No account named nobody"));

        // When / Then: a different exception internally, the same 401 to the client.
        // The request is built OUTSIDE the lambda so that only the call under test can throw —
        // otherwise a constructor that started validating its arguments would satisfy this
        // assertion without login() ever being reached.
        LoginRequest unknownAccount = new LoginRequest("nobody", "whatever-password");
        assertThatThrownBy(() -> authService.login(unknownAccount))
                .isInstanceOf(UsernameNotFoundException.class);
        verify(tokenService, never()).issueFor(any());
    }

    @Test
    @DisplayName("the request never prints the password, even in a log line")
    void loginRequestHidesThePassword() {
        // A record's generated toString() includes every component, and a DTO is exactly the
        // kind of object that ends up in a debug log or an exception message.
        assertThat(REQUEST.toString()).contains("asha").doesNotContain("correct-horse-battery-staple");
    }
}
