package com.ecomdemo.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An account: who is calling, and what they are allowed to do.
 *
 * <p>The table is {@code users} because USER is a reserved word in SQL — see V5.
 *
 * <p><strong>{@code password} holds a BCrypt hash, never a password.</strong> The entity cannot
 * help with that on its own, so the rule is kept where it can be enforced: the only constructor
 * that sets it is called from {@link CustomerService#register}, which encodes first. Nothing in
 * this class can turn the stored value back into a password, which is exactly the property
 * hashing is chosen for.
 *
 * <p>There is deliberately no {@code setPassword} and no {@code setRole}. Changing a password is
 * a use case with its own rules (re-authenticate, re-encode) and would arrive as its own method;
 * changing a role is a privilege change and must never be a side effect of editing a profile.
 * Leaving the setters out means neither can happen by accident.
 *
 * <p>This is <em>not</em> a Spring Security {@code UserDetails}. Keeping the two apart means the
 * persistence model is free of framework types and the framework's view of a user is built where
 * it is needed — see {@code com.ecomdemo.security.AppUserDetails}.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String username;

    /** The BCrypt hash. 60 characters today; the column allows 100 so the format can change. */
    @Column(nullable = false, length = 100)
    private String password;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {
        // required by JPA
    }

    public User(String username, String encodedPassword, String fullName, Role role, Instant createdAt) {
        this.username = username;
        this.password = encodedPassword;
        this.fullName = fullName;
        this.role = role;
        this.createdAt = createdAt;
    }

    /** A profile change. The name is the only thing about an account its owner may edit. */
    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
