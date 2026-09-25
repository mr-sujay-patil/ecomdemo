package com.ecomdemo.identity;

import com.ecomdemo.clients.customer.CustomerGateway;
import com.ecomdemo.clients.customer.LoginCommand;
import com.ecomdemo.clients.customer.TokenView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/auth/login}, forwarded to customer-service.
 *
 * <p>The endpoint a shopper has used since Phase 9, at the same URL, returning the same body. What
 * changed is behind it: this application cannot check a password any more — it has no
 * {@code PasswordEncoder}, no {@code AuthenticationManager} and no {@code users} table — so it asks
 * the one service that can.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Exchanging credentials for a token.")
public class AuthProxyController {

    private final CustomerGateway customers;

    AuthProxyController(CustomerGateway customers) {
        this.customers = customers;
    }

    @PostMapping("/login")
    @Operation(
            summary = "Log in",
            description = "Returns a signed token. Public: this is how a caller obtains one.")
    TokenView login(@Valid @RequestBody LoginRequest request) {
        return customers.login(new LoginCommand(request.username(), request.password()));
    }
}
