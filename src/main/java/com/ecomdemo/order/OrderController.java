package com.ecomdemo.order;

import com.ecomdemo.common.ApiError;
import com.ecomdemo.order.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for checkout and order history. */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Checkout and order history. An order is immutable once placed.")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** Placing an order takes no body: it always checks out the whole shared cart. */
    @PostMapping
    @Operation(
            summary = "Check out the cart",
            description =
                    """
                    Takes no body: it always checks out the whole shared cart. Stock is checked for \
                    every line first, then reduced, the order is saved with the names and prices \
                    copied in, and the cart is emptied.

                    The whole sequence is one database transaction, so a failure anywhere in it \
                    leaves the catalogue, the cart and the order history exactly as they were. \
                    If another checkout changes the same products at the same time, this one is \
                    retried a few times and then answered with 409.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "The order was placed. The Location header points at it.")
    @ApiResponse(
            responseCode = "409",
            description =
                    "The cart is empty, a line asks for more units than are in stock, or the "
                            + "request kept losing to concurrent checkouts of the same products",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<OrderResponse> place() {
        OrderResponse order = orderService.place();
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
    }

    @GetMapping
    @Operation(summary = "List every order", description = "Ordered by id, oldest first. Unpaged: every order is returned.")
    @ApiResponse(responseCode = "200", description = "Every order placed so far, possibly empty")
    public List<OrderResponse> list() {
        return orderService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one order by id")
    @ApiResponse(responseCode = "200", description = "The order")
    @ApiResponse(
            responseCode = "404",
            description = "No order with that id",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public OrderResponse get(
            @Parameter(description = "Id of the order", example = "1") @PathVariable Long id) {
        return orderService.findById(id);
    }
}
