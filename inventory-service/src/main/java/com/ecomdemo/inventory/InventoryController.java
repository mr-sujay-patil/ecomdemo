package com.ecomdemo.inventory;

import com.ecomdemo.inventory.dto.StockLevelRequest;
import com.ecomdemo.inventory.dto.StockResponse;
import com.ecomdemo.inventory.dto.UnitsRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The inventory service's HTTP surface: what used to be method calls on {@code InventoryService}.
 *
 * <h2>The batch endpoint is not a convenience</h2>
 *
 * <p>{@link #quantities} takes a list of ids because a catalogue listing needs a number for every
 * row it returns. In one process, asking per product was the N+1 problem — a real but survivable
 * inefficiency. Across a boundary the same shape is N+1 <em>network round trips</em>, and a
 * forty-product listing becomes forty HTTP calls. Phase 20a shaped the caller around a batch
 * while it was still a method call precisely so this endpoint could exist rather than be wished
 * for later.
 *
 * <h2>Reserve and release are a pair, and the pairing is the whole design</h2>
 *
 * <p>Reserving used to be {@code MANDATORY}-transactional inside the caller's transaction, so a
 * failed checkout un-reserved automatically. Over HTTP that is impossible, so the caller
 * compensates: it reserves, and if its own transaction rolls back it releases. See
 * {@code InventoryService#release} for why the two are deliberately not symmetric.
 *
 * <h2>Everything needs a token</h2>
 *
 * <p>Including the reads. Stock is not a secret, but this service is not on the public internet -
 * it is called by other services carrying the caller's token, and an endpoint that skipped
 * authentication would be one an attacker inside the network could use to enumerate the
 * catalogue's movement. Validation is local: the signature is checked here, and nothing calls
 * customer-service to ask whether a token is real.
 */
@RestController
@RequestMapping("/api/inventory")
class InventoryController {

    private final InventoryService inventory;

    InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/{productId}")
    StockResponse stockFor(@PathVariable Long productId) {
        return new StockResponse(productId, inventory.quantityFor(productId));
    }

    /**
     * Stock for many products in one call.
     *
     * <p>{@code GET} with a repeated query parameter rather than a {@code POST} with a body: this
     * is a read, it is cacheable and idempotent, and making it a POST to carry a list would be
     * choosing a verb for the sake of the payload. The practical ceiling is URL length, which for
     * a catalogue page of ids is nowhere near reached.
     */
    @GetMapping
    List<StockResponse> quantities(@RequestParam List<Long> productIds) {
        Map<Long, Integer> quantities = inventory.quantitiesFor(productIds);
        return productIds.stream()
                .distinct()
                .map(id -> new StockResponse(id, quantities.getOrDefault(id, 0)))
                .toList();
    }

    /**
     * Sets an absolute level. The catalogue does this on create and update; the CSV import too.
     *
     * <p><strong>The {@code @PreAuthorize("hasRole('ADMIN')")} that used to be here is gone</strong>,
     * and its removal is the most consequential line of Phase 20b's security work. It was carried
     * over from the monolith, where the administrator's own token reached this method. It cannot
     * now: the application calls this service with its own SERVICE identity, so the only role that
     * ever arrives is {@code SERVICE} and the rule could only ever have answered 403 — breaking
     * product creation and the CSV import together.
     *
     * <p>It was also doing nothing, which is the part worth dwelling on. Method security is off
     * unless {@code @EnableMethodSecurity} is present, and this service never enabled it, so the
     * annotation was inert. An annotation that is both unsatisfiable and inactive is worse than no
     * annotation: it reads like protection. The real check is {@code SecurityConfig}, which requires
     * a valid token; that an ADMIN asked for this stays where the administrator's token is, at the
     * edge.
     */
    @PutMapping("/{productId}")
    StockResponse setLevel(@PathVariable Long productId, @Valid @RequestBody StockLevelRequest request) {
        inventory.setStockLevel(productId, request.quantity());
        return new StockResponse(productId, inventory.quantityFor(productId));
    }

    /**
     * Takes units out. Answers 409 through {@code InsufficientStockException} when there are not
     * enough, which is the same status and the same message a shopper saw when this was a method
     * call — the split is not supposed to be visible from the outside.
     */
    @PostMapping("/{productId}/reserve")
    StockResponse reserve(@PathVariable Long productId, @Valid @RequestBody UnitsRequest request) {
        inventory.reserve(productId, request.productName(), request.units());
        return new StockResponse(productId, inventory.quantityFor(productId));
    }

    /**
     * Puts units back. The compensating half of the saga, called when the caller's transaction
     * rolled back after a successful reserve.
     */
    @PostMapping("/{productId}/release")
    StockResponse release(@PathVariable Long productId, @Valid @RequestBody UnitsRequest request) {
        inventory.release(productId, request.units());
        return new StockResponse(productId, inventory.quantityFor(productId));
    }

    /** Forgets a product's stock, when the catalogue deletes the product. See {@link #setLevel} for
     * why this no longer carries {@code @PreAuthorize("hasRole('ADMIN')")}. */
    @DeleteMapping("/{productId}")
    ResponseEntity<Void> forget(@PathVariable Long productId) {
        inventory.forget(productId);
        return ResponseEntity.noContent().build();
    }
}
