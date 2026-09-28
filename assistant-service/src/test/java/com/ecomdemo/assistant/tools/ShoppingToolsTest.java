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
        assertThat(parameters).containsExactlyInAnyOrder("query", "category", "maxprice", "orderid", "productname", "quantity");
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
                .isEqualTo("Product search is unavailable right now.");
    }

    @Test
    @DisplayName("an order that is not the customer's is 'not on this account', and is not recorded")
    void orderNotFound() {
        when(store.orderStatus(TOKEN, 7L)).thenReturn(Optional.empty());

        assertThat(tools.getOrderStatus(7L))
                .isEqualTo("Order 7 is not an order on your account.");
        assertThat(turn.orders()).isEmpty();
    }

    @Test
    @DisplayName("addToCart finds the product by name and only proposes; it never touches the cart")
    void addToCartProposes() {
        when(store.searchProducts(TOKEN, "noise-cancelling headphones", null, null, 5)).thenReturn(List.of(HEADPHONES));
        PendingCartAddition proposal = new PendingCartAddition("a1", 4L, HEADPHONES.name(), 2, HEADPHONES.price());
        when(actions.propose(11L, 4L, HEADPHONES.name(), 2, HEADPHONES.price())).thenReturn(proposal);

        Object result = tools.addToCart("noise-cancelling headphones", 2);

        assertThat(result.toString()).contains("Confirm button").contains("not in the cart");
        assertThat(turn.pendingAction()).isEqualTo(proposal);
        verify(store, never()).addToCart(anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("a name matching several products, or none, proposes nothing and says which exist")
    void addToCartNeedsOneProduct() {
        ProductView sleeve = new ProductView(10L, "Laptop Sleeve 16\"", "Padded sleeve", new BigDecimal("1799.00"), 5, "ACCESSORIES");
        ProductView stand = new ProductView(6L, "Laptop Stand", "Aluminium stand", new BigDecimal("2199.00"), 5, "ACCESSORIES");
        when(store.searchProducts(TOKEN, "laptop", null, null, 5)).thenReturn(List.of(sleeve, stand));
        when(store.searchProducts(TOKEN, "gaming laptop", null, null, 5)).thenReturn(List.of());

        assertThat(tools.addToCart("laptop", 1).toString())
                .contains("Ask the customer which one").contains("Laptop Stand").contains("Laptop Sleeve");
        assertThat(tools.addToCart("gaming laptop", 1).toString()).contains("No product in this store is called");
        assertThat(turn.pendingAction()).isNull();
        verify(actions, never()).propose(anyLong(), anyLong(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("an exact name wins over names that merely contain it")
    void exactNameWins() {
        ProductView mat = new ProductView(8L, "Desk Mat", "Felt mat", new BigDecimal("1299.00"), 5, "ACCESSORIES");
        ProductView bigMat = new ProductView(11L, "Desk Mat XL", "Bigger", new BigDecimal("1999.00"), 5, "ACCESSORIES");

        assertThat(ShoppingTools.matching("desk mat", List.of(bigMat, mat))).containsExactly(mat);
        assertThat(ShoppingTools.matching("mat", List.of(bigMat, mat))).containsExactly(bigMat, mat);
    }

    @Test
    @DisplayName("addToCart: a quantity outside 1-10, or a second proposal in one answer, is refused")
    void addToCartRefusals() {
        assertThat(tools.addToCart("Desk Mat", 0).toString()).contains("quantity from 1 to 10");
        assertThat(tools.addToCart("Desk Mat", 11).toString()).contains("quantity from 1 to 10");

        when(store.searchProducts(TOKEN, "Noise-Cancelling Headphones", null, null, 5)).thenReturn(List.of(HEADPHONES));
        when(actions.propose(eq(11L), eq(4L), anyString(), anyInt(), any()))
                .thenReturn(new PendingCartAddition("a1", 4L, HEADPHONES.name(), 1, HEADPHONES.price()));
        tools.addToCart("Noise-Cancelling Headphones", null);
        assertThat(tools.addToCart("Noise-Cancelling Headphones", 1).toString()).contains("Only one addition");
        verify(store, never()).addToCart(anyString(), anyLong(), anyInt());
    }
}
