package com.ecomdemo.identity;

import com.ecomdemo.clients.customer.CustomerGateway;
import com.ecomdemo.clients.customer.CustomerView;
import com.ecomdemo.clients.customer.RegisterCommand;
import com.ecomdemo.clients.customer.UpdateProfileCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/customers}, forwarded to customer-service.
 *
 * <p><strong>The caller's own token is forwarded, not a service token</strong>, on the two profile
 * endpoints. That is what makes {@code /me} mean the right person: customer-service reads the
 * {@code uid} claim out of the token it receives, so whoever's token arrives is whose profile comes
 * back. Substituting this application's service identity would answer for nobody — or, worse, for
 * whatever account the service identity happened to map to.
 *
 * <p>There is still no "get any customer by id", here or there. The absence is the access control.
 */
@RestController
@RequestMapping("/api/customers")
@Tag(name = "Customers", description = "Registration, and the caller's own profile.")
class CustomerProxyController {

    private final CustomerGateway customers;

    CustomerProxyController(CustomerGateway customers) {
        this.customers = customers;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register an account", description = "Public: a new customer has no token yet.")
    CustomerView register(@Valid @RequestBody RegisterRequest request) {
        return customers.register(
                new RegisterCommand(request.username(), request.password(), request.fullName()));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "The caller's own profile")
    CustomerView currentProfile(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return customers.currentProfile(authorization);
    }

    @PutMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update the caller's own profile")
    CustomerView updateCurrentProfile(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody UpdateProfileRequest request) {
        return customers.updateCurrentProfile(
                authorization, new UpdateProfileCommand(request.fullName()));
    }

    /** Validated at the edge as well as in customer-service; see {@code AuthProxyController}. */
    record RegisterRequest(
            @NotBlank(message = "must not be blank")
            @Size(min = 3, max = 50, message = "must be between 3 and 50 characters")
            String username,

            @NotBlank(message = "must not be blank")
            @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
            String password,

            @NotBlank(message = "must not be blank")
            @Size(max = 100, message = "must be at most 100 characters")
            String fullName) {
    }

    record UpdateProfileRequest(
            @NotBlank(message = "must not be blank")
            @Size(max = 100, message = "must be at most 100 characters")
            String fullName) {
    }
}
