package com.ecomdemo.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.assistant.actions.PendingActions;
import com.ecomdemo.assistant.actions.PendingCartAddition;
import com.ecomdemo.assistant.store.ProductView;
import com.ecomdemo.assistant.store.StoreClient;
import com.ecomdemo.assistant.store.StoreUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShoppingTools: what the model can do, and what it cannot")
class ShoppingToolsTest {

    private static final String TOKEN = "the-customers-token";
    private static final ProductView HEADPHONES = new ProductView(4L, "Noise-Cancelling Headphones",
            "Over-ear ANC headphones", new BigDecimal("14999.00"), 12, "AUDIO");

    @Mock
    private StoreClient store;

    @Mock
    private PendingActions actions;

    private SimpleMeterRegistry meters;
    private Turn turn;
    private ShoppingTools tools;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        turn = new Turn();
        tools = new ShoppingTools(store, actions, meters, TOKEN, 11L, 5, turn);
    }

    @Test
    @DisplayName("no tool has a parameter that could choose WHOSE data: no user, customer, account or token")
    void theModelCannotChooseTheUser() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();

        assertThat(callbacks).extracting(c -> c.getToolDefinition().name())
                .containsExactlyInAnyOrder("searchProducts", "getOrderStatus", "addToCart");
        // The parameter NAMES, from the JSON schema the model is sent. (The descriptions may well
        // mention "the customer" - the point is that the model has no argument to name one with.)
        List<String> parameters = Arrays.stream(callbacks)
                .map(c -> JsonMapper.shared().readTree(c.getToolDefinition().inputSchema()).get("properties"))
                .flatMap(properties -> properties.propertyNames().stream())
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
        assertThat(parameters).containsExactlyInAnyOrder("query", "category", "maxprice", "orderid", "productid", "quantity");
        assertThat(parameters).noneMatch(name -> name.contains("user") || name.contains("customer")
                || name.contains("account") || name.contains("token"));
    }

    @Test
    @DisplayName("searchProducts sends the customer's token and remembers what it found")
    void search() {
        when(store.searchProducts(TOKEN, "headphones", "AUDIO", new BigDecimal("20000"), 5)).thenReturn(List.of(HEADPHONES));

        Object result = tools.searchProducts("headphones", "AUDIO", new BigDecimal("20000"));

        assertThat(result).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .singleElement().extracting("name").isEqualTo("Noise-Cancelling Headphones");
        assertThat(turn.products()).containsExactly(HEADPHONES);
        assertThat(turn.toolsUsed()).containsExactly("searchProducts");
        assertThat(meters.counter(ShoppingTools.CALLS, "tool", "searchProducts", "outcome", "found").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unavailable store becomes a sentence for the model")
    void searchUnavailable() {
        when(store.searchProducts(anyString(), anyString(), any(), any(), anyInt()))
                .thenThrow(new StoreUnavailableException("Product search is unavailable right now.", null));

        assertThat(tools.searchProducts("headphones", null, null))
                .isEqualTo(new ShoppingTools.Note("Product search is unavailable right now."));
    }

    @Test
    @DisplayName("an order that is not the customer's is 'not on this account', and is not recorded")
    void orderNotFound() {
        when(store.orderStatus(TOKEN, 7L)).thenReturn(Optional.empty());

        assertThat(tools.getOrderStatus(7L))
                .isEqualTo(new ShoppingTools.Note("There is no order 7 on this customer's account."));
        assertThat(turn.orders()).isEmpty();
    }

    @Test
    @DisplayName("addToCart only proposes; it never touches the cart")
    void addToCartProposes() {
        when(store.product(TOKEN, 4L)).thenReturn(Optional.of(HEADPHONES));
        PendingCartAddition proposal = new PendingCartAddition("a1", 4L, HEADPHONES.name(), 2, HEADPHONES.price());
        when(actions.propose(11L, 4L, HEADPHONES.name(), 2, HEADPHONES.price())).thenReturn(proposal);

        Object result = tools.addToCart(4L, 2);

        assertThat(result.toString()).contains("Nothing is in the cart yet");
        assertThat(turn.pendingAction()).isEqualTo(proposal);
        verify(store, never()).addToCart(anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("addToCart: a quantity outside 1-10, an unknown product, or a second proposal is refused")
    void addToCartRefusals() {
        assertThat(tools.addToCart(4L, 0).toString()).contains("quantity from 1 to 10");
        assertThat(tools.addToCart(4L, 11).toString()).contains("quantity from 1 to 10");

        when(store.product(TOKEN, 99L)).thenReturn(Optional.empty());
        assertThat(tools.addToCart(99L, 1).toString()).contains("no product with id 99");

        when(store.product(TOKEN, 4L)).thenReturn(Optional.of(HEADPHONES));
        when(actions.propose(eq(11L), eq(4L), anyString(), anyInt(), any()))
                .thenReturn(new PendingCartAddition("a1", 4L, HEADPHONES.name(), 1, HEADPHONES.price()));
        tools.addToCart(4L, null);
        assertThat(tools.addToCart(4L, 1).toString()).contains("Only one addition");
        verify(store, never()).addToCart(anyString(), anyLong(), anyInt());
    }
}
