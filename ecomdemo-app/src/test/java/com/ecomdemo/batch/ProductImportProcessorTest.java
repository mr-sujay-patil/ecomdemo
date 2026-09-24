package com.ecomdemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ecomdemo.catalog.Product;
import com.ecomdemo.catalog.ProductService;
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
    @Mock
    private ProductService productService;

    private ProductImportProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ProductImportProcessor(productService);
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
            when(productService.findFirstByName("Widget")).thenReturn(Optional.empty());

            ImportedProduct imported = processor.process(row("Widget", "12.50", "4"));

            assertThat(imported.product().getId()).as("a new product has no id yet").isNull();
            assertThat(imported.product().getName()).isEqualTo("Widget");
            assertThat(imported.product().getPrice()).isEqualByComparingTo("12.50");
            assertThat(imported.stockQuantity()).isEqualTo(4);
            assertThat(imported.product().getCategory()).isEqualTo("TEST");
        }

        @Test
        @DisplayName("updates the existing product of that name, so a re-import is idempotent")
        void updatesWhenTheNameExists() {
            Product existing = new Product("Widget", "old", new BigDecimal("1.00"), "OLD");
            when(productService.findFirstByName("Widget"))
                    .thenReturn(Optional.of(existing));

            ImportedProduct imported = processor.process(row("Widget", "12.50", "4"));

            // The SAME object comes back, mutated. That is what makes a restart safe: the rows of
            // a rolled-back chunk are read again, and processing them twice changes nothing.
            assertThat(imported.product()).isSameAs(existing);
            assertThat(imported.product().getPrice()).isEqualByComparingTo("12.50");
            assertThat(imported.stockQuantity()).isEqualTo(4);
        }

        @Test
        @DisplayName("is trimmed, and blank optional columns become null rather than empty text")
        void trimsAndNullsOutBlanks() {
            when(productService.findFirstByName("Widget")).thenReturn(Optional.empty());

            ImportedProduct imported = processor.process(
                    new ProductCsvRow(1, "raw", "  Widget  ", "   ", " 12.50 ", " 4 ", ""));

            assertThat(imported.product().getName()).isEqualTo("Widget");
            assertThat(imported.product().getDescription()).isNull();
            assertThat(imported.product().getCategory()).isNull();
            assertThat(imported.stockQuantity()).isEqualTo(4);
        }

        @Test
        @DisplayName("keeps the price at two decimal places, the scale the column stores")
        void normalisesThePriceScale() {
            when(productService.findFirstByName("Widget")).thenReturn(Optional.empty());

            ImportedProduct imported = processor.process(row("Widget", "12.5", "4"));

            assertThat(imported.product().getPrice().scale()).isEqualTo(2);
            assertThat(imported.product().getPrice()).isEqualByComparingTo("12.50");
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

        @Test
        @DisplayName("and never reaches the database, so a bad row costs no query")
        void doesNotLookUpAnInvalidRow() {
            assertThatThrownBy(() -> processor.process(row("Widget", "nope", "4")))
                    .isInstanceOf(InvalidProductRowException.class);

            org.mockito.Mockito.verify(productService, org.mockito.Mockito.never())
                    .findFirstByName(anyString());
        }

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
