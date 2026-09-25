package com.ecomdemo.customer;

import com.ecomdemo.customer.internal.UserRepository;
import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.customer.dto.RegisterRequest;
import com.ecomdemo.customer.dto.UpdateProfileRequest;
import com.ecomdemo.jwt.CurrentUser;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and the profile of whoever is calling.
 *
 * <p>The class defaults to {@code readOnly = true} with the writers overriding it, the same way
 * round as {@code ProductService}: a new read method is safe by default, a new write method has
 * to say so.
 */
@Service
@Transactional(readOnly = true)
public class CustomerService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUser currentUser;

    public CustomerService(
            UserRepository userRepository, PasswordEncoder passwordEncoder, CurrentUser currentUser) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
    }

    /**
     * Creates a CUSTOMER account. The role is not a parameter, and it never will be: an
     * anonymous endpoint that accepts a role is an endpoint that hands out administrator
     * accounts to anyone who asks.
     *
     * <p>The password is hashed on the line it arrives and the plain text is never held in a
     * field, written to the database or put in a log.
     *
     * <p><strong>Why the check and the catch.</strong> {@code existsByUsername} gives the caller
     * a clear 409 in the ordinary case. It cannot be the real guard, though: it is a read
     * followed by a write, and two registrations of the same name racing between the two both
     * read "free" and both insert. The unique index from V5 is what actually decides, and the
     * catch turns the constraint violation into the same 409 rather than a 500. Check for the
     * message, constrain for the truth.
     */
    @Transactional
    public CustomerResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw usernameTaken(request.username());
        }
        User user = new User(
                request.username(),
                passwordEncoder.encode(request.password()),
                request.fullName(),
                Role.CUSTOMER,
                Instant.now());
        try {
            return CustomerResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException ex) {
            throw usernameTaken(request.username());
        }
    }

    /** The caller's own account. There is deliberately no "get any user by id" endpoint. */
    public CustomerResponse currentProfile() {
        return CustomerResponse.from(requireCurrentAccount());
    }

    @Transactional
    public CustomerResponse updateCurrentProfile(UpdateProfileRequest request) {
        User user = requireCurrentAccount();
        user.setFullName(request.fullName());
        return CustomerResponse.from(user);
    }

    /**
     * The caller's account, loaded from this service's own table.
     *
     * <p>This used to be {@code currentUser.require()}, on a {@code CurrentUser} that held a
     * {@code UserRepository}. Phase 20d moved that class to {@code common} so every service could read
     * the caller's identity from the token — and took the repository lookup out of it, because a
     * service that does not own accounts must not be able to load one.
     *
     * <p>So the lookup lives here, in the one service that legitimately has the table. It is the same
     * two lines it always was; what changed is that nobody else can write them.
     *
     * <p>The id comes from the token's {@code uid} claim, so a caller can only ever fetch THEMSELVES —
     * the same property the missing "get any user by id" endpoint is about, now enforced by where the
     * number comes from rather than by which endpoints exist.
     */
    private User requireCurrentAccount() {
        Long id = currentUser.id();
        return userRepository
                .findById(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated as user " + id + " but that account no longer exists"));
    }

    private ConflictException usernameTaken(String username) {
        return new ConflictException("Username %s is already taken".formatted(username));
    }
}
