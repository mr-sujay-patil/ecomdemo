package com.ecomdemo.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

/**
 * Tests for the two hand-written queries on {@link OrderRepository}.
 *
 * <p>Both exist to avoid the N+1 problem: without the JOIN FETCH, listing ten orders would fire
 * one query for the orders and then one more per order to load its lines. The tests below prove
 * the lines come back with the order, and that {@code findAllWithItems} keeps its declared
 * ordering.
 *
 * <p>The schema is built by Hibernate, not by the Flyway migrations: {@code @DataJpaTest} hands
 * every slice its own throwaway database and does not run Flyway, and these tests want a table
 * with nothing in it. {@code FlywayMigrationTest} covers the migrations.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findAllWithItems_whenNoOrdersExist_returnsEmptyList() {
        // Given: an empty orders table

        // When / Then
        assertThat(orderRepository.findAllWithItems()).isEmpty();
    }

    @Test
    void findAllWithItems_whenOrdersExist_returnsThemByIdWithTheirLines() {
        // Given: two orders, the first with two lines
        persistOrder("2026-01-01T10:00:00Z", List.of(line("Lamp", "1500.00", 2), line("Cable", "100.50", 3)));
        persistOrder("2026-01-02T10:00:00Z", List.of(line("Mouse", "3499.00", 1)));
        entityManager.clear();

        // When
        List<Order> found = orderRepository.findAllWithItems();

        // Then
        assertThat(found).hasSize(2);
        assertThat(found.get(0).getId()).isLessThan(found.get(1).getId());
        assertThat(found.get(0).getItems())
                .extracting(OrderItem::getProductName, OrderItem::getQuantity)
                .containsExactlyInAnyOrder(tuple("Lamp", 2), tuple("Cable", 3));
        assertThat(found.get(0).getTotalAmount()).isEqualByComparingTo("3301.50");
    }

    @Test
    void findAllWithItems_whenAnOrderHasSeveralLines_returnsOneOrderNotOnePerLine() {
        // Given: without "distinct" the collection join would return this order three times
        persistOrder(
                "2026-01-01T10:00:00Z",
                List.of(line("Lamp", "1500.00", 1), line("Cable", "100.50", 1), line("Mouse", "3499.00", 1)));
        entityManager.clear();

        // When / Then
        assertThat(orderRepository.findAllWithItems()).hasSize(1);
    }

    @Test
    void findByIdWithItems_whenTheOrderExists_returnsItWithItsLines() {
        // Given
        Long id = persistOrder("2026-01-01T10:00:00Z", List.of(line("Lamp", "1500.00", 2))).getId();
        entityManager.clear();

        // When
        Optional<Order> found = orderRepository.findByIdWithItems(id);

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(found.get().getItems())
                .extracting(OrderItem::getProductName, OrderItem::getUnitPrice)
                .containsExactly(tuple("Lamp", new BigDecimal("1500.00")));
    }

    @Test
    void findByIdWithItems_whenTheIdIsUnknown_returnsEmptyOptional() {
        // Given: nothing persisted

        // When / Then
        assertThat(orderRepository.findByIdWithItems(404L)).isEmpty();
    }

    @Test
    void findByIdWithItems_whenTheOrderHasNoLines_stillFindsIt() {
        // Given: the service never creates one, but "left join" means the query does not
        // silently lose a row that somehow has none
        Long id = persistOrder("2026-01-01T10:00:00Z", List.of()).getId();
        entityManager.clear();

        // When / Then
        assertThat(orderRepository.findByIdWithItems(id)).isPresent();
    }

    private Order persistOrder(String placedAt, List<Line> lines) {
        Order order = new Order(Instant.parse(placedAt));
        long productId = 10L;
        for (Line line : lines) {
            order.addItem(productId++, line.name(), new BigDecimal(line.unitPrice()), line.quantity());
        }
        return entityManager.persistAndFlush(order);
    }

    private static Line line(String name, String unitPrice, int quantity) {
        return new Line(name, unitPrice, quantity);
    }

    private record Line(String name, String unitPrice, int quantity) {
    }
}
