package com.ecomdemo.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.shared.InsufficientStockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * The stock arithmetic, against a real database.
 *
 * <h2>Where these assertions came from</h2>
 *
 * <p>Most of them were somewhere else until Phase 20. "A checkout reduces stock by the quantity
 * ordered" was asserted in {@code OrderPlacementServiceTest}, on a {@code Product} object it could
 * mutate and read back; "a stock change announces itself so the cache can be evicted" was asserted
 * in {@code ProductServiceTest}. Splitting {@code product_stock} out took the field away from both
 * of them.
 *
 * <p>Nothing was dropped. It is asserted here, where the behaviour now lives — and it is a better
 * place for it, because a {@code @DataJpaTest} can exercise the optimistic lock and the
 * missing-row case, neither of which a mock could have shown.
 *
 * <p><strong>Phase 20b moved the file itself into inventory-service</strong>, and took a helper
 * out of it. These tests used to wrap every reservation in a transaction, because {@code reserve}
 * was {@code Propagation.MANDATORY} and refused to run without one. It cannot be any more — the
 * caller is in another process — so the wrapper went with the guarantee. What replaces that
 * guarantee is a compensating {@code release}, which is tested below and, unlike a rollback, can
 * itself fail.
 *
 * <h2>Why a database rather than a mocked repository</h2>
 *
 * <p>Two of the claims below are claims about JPA, not about this class: that a missing row reads
 * as zero rather than throwing, and that {@code quantitiesFor} really does answer in one query for
 * many ids. A mocked repository would have made both of those assertions about the mock.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({InventoryService.class, StockChangePublisher.class})
@RecordApplicationEvents
@DisplayName("InventoryService")
class InventoryServiceTest {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private ProductStockRepository stock;

    /**
     * {@code @DataJpaTest} configures a database and nothing else, so there is no
     * {@code KafkaTemplate} for {@link StockChangePublisher} to hold. Mocking it keeps this test
     * on the arithmetic, which is what it is for.
     *
     * <p>The publisher itself is REAL, because the assertion below is about the event it raises
     * in-process — the one a transactional listener then turns into a message. Mocking the
     * publisher would have removed the thing under test; mocking the broker underneath it removes
     * only the part that belongs to {@code InventoryKafkaIT}.
     */
    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Records what was published, rather than mocking the publisher.
     *
     * <p>{@code @MockitoBean ApplicationEventPublisher} does NOT work here, and the reason is
     * worth knowing: Spring registers that interface as a <em>resolvable dependency</em> — the
     * application context itself — rather than as an ordinary bean, so constructor injection
     * resolves to the context and walks straight past the mock. The test passes its stubbing and
     * then reports zero interactions, which reads like the code not publishing at all.
     *
     * <p>{@code @RecordApplicationEvents} is better anyway: it observes the real publication
     * instead of substituting for it. What the cache then does with the event, and the fact that
     * it arrives only after a COMMIT, is {@code CacheApiIT}'s to prove — that timing is not
     * observable from here.
     */
    @Autowired
    private ApplicationEvents publishedEvents;

