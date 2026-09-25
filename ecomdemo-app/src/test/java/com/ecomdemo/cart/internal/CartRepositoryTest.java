package com.ecomdemo.cart.internal;

import com.ecomdemo.cart.CartItem;
import com.ecomdemo.cart.Cart;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.support.TestData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
 * {@code findByUserId()} and leaves {@code save} and {@code findById} alone.
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

    /**
     * A cart cannot exist without an owner any more ({@code user_id} is NOT NULL), so every
     * test needs one. Each test gets a fresh row because the surrounding transaction is rolled
     * back afterwards.
     */
    /**
     * Two owner IDS, and nothing persisted for them.
     *
     * <p>This used to persist a {@code User} row per owner, because {@code cart.user_id} had a foreign
     * key and a cart could not exist without an account to point at. Phase 20d removed the key and
     * moved the table to customer-service, so there is nothing here to persist — and nothing that
     * would check it if there were.
     *
     * <p>That is precisely the guarantee the split gave up, and this test is where it shows: it can
     * now create a cart for an account that does not exist, and the database is perfectly happy.
     */
    private static final long OWNER_ID = 5101L;

    private static final long OTHER_ID = 5102L;

    @Test
    @DisplayName("findByUserId returns empty before anything has been added")
    void findByUserId_whenTheUserHasNoCart_returnsEmptyOptional() {
        // Given: a database with no cart row

        // When
        Optional<Cart> found = cartRepository.findByUserId(OWNER_ID);

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void findByUserId_whenTheCartIsEmpty_stillFindsIt() {
        // Given: a cart with no lines at all
        entityManager.persistAndFlush(new Cart(OWNER_ID));
        entityManager.clear();

        // When
        Optional<Cart> found = cartRepository.findByUserId(OWNER_ID);

        // Then: this is what "left join" buys — an inner join would return nothing here, and
        // the very first "add to cart" would fail because no cart could be found
        assertThat(found).isPresent();
        assertThat(found.get().getItems()).isEmpty();
    }

    @Test
    void findByUserId_whenTheCartHasLines_loadsTheItemsAndTheirProducts() {
        // Given
        ProductSnapshot lamp = persistProduct("Lamp", "1500.00", 9);
        ProductSnapshot cable = persistProduct("Cable", "100.50", 4);
        Cart cart = new Cart(OWNER_ID);
        TestData.addTo(cart, lamp, 2);
        TestData.addTo(cart, cable, 3);
        entityManager.persistAndFlush(cart);
        entityManager.clear();

        // When
        Cart found = cartRepository.findByUserId(OWNER_ID).orElseThrow();

        // Then: the JOIN FETCH brings back the items. Since Phase 20 there are no products
        // BEHIND them - each line remembers the name and price it was added at - so what this
        // asserts is that the lines came back with the cart, which is what the fetch is for.
        assertThat(found.getItems())
                .extracting(CartItem::getProductName, CartItem::getQuantity)
                .containsExactlyInAnyOrder(tuple("Lamp", 2), tuple("Cable", 3));
        assertThat(found.total()).isEqualByComparingTo("3301.50");
    }

    @Test
    void findByUserId_whenTheCartHasSeveralLines_returnsOneCartNotOnePerLine() {
        // Given: a collection join multiplies the parent row by the number of children, which is
        // what the "distinct" in the query is there to collapse
        Cart cart = new Cart(OWNER_ID);
        TestData.addTo(cart, persistProduct("Lamp", "1500.00", 9), 1);
        TestData.addTo(cart, persistProduct("Cable", "100.50", 4), 1);
        TestData.addTo(cart, persistProduct("Mouse", "3499.00", 7), 1);
        entityManager.persistAndFlush(cart);
        entityManager.clear();

        // When
        Optional<Cart> found = cartRepository.findByUserId(OWNER_ID);

        // Then: one cart, three lines
        assertThat(found).isPresent();
        assertThat(found.get().getItems()).hasSize(3);
    }

    @Test
    @DisplayName("findByUserId never returns another account's cart")
    void findByUserId_whenAnotherAccountHasACart_doesNotReturnIt() {
        // Given: somebody else's cart, with something in it
        Cart theirs = new Cart(OTHER_ID);
        TestData.addTo(theirs, persistProduct("Lamp", "1500.00", 9), 1);
        entityManager.persistAndFlush(theirs);
        entityManager.clear();

        // When: our user asks for theirs
        Optional<Cart> found = cartRepository.findByUserId(OWNER_ID);

        // Then: the rows never leave the database. This is the tenancy rule, and it is enforced
        // by the where clause rather than by anything remembering to filter afterwards.
        assertThat(found).isEmpty();
    }

    /**
     * A product the cart can hold, WITHOUT persisting one.
     *
     * <p>It used to call {@code entityManager.persistAndFlush(new Product(...))}, because
     * {@code cart_item} had a foreign key to {@code product} and a line could not exist without a
     * row to point at. Phase 20a removed that key and made the line a snapshot; Phase 20c moved the
     * table to another database entirely, so there is nothing here to persist and nothing that
     * would check it if there were.
     *
     * <p>The {@code stock} parameter is kept because it reads as scenario documentation at the call
     * sites, and dropping it would make three tests say less about what they are describing.
     */
    private ProductSnapshot persistProduct(String name, String price, int stock) {
        return TestData.product(nextProductId++, name, price);
    }

    private long nextProductId = 1;
}
