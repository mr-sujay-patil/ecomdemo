package com.ecomdemo.assistant.tools;

import com.ecomdemo.assistant.actions.PendingActions;
import com.ecomdemo.assistant.actions.PendingCartAddition;
import com.ecomdemo.assistant.store.OrderStatusView;
import com.ecomdemo.assistant.store.ProductView;
import com.ecomdemo.assistant.store.StoreClient;
import com.ecomdemo.assistant.store.StoreUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.web.client.HttpClientErrorException;

/**
 * What the model may DO, as opposed to say (Phase 29).
 *
 * <h2>How tool calling works</h2>
 *
 * The model never runs code. Each {@link Tool} method is described to it as a name, a sentence and
 * a JSON schema of its arguments. When it decides one would help, its reply is not text but a
 * request - "call getOrderStatus with {orderId: 42}" - which Spring AI executes here, and whose
 * result goes back to the model as a new message. The model then answers, or asks for another
 * tool. The descriptions below are therefore PROMPTS: they are how the model learns when a tool
 * applies, and a vague one is a tool that gets called at the wrong time or never.
 *
 * <h2>The authorization boundary</h2>
 *
 * A new instance is made for every question, holding that caller's token and user id. Look at what
 * the methods do NOT take: no user id, no username, no token. The model chooses the arguments, so
 * anything that decides WHOSE data is read must never be one of them. It comes from the verified
 * token, and the owning service checks it again.
 *
 * <h2>What the model is told when something goes wrong</h2>
 *
 * Every failure is caught here and returned as a plain sentence. An exception left to escape would
 * be turned into its message and handed to the model - a stack of URLs and class names that at best
 * confuses it and at worst ends up quoted to the customer.
 */
public class ShoppingTools {

    static final String CALLS = "ecomdemo.assistant.tool.calls";

    private final StoreClient store;
    private final PendingActions actions;
    private final MeterRegistry meterRegistry;
    private final String token;
    private final long userId;
    private final int productResults;
    private final Turn turn;

    public ShoppingTools(StoreClient store, PendingActions actions, MeterRegistry meterRegistry,
            String token, long userId, int productResults, Turn turn) {
        this.store = store;
        this.actions = actions;
        this.meterRegistry = meterRegistry;
        this.token = token;
        this.userId = userId;
        this.productResults = productResults;
        this.turn = turn;
    }

    @Tool(name = "searchProducts", description = """
            Search this store's catalogue by meaning. Use it for EVERY question about which products \
            the store sells, what something costs, whether it is in stock, or what to recommend. \
            Returns up to five products with their id, name, price in rupees, category, stock and \
            description. Never mention a product that this tool did not return.""")
    public Object searchProducts(
            @ToolParam(description = "What the customer is looking for, in plain words, e.g. 'headphones for flights'")
            String query,
            @ToolParam(required = false, description = "Only this category: PERIPHERALS, DISPLAYS, AUDIO, ACCESSORIES or STORAGE")
            String category,
            @ToolParam(required = false, description = "Only products costing at most this many rupees")
            BigDecimal maxPrice) {
        turn.used("searchProducts");
        if (query == null || query.isBlank()) {
            return note("searchProducts", "invalid", "Say what to search for.");
        }
        try {
            List<ProductView> products = store.searchProducts(token, query, category, maxPrice, productResults);
            products.forEach(turn::sawProduct);
            count("searchProducts", products.isEmpty() ? "empty" : "found");
            return products.isEmpty()
                    ? new Note("No products in this store match that.")
                    : products.stream().map(FoundProduct::of).toList();
        } catch (StoreUnavailableException | HttpClientErrorException e) {
            return note("searchProducts", "unavailable", "Product search is unavailable right now.");
        }
    }

    @Tool(name = "getOrderStatus", description = """
            Look up one of the CURRENT customer's own orders by its order number, and return its \
            status: PENDING, CONFIRMED or CANCELLED (with the reason). It can only see the current \
            customer's orders; any other number is reported as not found.""")
    public Object getOrderStatus(@ToolParam(description = "The order number, e.g. 42") Long orderId) {
        turn.used("getOrderStatus");
        if (orderId == null || orderId <= 0) {
            return note("getOrderStatus", "invalid", "An order number is a positive whole number.");
        }
        try {
            Optional<OrderStatusView> status = store.orderStatus(token, orderId);
            if (status.isEmpty()) {
                return note("getOrderStatus", "not_found",
                        "There is no order " + orderId + " on this customer's account.");
            }
            turn.sawOrder(orderId);
            count("getOrderStatus", "found");
            return status.get();
        } catch (StoreUnavailableException | HttpClientErrorException e) {
            return note("getOrderStatus", "unavailable", "Order lookup is unavailable right now.");
        }
    }

    @Tool(name = "addToCart", description = """
            PROPOSE adding a product to the current customer's cart. This does NOT change the cart: \
            the customer is shown the proposal and must confirm it in the app. Use a product id \
            returned by searchProducts. After calling it, tell the customer to confirm.""")
    public Object addToCart(
            @ToolParam(description = "The product's id, from searchProducts") Long productId,
            @ToolParam(required = false, description = "How many, 1 to 10; 1 if the customer did not say") Integer quantity) {
        turn.used("addToCart");
        int units = quantity == null ? 1 : quantity;
        if (productId == null || units < 1 || units > 10) {
            return note("addToCart", "invalid", "Give a product id and a quantity from 1 to 10.");
        }
        if (turn.pendingAction() != null) {
            return note("addToCart", "invalid", "Only one addition can be proposed per answer.");
        }
        try {
            Optional<ProductView> product = store.product(token, productId);
            if (product.isEmpty()) {
                return note("addToCart", "not_found", "There is no product with id " + productId + ".");
            }
            ProductView found = product.get();
            PendingCartAddition action = actions.propose(userId, found.id(), found.name(), units, found.price());
            turn.proposed(action);
            count("addToCart", "proposed");
            return new Note("Proposed: " + units + " x " + found.name() + " at " + found.price()
                    + " rupees each. Nothing is in the cart yet; the customer must confirm.");
        } catch (StoreUnavailableException | HttpClientErrorException e) {
            return note("addToCart", "unavailable", "The catalogue is unavailable right now.");
        }
    }

    private Note note(String tool, String outcome, String text) {
        count(tool, outcome);
        return new Note(text);
    }

    private void count(String tool, String outcome) {
        meterRegistry.counter(CALLS, "tool", tool, "outcome", outcome).increment();
    }

    /** What a tool says when it has no data to return. */
    public record Note(String note) {
    }

    /** A product as the model sees it: what it needs to answer, and the id to propose it by. */
    public record FoundProduct(Long id, String name, BigDecimal price, String category, int stock,
            String description) {

        static FoundProduct of(ProductView product) {
            return new FoundProduct(product.id(), product.name(), product.price(), product.category(),
                    product.stockQuantity(), product.description());
        }
    }
}
