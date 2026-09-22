package com.ecomdemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.batch.dto.JobExecutionResponse;
import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.IntegrationTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The sales report job, against real orders placed through the real checkout.
 *
 * <p>The report is only interesting if the numbers in it are the numbers in the database, so
 * nothing here writes orders with SQL: the test buys things the way a customer does, then asks
 * for the day's report and reads the file the job wrote.
 *
 * <p>{@link BatchService#runSalesReport(LocalDate)} is called directly rather than through an
 * endpoint because the report has no endpoint — its trigger is a cron, and that the cron really
 * fires the job is proven separately in {@code SalesReportScheduleTest}. Splitting it this way
 * keeps this class about the report's CONTENT and that one about its TIMING.
 */
class SalesReportJobIT extends IntegrationTest {

    private static final String PREFIX = "IT Report ";

    @Autowired
    private BatchService batchService;

    @Autowired
    private BatchProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    private TestRestTemplate admin;
    private TestRestTemplate shopper;

    private final List<Long> createdProductIds = new ArrayList<>();

    @BeforeEach
    void signIn() {
        admin = asAdmin();
        shopper = asCustomer("it-report-shopper");
        emptyTheCart();
    }

    @AfterEach
    void cleanUp() {
        emptyTheCart();
        // Orders first: an order line keeps a copy of the product, so deleting the product does
        // not cascade - but leaving today's orders behind would change the next run's totals.
        jdbc.update("DELETE FROM order_item WHERE product_name LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM orders WHERE id NOT IN (SELECT order_id FROM order_item)");
        createdProductIds.forEach(id -> admin.delete("/api/products/" + id));
        createdProductIds.clear();
    }

    private void emptyTheCart() {
        CartResponse cart = shopper.getForObject("/api/cart", CartResponse.class);
        if (cart != null) {
            cart.items().stream().map(CartItemResponse::productId)
                    .forEach(id -> shopper.delete("/api/cart/items/" + id));
        }
    }

    private long createProduct(String name, String price, int stock) {
        ProductResponse product = admin.postForObject("/api/products",
                new ProductRequest(PREFIX + name, "for the report", new BigDecimal(price), stock,
                        "REPORT"),
                ProductResponse.class);
        assertThat(product).isNotNull();
        createdProductIds.add(product.id());
        return product.id();
    }

    private void buy(long productId, int quantity) {
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(productId, quantity),
                CartResponse.class);
        OrderResponse order = shopper.postForObject("/api/orders", null, OrderResponse.class);
        assertThat(order).as("the checkout should succeed").isNotNull();
    }

    @Test
    @DisplayName("today's report counts the orders, totals the revenue and ranks the best sellers")
    void writesTheDaysSales() throws IOException {
        long popular = createProduct("Popular", "10.00", 100);
        long quiet = createProduct("Quiet", "50.00", 100);

        buy(popular, 5);      // one order,  50.00
        buy(popular, 3);      // one order,  30.00
        buy(quiet, 1);        // one order,  50.00

        LocalDate today = LocalDate.now();
        JobExecutionResponse execution = batchService.runSalesReport(today);

        assertThat(execution.status()).isEqualTo("COMPLETED");
        assertThat(execution.steps())
                .extracting(step -> step.name())
                .containsExactly(SalesReportJobConfig.SUMMARY_STEP,
                        SalesReportJobConfig.TOP_PRODUCTS_STEP);

        List<String> report = Files.readAllLines(reportFile(today));

        // The header block comes from the tasklet step. Other tests in this run may have placed
        // orders of their own, so the assertions are "at least ours" rather than exact - the
        // best-seller lines below are the precise part.
        assertThat(report.get(0)).isEqualTo("# EcomDemo daily sales report");
        assertThat(report.get(1)).isEqualTo("# date," + today);
        assertThat(orderCount(report)).isGreaterThanOrEqualTo(3);
        assertThat(report.get(4)).isEqualTo("product_id,product_name,units_sold,revenue");

        // The chunk step's rows: units summed across orders, revenue as unit price times quantity.
        assertThat(report).anyMatch(line ->
                line.startsWith(popular + ",\"" + PREFIX + "Popular\",8,80.00"));
        assertThat(report).anyMatch(line ->
                line.startsWith(quiet + ",\"" + PREFIX + "Quiet\",1,50.00"));

        // 8 units of Popular against 1 of Quiet, so Popular ranks first.
        int popularRow = rowOf(report, popular);
        int quietRow = rowOf(report, quiet);
        assertThat(popularRow).as("the best seller comes first").isLessThan(quietRow);
    }

    @Test
    @DisplayName("a day with no orders still produces a report, with zeroes and no rows")
    void writesAnEmptyReportForAQuietDay() throws IOException {
        // A date far enough in the past that nothing in this container can have sold on it.
        LocalDate quietDay = LocalDate.of(2000, 1, 1);

        JobExecutionResponse execution = batchService.runSalesReport(quietDay);

        assertThat(execution.status()).isEqualTo("COMPLETED");
        List<String> report = Files.readAllLines(reportFile(quietDay));
        assertThat(report).hasSize(5);
        assertThat(report.get(1)).isEqualTo("# date,2000-01-01");
        assertThat(report.get(2)).isEqualTo("# orders,0");
        // The scale of an empty SUM is the database's business; what matters is that it is zero
        // and not null, which is what the coalesce in the query is for.
        assertThat(report.get(3)).startsWith("# revenue,0");
        assertThat(report.get(4)).isEqualTo("product_id,product_name,units_sold,revenue");
    }

    @Test
    @DisplayName("the same day cannot be reported twice, so a re-run cannot overwrite yesterday")
    void refusesASecondRunForTheSameDay() {
        LocalDate day = LocalDate.of(2001, 2, 3);
        assertThat(batchService.runSalesReport(day).status()).isEqualTo("COMPLETED");

        // The report date is an IDENTIFYING job parameter, so this is the same JobInstance - and
        // an instance that has COMPLETED will not run again. That is the JobRepository doing the
        // job a lock file or a "have I run today?" flag would otherwise be written to do.
        assertThat(org.assertj.core.api.Assertions
                .catchThrowableOfType(com.ecomdemo.common.ConflictException.class,
                        () -> batchService.runSalesReport(day)))
                .isNotNull()
                .hasMessageContaining("already been completed");
    }

    private Path reportFile(LocalDate day) {
        return SalesReportJobConfig.reportFileFor(properties.reportDirectory(), day);
    }

    private static long orderCount(List<String> report) {
        return Long.parseLong(report.get(2).substring("# orders,".length()));
    }

    private static int rowOf(List<String> report, long productId) {
        for (int i = 0; i < report.size(); i++) {
            if (report.get(i).startsWith(productId + ",")) {
                return i;
            }
        }
        throw new AssertionError("product " + productId + " is not in the report");
    }
}
