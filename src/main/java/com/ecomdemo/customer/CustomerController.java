package com.ecomdemo.customer;

import com.ecomdemo.common.ApiError;
import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.customer.dto.RegisterRequest;
import com.ecomdemo.customer.dto.UpdateProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for accounts: registering one, and reading or editing your own. */
@RestController
@RequestMapping("/api/customers")
@Tag(
        name = "Customers",
        description =
                "Accounts. Registration is open to anyone and always creates a CUSTOMER; the "
                        + "profile endpoints act on whoever is authenticated and on nobody else.")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping("/register")
    @Operation(
            summary = "Register a customer account",
            description =
                    """
                    The one endpoint besides browsing the catalogue that an anonymous caller may \
                    use — requiring an account in order to create an account would be a closed \
                    loop.

                    The password is hashed with BCrypt on arrival and is never stored, logged or \
                    returned. The new account is always a CUSTOMER: the role cannot be chosen by \
                    the caller.
                    """)
    @ApiResponse(responseCode = "201", description = "The account was created. The Location header points at the profile.")
    @ApiResponse(
            responseCode = "400",
            description = "A field is missing, the username has illegal characters, or the password is shorter than 8 characters",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "That username is already taken",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CustomerResponse> register(@Valid @RequestBody RegisterRequest request) {
        CustomerResponse created = customerService.register(request);
        return ResponseEntity.created(URI.create("/api/customers/me")).body(created);
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Your own profile",
            description =
                    "Reads the account behind the credentials on the request. There is no "
                            + "\"get user by id\" endpoint: an account is only ever visible to itself.")
    @ApiResponse(responseCode = "200", description = "The authenticated account")
    @ApiResponse(
            responseCode = "401",
            description = "No Bearer token, or one that is invalid or expired",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public CustomerResponse me() {
        return customerService.currentProfile();
    }

    @PutMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Change your own display name",
            description =
                    "The only editable field. The username identifies the account everywhere "
                            + "else in the schema, and the role is a privilege rather than a preference.")
    @ApiResponse(responseCode = "200", description = "The updated account")
    @ApiResponse(
            responseCode = "400",
            description = "fullName is blank or longer than 100 characters",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "No Bearer token, or one that is invalid or expired",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public CustomerResponse updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return customerService.updateCurrentProfile(request);
    }
}
