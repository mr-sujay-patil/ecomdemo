package com.ecomdemo.customer.internal;

import com.ecomdemo.customer.User;
import com.ecomdemo.customer.Role;
import com.ecomdemo.customer.CustomerService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.customer.dto.RegisterRequest;
import com.ecomdemo.customer.dto.UpdateProfileRequest;
import com.ecomdemo.jwt.CurrentUser;
import com.ecomdemo.support.TestData;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link CustomerService}.
 *
 * <p>{@code PasswordEncoder} is a {@code @Mock} rather than a real {@code BCryptPasswordEncoder}
 * for two reasons. Hashing is deliberately slow — a cost-10 BCrypt is tens of milliseconds, and
 * a unit test should not pay that — and, more importantly, a mock lets the test assert the thing
 * that actually matters: that the value handed to the repository is whatever came <em>out</em>
 * of the encoder, and never the string that came in. {@link Registering#hashesThePassword()}
 * pins that down without caring which algorithm did it.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private CurrentUser currentUser;

    @InjectMocks
    private CustomerService customerService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    private static final RegisterRequest REQUEST =
            new RegisterRequest("asha", "correct-horse-battery-staple", "Asha Rao");

    @Nested
    @DisplayName("register")
    class Registering {

        @Test
        void hashesThePassword() {
            // Given
            when(userRepository.existsByUsername("asha")).thenReturn(false);
            when(passwordEncoder.encode("correct-horse-battery-staple")).thenReturn("$2a$10$hashed");
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

            // When
            customerService.register(REQUEST);

            // Then: what reaches the database is the encoder's output, and the password the
            // client typed appears nowhere on the entity
            verify(userRepository).saveAndFlush(userCaptor.capture());
            assertThat(userCaptor.getValue().getPassword()).isEqualTo("$2a$10$hashed");
            assertThat(userCaptor.getValue().getPassword()).isNotEqualTo("correct-horse-battery-staple");
        }

        @Test
        void alwaysCreatesACustomerNeverAnAdmin() {
            // Given
            when(userRepository.existsByUsername("asha")).thenReturn(false);
            when(passwordEncoder.encode(any())).thenReturn("$2a$10$hashed");
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

            // When
            CustomerResponse created = customerService.register(REQUEST);

            // Then: the role is not a parameter of the request and cannot be influenced by it.
            // An anonymous endpoint that accepted a role would hand out administrator accounts.
            verify(userRepository).saveAndFlush(userCaptor.capture());
            assertThat(userCaptor.getValue().getRole()).isEqualTo(Role.CUSTOMER);
            assertThat(created.role()).isEqualTo(Role.CUSTOMER);
        }

        @Test
        void neverReturnsThePasswordOrItsHash() {
            // Given
            when(userRepository.existsByUsername("asha")).thenReturn(false);
            when(passwordEncoder.encode(any())).thenReturn("$2a$10$hashed");
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

            // When
            CustomerResponse created = customerService.register(REQUEST);

            // Then: CustomerResponse has no password component at all, so this is really a
            // statement about the DTO — which is the point. The absence is the feature.
            assertThat(created.toString()).doesNotContain("correct-horse-battery-staple", "$2a$10$hashed");
            assertThat(created.username()).isEqualTo("asha");
            assertThat(created.fullName()).isEqualTo("Asha Rao");
        }

        @Test
        void whenTheUsernameIsAlreadyTaken_throwsConflictWithoutHashingAnything() {
            // Given
            when(userRepository.existsByUsername("asha")).thenReturn(true);

            // When / Then
            assertThatThrownBy(() -> customerService.register(REQUEST))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Username asha is already taken");
            verify(userRepository, never()).saveAndFlush(any());
            // BCrypt is expensive on purpose; there is no reason to pay for it to then throw away
            // the result.
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        void whenTwoRegistrationsRace_theUniqueConstraintStillProducesA409() {
            // Given: the "is it taken?" check says no, and the insert then loses to another
            // registration that committed in between. The check is for the message; the unique
            // index in V5 is the thing that actually decides.
            when(userRepository.existsByUsername("asha")).thenReturn(false);
            when(passwordEncoder.encode(any())).thenReturn("$2a$10$hashed");
            when(userRepository.saveAndFlush(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_users_username"));

            // When / Then: the client sees the same 409, not a 500
            assertThatThrownBy(() -> customerService.register(REQUEST))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Username asha is already taken");
        }

        @Test
        void aRealBCryptHashIsSaltedSoTwoIdenticalPasswordsDoNotMatch() {
            // Not about CustomerService: about the property the whole scheme rests on. Two
            // accounts with the same password must not have the same hash, or one stolen table
            // plus one precomputed rainbow table would crack every reused password at once.
            BCryptPasswordEncoder real = new BCryptPasswordEncoder();
            String first = real.encode("same-password");
            String second = real.encode("same-password");

            assertThat(first).isNotEqualTo(second);
            assertThat(real.matches("same-password", first)).isTrue();
            assertThat(real.matches("same-password", second)).isTrue();
            assertThat(real.matches("other-password", first)).isFalse();
        }
    }

    @Nested
    @DisplayName("the profile endpoints act on the caller and nobody else")
    class Profile {

        @Test
        void currentProfile_returnsTheAuthenticatedAccount() {
            // Given
            User asha = TestData.user(7L, "asha", Role.CUSTOMER);
            // The lookup moved into CustomerService in Phase 20d: CurrentUser reads the token and
            // nothing else, so the account comes from this service's own repository.
            when(currentUser.id()).thenReturn(asha.getId());
            when(userRepository.findById(asha.getId())).thenReturn(Optional.of(asha));

            // When / Then: the account is taken from the security context, never from an
            // argument, so there is nothing for a caller to tamper with
            assertThat(customerService.currentProfile().username()).isEqualTo("asha");
        }

        @Test
        void updateCurrentProfile_changesOnlyTheDisplayName() {
            // Given
            User asha = TestData.user(7L, "asha", Role.CUSTOMER);
            // The lookup moved into CustomerService in Phase 20d: CurrentUser reads the token and
            // nothing else, so the account comes from this service's own repository.
            when(currentUser.id()).thenReturn(asha.getId());
            when(userRepository.findById(asha.getId())).thenReturn(Optional.of(asha));

            // When
            CustomerResponse updated =
                    customerService.updateCurrentProfile(new UpdateProfileRequest("Asha M. Rao"));

            // Then
            assertThat(updated.fullName()).isEqualTo("Asha M. Rao");
            assertThat(updated.username()).as("the username identifies the account elsewhere").isEqualTo("asha");
            assertThat(updated.role()).as("a role is a privilege, not a preference").isEqualTo(Role.CUSTOMER);
        }
    }
}
