package com.ecomdemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.clients.catalog.CatalogGateway;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the import's validation and upsert rules.
 *
 * <p>No Spring, no database and no Spring Batch: the processor is a plain object with one
 * dependency, and the rules it enforces are the part of the job most likely to be got wrong.
 * Testing them here rather than through a job run means each rule is one fast, named test
 * instead of a row buried in a fixture file.
 */
@ExtendWith(MockitoExtension.class)
class ProductImportProcessorTest {


    /**
     * Only the catalogue's persistence is mocked. {@link InventoryService} itself is REAL, because
     * the assertions below are about the stock value the processor produces — a mocked inventory
     * would make {@code setStockLevel} a no-op and every one of those assertions would be checking
     * that the mock was asked politely rather than that the number is right.
     */
    private ProductImportProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ProductImportProcessor();
    }

    private static ProductCsvRow row(String name, String price, String stock) {
        return new ProductCsvRow(7, "raw line", name, "a description", price, stock, "TEST");
    }

    @Nested
    @DisplayName("a valid row")
    class Valid {

        @Test
        @DisplayName("becomes a new product when nothing of that name exists")
        void createsWhenTheNameIsNew() {

            ImportedProduct imported = processor.process(row("Widget", "12.50", "4"));

            assertThat(imported.product().id()).as("a new product has no id yet").isNull();
            assertThat(imported.product().name()).isEqualTo("Widget");
            assertThat(imported.product().price()).isEqualByComparingTo("12.50");
            assertThat(imported.stockQuantity()).isEqualTo(4);
            assertThat(imported.product().category()).isEqualTo("TEST");
        }

        // THE "UPDATES THE EXISTING PRODUCT OF THAT NAME" TEST MOVED, it was not deleted.
        //
        // It asserted that the processor looked a product up by name and mutated the match, so a
        // re-import was idempotent. The processor does not look anything up any more: resolving a
        // name to a product is one HTTP call per ROW if it happens here, so it happens in
        // catalog-service, once per chunk, inside the write.
        //
        // The claim now lives where the behaviour does — `ProductServiceTest` in catalog-service,
        // "a null id resolves by name, so a re-import updates rather than duplicates". This comment
        // exists so that the next person to wonder where the idempotency test went does not
        // conclude there isn't one.

        @Test
        @DisplayName("is trimmed, and blank optional columns become null rather than empty text")
        void trimsAndNullsOutBlanks() {

            ImportedProduct imported = processor.process(
                    new ProductCsvRow(1, "raw", "  Widget  ", "   ", " 12.50 ", " 4 ", ""));

            assertThat(imported.product().name()).isEqualTo("Widget");
            assertThat(imported.product().description()).isNull();
            assertThat(imported.product().category()).isNull();
            assertThat(imported.stockQuantity()).isEqualTo(4);
        }

        @Test
        @DisplayName("keeps the price at two decimal places, the scale the column stores")
        void normalisesThePriceScale() {

            ImportedProduct imported = processor.process(row("Widget", "12.5", "4"));

            assertThat(imported.product().price().scale()).isEqualTo(2);
            assertThat(imported.product().price()).isEqualByComparingTo("12.50");
        }
    }

    @Nested
    @DisplayName("an invalid row is rejected with a reason naming the line")
    class Invalid {

        @ParameterizedTest(name = "{3}")
        @CsvSource({
            "'',12.50,4,name is required",
            "Widget,,4,price is required",
            "Widget,twelve,4,price 'twelve' is not a number",
            "Widget,0.00,4,price '0.00' must be positive",
            "Widget,-1.00,4,price '-1.00' must be positive",
            "Widget,12.505,4,price '12.505' has more than 2 decimal places",
            "Widget,12.50,,stock_quantity is required",
            "Widget,12.50,four,stock_quantity 'four' is not a whole number",
            "Widget,12.50,-1,stock_quantity '-1' must not be negative"
        })
        void rejects(String name, String price, String stock, String reason) {
            assertThatThrownBy(() -> processor.process(row(name, price, stock)))
                    .isInstanceOf(InvalidProductRowException.class)
                    .hasMessage("line 7: " + reason);
        }

        @Test
        @DisplayName("a name longer than the column")
        void rejectsAnOverlongName() {
            assertThatThrownBy(() -> processor.process(row("x".repeat(256), "12.50", "4")))
                    .isInstanceOf(InvalidProductRowException.class)
                    .hasMessageContaining("name is longer than 255 characters");
        }

        // "and never reaches the database, so a bad row costs no query" is gone with the lookup
        // it verified. It is now true by construction rather than by assertion: this class holds no
        // collaborator at all, so a bad row cannot reach anything. A test that can only pass is not
        // worth the line it takes up.

        @Test
        @DisplayName("and the exception carries the row, which is what the error file writes out")
        void carriesTheRow() {
            ProductCsvRow bad = row("Widget", "nope", "4");

            assertThatThrownBy(() -> processor.process(bad))
                    .isInstanceOf(InvalidProductRowException.class)
                    .extracting(e -> ((InvalidProductRowException) e).row())
                    .isEqualTo(bad);
        }
    }
}
