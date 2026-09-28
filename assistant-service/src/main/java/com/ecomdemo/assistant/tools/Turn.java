package com.ecomdemo.assistant.tools;

import com.ecomdemo.assistant.actions.PendingCartAddition;
import com.ecomdemo.assistant.store.ProductView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What happened while one question was being answered: which tools ran, what they returned, and
 * whether a cart addition was proposed. The answer's {@code sources} are built from it, which is how
 * a client (and the evaluation) can check that a product the model named really came from the
 * catalogue.
 *
 * <p>How MANY tools one answer may call is not counted here: Spring AI 2.0 enforces that itself
 * ({@code spring.ai.tools.limits.*}), and ends the answer with a {@code ToolCallLimitExceededException}.
 */
public class Turn {

    private final List<String> toolsUsed = new ArrayList<>();
    private final Map<Long, ProductView> products = new LinkedHashMap<>();
    private final List<Long> orders = new ArrayList<>();
    private PendingCartAddition pendingAction;

    void used(String tool) {
        toolsUsed.add(tool);
    }

    void sawProduct(ProductView product) {
        products.putIfAbsent(product.id(), product);
    }

    void sawOrder(long orderId) {
        orders.add(orderId);
    }

    void proposed(PendingCartAddition action) {
        this.pendingAction = action;
    }

    public List<String> toolsUsed() {
        return List.copyOf(toolsUsed);
    }

    public List<ProductView> products() {
        return List.copyOf(products.values());
    }

    public List<Long> orders() {
        return List.copyOf(orders);
    }

    public PendingCartAddition pendingAction() {
        return pendingAction;
    }
}
