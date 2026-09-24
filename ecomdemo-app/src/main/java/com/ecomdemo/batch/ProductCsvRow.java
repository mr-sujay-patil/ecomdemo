package com.ecomdemo.batch;

/**
 * One line of the import file, exactly as it was read.
 *
 * <p>Every field is a {@code String}, including the two that end up numeric. That is deliberate:
 * if the reader converted them, a price of {@code "twelve"} would fail during <em>reading</em>,
 * as a framework parse error with no idea what a price is, and the job would have two unrelated
 * kinds of "bad row" to explain. Keeping the line as text until
 * {@link ProductImportProcessor} looks at it means one validation step, one exception type, and
 * error messages written in the language of the catalogue rather than of {@code BigDecimal}.
 *
 * @param line the 1-based line number in the file, carried so a rejected row can be pointed at
 * @param raw the original text of the line, so the error file can quote it back verbatim
 */
public record ProductCsvRow(int line, String raw, String name, String description, String price,
        String stockQuantity, String category) {

    /** The CSV header this import expects, in order. */
    public static final String[] COLUMNS =
            {"name", "description", "price", "stock_quantity", "category"};
}
