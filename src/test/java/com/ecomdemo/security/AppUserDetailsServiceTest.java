package com.ecomdemo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ecomdemo.customer.Role;
import com.ecomdemo.customer.UserRepository;
import com.ecomdemo.support.TestData;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** Unit tests for the half of authentication that finds the account. */
@ExtendWith(MockitoExtension.class)
class AppUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AppUserDetailsService service;

    @Test
    void loadUserByUsername_addsTheRolePrefixSpringSecurityExpects() {
        // Given: the database stores "ADMIN", with no prefix
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(TestData.user(2L, "admin", Role.ADMIN)));

        // When
        UserDetails details = service.loadUserByUsername("admin");

        // Then: hasRole("ADMIN") is shorthand for hasAuthority("ROLE_ADMIN"), so the prefix has
        // to be added somewhere. It is added here, and never written to the database — it is a
        // Spring Security convention with no business meaning.
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void loadUserByUsername_carriesTheAccountIdOnThePrincipal() {
        // Given
        when(userRepository.findByUsername("asha"))
                .thenReturn(Optional.of(TestData.user(7L, "asha", Role.CUSTOMER)));

        // When
        UserDetails details = service.loadUserByUsername("asha");

        // Then: the cart and the orders are keyed by user id, so carrying it on the principal
        // saves a lookup by username on every request that touches them
        assertThat(details).isInstanceOf(AppUserDetails.class);
        assertThat(((AppUserDetails) details).getId()).isEqualTo(7L);
    }

    @Test
    void loadUserByUsername_returnsTheStoredHashAsTheCredential() {
        // Given
        when(userRepository.findByUsername("asha"))
                .thenReturn(Optional.of(TestData.user(7L, "asha", Role.CUSTOMER)));

        // When / Then: "password" in UserDetails means "the stored credential to compare
        // against". Nothing ever decodes it; the PasswordEncoder re-hashes the submitted
        // password and compares.
        assertThat(service.loadUserByUsername("asha").getPassword()).isEqualTo("{not-a-real-hash}");
    }

    @Test
    void loadUserByUsername_whenThereIsNoSuchAccount_throws() {
        // Given
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        // When / Then: this exception never reaches the client. Spring Security turns it into
        // the same BadCredentialsException a wrong password produces, so a caller cannot use
        // the API to find out which usernames exist.
        assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
