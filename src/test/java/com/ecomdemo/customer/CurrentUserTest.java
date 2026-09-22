package com.ecomdemo.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ecomdemo.common.TokenClaims;
import com.ecomdemo.support.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Unit tests for {@link CurrentUser}, which is where Phase 9's change is most visible: the
 * caller's identity now comes out of a verified token instead of a database row read per
 * request.
 */
@ExtendWith(MockitoExtension.class)
class CurrentUserTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CurrentUser currentUser;

    @AfterEach
    void clearTheContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(Object userIdClaim) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .subject("asha")
                .claim(TokenClaims.USER_ID, userIdClaim)
                .claim(TokenClaims.ROLES, List.of("CUSTOMER"))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(
                        jwt, AuthorityUtils.createAuthorityList("ROLE_CUSTOMER"), "asha"));
    }

    @Test
    @DisplayName("the id and username come from the token's claims, with no database read")
    void readsTheClaims() {
        authenticateWith(7L);

        assertThat(currentUser.id()).isEqualTo(7L);
        assertThat(currentUser.username()).isEqualTo("asha");
        // userRepository was never touched — that is the whole point of a stateless token
        org.mockito.Mockito.verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("an id claim that came back as an Integer still reads as a Long")
    void narrowsWhateverNumberTypeTheParserChose() {
        // JSON has one number type, so a claim written as a Long comes back as an Integer for
        // small values and a Long for large ones. Casting to Long directly would work until the
        // users table passed two billion rows, which is the worst kind of bug to ship.
        authenticateWith(7);
        assertThat(currentUser.id()).isEqualTo(7L);

        authenticateWith(3_000_000_000L);
        assertThat(currentUser.id()).isEqualTo(3_000_000_000L);
    }

    @Test
    @DisplayName("require() loads the managed entity the services need")
    void requireReadsTheRow() {
        // The id comes from the token, but the cart and checkout need a real attached entity to
        // hang JPA associations off — so this one method does read, by primary key.
        User asha = TestData.user(7L, "asha", Role.CUSTOMER);
        authenticateWith(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(asha));

        assertThat(currentUser.require()).isSameAs(asha);
    }

    @Test
    @DisplayName("a token with no uid claim is a configuration error, not a client error")
    void rejectsATokenFromSomewhereElse() {
        // A token that verified but carries no uid was issued by something other than this
        // application's login endpoint. Failing loudly beats a NullPointerException later.
        authenticateWith(null);

        assertThatThrownBy(() -> currentUser.id())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("uid");
    }

    @Test
    @DisplayName("no authentication at all is a bug in the rules, and says so")
    void failsLoudlyWithNoToken() {
        assertThatThrownBy(() -> currentUser.id())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No authenticated user on this thread");
    }

    @Test
    @DisplayName("an anonymous authentication is treated as no authentication")
    void failsLoudlyForAnonymous() {
        // Spring Security always puts SOMETHING in the context, so a null check alone is not
        // enough: an anonymous token has a String principal, not a Jwt.
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatThrownBy(() -> currentUser.username()).isInstanceOf(IllegalStateException.class);
    }
}
