package com.ecomdemo.customer;

import com.ecomdemo.customer.internal.UserRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account lookup, published for the modules that authenticate people.
 *
 * <p>Introduced in Phase 19 so that {@code UserRepository} could move into {@code internal}.
 * {@code security} has to turn a username into an account in order to authenticate it, and it was
 * reaching across the boundary into the customer module's repository to do so.
 *
 * <p>That was not a harmless shortcut. A repository is a module's whole data surface — every
 * derived query anybody adds to it becomes available to every importer, so the boundary widens
 * over time without anyone deciding to widen it. This interface is two methods, and adding a third
 * is a visible act.
 *
 * <p>It is deliberately NOT {@code CustomerService}. That class is about a person managing their
 * own profile — it reads {@code CurrentUser} and acts on the caller. This one answers questions
 * about accounts in general, for infrastructure that has no caller yet because it is still working
 * out who the caller is. Folding the two together would have given {@code security} access to
 * profile mutation it has no business with.
 */
@Service
public class UserDirectory {

    private final UserRepository users;

    public UserDirectory(UserRepository users) {
        this.users = users;
    }

    /** The account with this username, for authentication. */
    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        return users.findByUsername(username);
    }

    /** The account with this id, for a request that already carries a verified token. */
    @Transactional(readOnly = true)
    public Optional<User> findById(Long id) {
        return users.findById(id);
    }
}
