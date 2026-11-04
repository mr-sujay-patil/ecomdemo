package com.ecomdemo.cart.internal;

import com.ecomdemo.cart.CartService;
import com.ecomdemo.cart.Cart;
import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.shared.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the caller's own cart.
 *
 * <p>Notice that no endpoint here takes a cart id. The cart is always "mine", resolved from the
 * authenticated principal inside {@code CartService}, so there is no identifier for a client to
 * tamper with and no ownership check to forget.
 */
@RestController
@RequestMapping("/api/cart")
@SecurityRequirement(name = "bearerAuth")
@Tag(
        name = "Cart",
        description =
                "Your own cart. Requires a CUSTOMER account: it is created on first use, every "
                        + "endpoint returns the whole cart afterwards, and checkout empties it.")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    /**
     * Runs a cart call, and runs it once more if it lost the race to create the account's cart
     * (KI-081).
     *
     * <p>The cart is created by the first request that needs it, and {@code uq_cart_user} allows
     * one per account. Two first requests that arrive together (the web app reads the cart twice
     * at once after sign-in) both find no cart and both insert one; the second insert breaks the
     * constraint, and that request used to answer 500 for a valid call.
     *
     * <p>The retry is here, outside the service's transaction, because it cannot be inside it:
     * PostgreSQL aborts a transaction at its first failed statement, so the losing request could
     * not go on to read the winner's cart. By the time the exception reaches this method its
     * transaction has rolled back whole (nothing of it is kept, so repeating an add cannot add
     * twice), and the second call is a new transaction that finds the cart the other request
     * committed. A second failure is not a race this method knows, so it propagates.
     */
    private static CartResponse onceMoreIfTheCartWasCreatedConcurrently(Supplier<CartResponse> call) {
        try {
            return call.get();
        } catch (DataIntegrityViolationException lostTheRace) {
            return call.get();
        }
    }

    @GetMapping
    @Operation(
            summary = "View your cart",
            description = "Creates your cart on first call, so this never returns 404.")
    @ApiResponse(responseCode = "200", description = "The cart, empty if nothing has been added")
    @ApiResponse(
            responseCode = "401",
            description = "No Bearer token, or one that is invalid or expired",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated, but not as a CUSTOMER",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public CartResponse view() {
        return onceMoreIfTheCartWasCreatedConcurrently(cartService::view);
    }

    @PostMapping("/items")
    @Operation(
            summary = "Add a product to the cart",
            description = "Adding a product already in the cart increases that line rather than creating a second one.")
    @ApiResponse(responseCode = "200", description = "The cart after the addition")
    @ApiResponse(
            responseCode = "400",
            description = "productId or quantity is missing, or quantity is below 1",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No product with that id",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public CartResponse addItem(@Valid @RequestBody AddCartItemRequest request) {
        return onceMoreIfTheCartWasCreatedConcurrently(() -> cartService.addItem(request));
    }

    @PutMapping("/items/{productId}")
    @Operation(
            summary = "Set the quantity of a line",
            description = "Replaces the quantity outright. To take a product out of the cart, use DELETE.")
    @ApiResponse(responseCode = "200", description = "The cart after the change")
    @ApiResponse(
            responseCode = "400",
            description = "quantity is missing or below 1",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "That product is not in the cart",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public CartResponse updateItem(
            @Parameter(description = "Id of the product whose line is being changed", example = "1")
            @PathVariable Long productId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return onceMoreIfTheCartWasCreatedConcurrently(() -> cartService.updateItem(productId, request));
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "Remove a line from the cart")
    @ApiResponse(responseCode = "200", description = "The cart after the removal")
    @ApiResponse(
            responseCode = "404",
            description = "That product is not in the cart",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public CartResponse removeItem(
            @Parameter(description = "Id of the product to remove", example = "1") @PathVariable Long productId) {
        return onceMoreIfTheCartWasCreatedConcurrently(() -> cartService.removeItem(productId));
    }
}
