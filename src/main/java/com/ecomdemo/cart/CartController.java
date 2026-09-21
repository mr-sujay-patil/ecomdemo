package com.ecomdemo.cart;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.common.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for the single shared cart. */
@RestController
@RequestMapping("/api/cart")
@Tag(
        name = "Cart",
        description =
                "The one cart everybody shares. It is created on first use, every endpoint returns the whole cart afterwards, and checkout empties it.")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @Operation(
            summary = "View the cart",
            description = "Creates the shared cart on first call, so this never returns 404.")
    @ApiResponse(responseCode = "200", description = "The cart, empty if nothing has been added")
    public CartResponse view() {
        return cartService.view();
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
        return cartService.addItem(request);
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
        return cartService.updateItem(productId, request);
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
        return cartService.removeItem(productId);
    }
}
