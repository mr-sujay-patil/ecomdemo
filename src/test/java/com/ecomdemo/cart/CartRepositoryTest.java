package com.ecomdemo.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ecomdemo.product.Product;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

/**
 * Tests for the one query {@link CartRepository} declares itself.
 *
 * <p>{@code @DataJpaTest} starts JPA and an in-memory database and nothing else: no web layer,
 * no services. Only a hand-written query can actually be wrong — everything inherited from
 * {@code JpaRepository} is Spring Data's code, not ours — so this class tests
 * {@code findCart()} and leaves {@code save} and {@code findById} alone.
 *
 * <p>Each test is wrapped in a transaction that is rolled back afterwards, so tests cannot leak
 * rows into each other. The schema comes from Hibernate rather than from the Flyway migrations
 * (see the property below): {@code @DataJpaTest} hands every slice its own throwaway database
 * and does not run Flyway, and a repository test should set up exactly the rows it asserts on -
 * migration V2 would seed ten products it never asked for. The migrations are exercised instead
 * by {@code FlywayMigrationTest} and by the {@code @SpringBootTest} classes.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CartRepositoryTest {

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("findCart returns empty before anything has been added")
    void findCart_whenNoCartHasBeenCreated_returnsEmptyOptional() {
        // Given: a database with no cart row

        // When
        Optional<Cart> found = cartRepository.findCart();

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void findCart_whenTheCartIsEmpty_stillFindsIt() {
        // Given: a cart with no lines at all
        entityManager.persistAndFlush(new Cart());
        entityManager.clear();

        // When
        Optional<Cart> found = cartRepository.findCart();

        // Then: this is what "left join" buys — an inner join would return nothing here, and
        // the very first "add to cart" would fail because no cart could be found
        assertThat(found).isPresent();
        assertThat(found.get().getItems()).isEmpty();
    }

    @Test
    void findCart_whenTheCartHasLines_loadsTheItemsAndTheirProducts() {
        // Given
        Product lamp = persistProduct("Lamp", "1500.00", 9);
        Product cable = persistProduct("Cable", "100.50", 4);
        Cart cart = new Cart();
        cart.addItem(lamp, 2);
        cart.addItem(cable, 3);
        entityManager.persistAndFlush(cart);
        entityManager.clear();

        // When
        Cart found = cartRepository.findCart().orElseThrow();

        // Then: the JOIN FETCH has already brought back the items and the products behind them
        assertThat(found.getItems())
                .extracting(item -> item.getProduct().getName(), CartItem::getQuantity)
                .containsExactlyInAnyOrder(tuple("Lamp", 2), tuple("Cable", 3));
        assertThat(found.total()).isEqualByComparingTo("3301.50");
    }

    @Test
    void findCart_whenTheCartHasSeveralLines_returnsOneCartNotOnePerLine() {
        // Given: a collection join multiplies the parent row by the number of children, which is
        // what the "distinct" in the query is there to collapse
        Cart cart = new Cart();
        cart.addItem(persistProduct("Lamp", "1500.00", 9), 1);
        cart.addItem(persistProduct("Cable", "100.50", 4), 1);
        cart.addItem(persistProduct("Mouse", "3499.00", 7), 1);
        entityManager.persistAndFlush(cart);
        entityManager.clear();

        // When
        Optional<Cart> found = cartRepository.findCart();

        // Then: one cart, three lines
        assertThat(found).isPresent();
        assertThat(found.get().getItems()).hasSize(3);
    }

    private Product persistProduct(String name, String price, int stock) {
        return entityManager.persistAndFlush(
                new Product(name, name + " description", new BigDecimal(price), stock));
    }
}