    @BeforeEach
    void setUp() {
        stock.deleteAll();
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("a product with no stock row has zero, rather than blowing up")
        void aMissingRowReadsAsZero() {
            // There is no foreign key from product_stock to product - see the V11 migration for
            // why one would be a liability rather than a guarantee - so this class cannot assume
            // a row exists. Zero is both true and an answer the caller can act on.
            assertThat(inventory.quantityFor(999L)).isZero();
        }

        @Test
        void readsWhatWasStored() {
            stock.save(new ProductStock(1L, 7));

            assertThat(inventory.quantityFor(1L)).isEqualTo(7);
        }

        @Test
        @DisplayName("answers for many products at once, which is what stops N+1 becoming N+1 HTTP")
        void batchesTheLookup() {
            stock.save(new ProductStock(1L, 7));
            stock.save(new ProductStock(2L, 0));

            var quantities = inventory.quantitiesFor(java.util.List.of(1L, 2L, 999L));

            assertThat(quantities).containsEntry(1L, 7).containsEntry(2L, 0);
            assertThat(quantities).doesNotContainKey(999L);
        }

        @Test
        void asksNothingWhenGivenNothing() {
            assertThat(inventory.quantitiesFor(java.util.List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("availability")
    class Availability {

        @Test
        @DisplayName("names the product and the shortfall, because a shopper reads this")
        void reportsTheShortfall() {
            stock.save(new ProductStock(1L, 2));

            assertThatThrownBy(() -> inventory.requireAvailable(1L, "Desk Lamp", 3))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("Desk Lamp")
                    .hasMessageContaining("3")
                    .hasMessageContaining("2");
        }

        @Test
        void passesWhenThereIsExactlyEnough() {
            stock.save(new ProductStock(1L, 3));

            inventory.requireAvailable(1L, "Desk Lamp", 3);
        }

        @Test
        @DisplayName("the product name is a parameter because this module cannot look one up")
        void takesTheNameFromTheCaller() {
            // The first small tax of the split, and the honest one to pay. The alternative is an
            // inventory service that calls the catalogue back to build an error message.
            assertThatThrownBy(() -> inventory.requireAvailable(404L, "Ghost Lamp", 1))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("Ghost Lamp");
        }
    }

    @Nested
    @DisplayName("reserving")
    class Reserving {

        @Test
        @DisplayName("reduces by the quantity ordered")
        void reducesStock() {
            // Moved from OrderPlacementServiceTest, which used to assert this on a Product it
            // could mutate directly.
            stock.save(new ProductStock(1L, 9));

            inventory.reserve(1L, "Lamp", 2);

            assertThat(inventory.quantityFor(1L)).isEqualTo(7);
        }

        @Test
        @DisplayName("announces the change, which is what lets the cache be evicted after commit")
        void announcesTheChange() {
            // Moved from ProductServiceTest. Announcing is all a reservation is allowed to do
            // about the cache: it runs inside a transaction that may still roll back, so the
            // eviction itself has to wait for the commit.
            stock.save(new ProductStock(1L, 9));

            inventory.reserve(1L, "Lamp", 2);

            assertThat(publishedEvents.stream(ProductStockChangedEvent.class))
                    .containsExactly(new ProductStockChangedEvent(1L));
        }

        @Test
        @DisplayName("refuses to take more than there is, in the shopper's terms")
        void refusesAnOverdraw() {
            stock.save(new ProductStock(1L, 1));

            assertThatThrownBy(() -> inventory.reserve(1L, "Lamp", 2))
                    .isInstanceOf(InsufficientStockException.class);

            assertThat(inventory.quantityFor(1L)).isEqualTo(1);
        }

        @Test
        @DisplayName("a product with no stock row cannot be reserved from")
        void refusesWhenThereIsNoRow() {
            assertThatThrownBy(() -> inventory.reserve(404L, "Ghost", 1))
                    .isInstanceOf(InsufficientStockException.class);
        }

        @Test
        @DisplayName("taking the last unit is allowed and leaves zero")
        void allowsTakingTheLastUnit() {
            stock.save(new ProductStock(1L, 1));

            inventory.reserve(1L, "Lamp", 1);

            assertThat(inventory.quantityFor(1L)).isZero();
        }
    }

    @Nested
    @DisplayName("setting a level")
    class SettingALevel {

        @Test
        @DisplayName("creates the row when the product has never had one")
        void createsAMissingRow() {
            inventory.setStockLevel(1L, 5);

            assertThat(inventory.quantityFor(1L)).isEqualTo(5);
        }

        @Test
        void replacesAnExistingLevel() {
            stock.save(new ProductStock(1L, 5));

            inventory.setStockLevel(1L, 2);

            assertThat(inventory.quantityFor(1L)).isEqualTo(2);
        }

        @Test
        @DisplayName("refuses a negative level, which is not a thing stock can be")
        void refusesNegative() {
            assertThatThrownBy(() -> inventory.setStockLevel(1L, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("does NOT announce a change, because the catalogue's own eviction covers it")
        void staysQuiet() {
            // A create or an update carries @CacheEvict on ProductService; the CSV import evicts
            // once at the end of its step. An event per row would be thousands of evictions to
            // invalidate a cache the import's scale has already made useless.
            inventory.setStockLevel(1L, 5);

            assertThat(publishedEvents.stream(ProductStockChangedEvent.class)).isEmpty();
        }
    }

    @Test
    @DisplayName("forgetting a product removes its stock entirely")
    void forgets() {
        stock.save(new ProductStock(1L, 5));

        inventory.forget(1L);

        assertThat(stock.findById(1L)).isEmpty();
    }

}
