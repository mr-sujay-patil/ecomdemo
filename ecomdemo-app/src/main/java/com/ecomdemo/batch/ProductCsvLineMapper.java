package com.ecomdemo.batch;

import org.springframework.batch.infrastructure.item.file.LineMapper;
import org.springframework.batch.infrastructure.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.infrastructure.item.file.transform.FieldSet;

/**
 * Turns one line of text into a {@link ProductCsvRow}.
 *
 * <p>Spring Batch ships a {@code DefaultLineMapper} that composes a tokenizer with a field-set
 * mapper, and it would do most of this in two lines of configuration. It is not used here for
 * one reason: {@link LineMapper#mapLine(String, int)} is handed the line NUMBER, and the
 * composed mapper throws it away. A rejected row that cannot say which line it came from is of
 * very little use to whoever has to fix the file.
 *
 * <p>The tokenizer is left {@code strict}, its default. A line with the wrong number of columns
 * therefore raises {@code IncorrectTokenCountException}, which the reader wraps in a
 * {@code FlatFileParseException} — the second of the two skippable failures the import step
 * declares. Turning strictness off would pad the missing columns with empty strings and quietly
 * import a shifted row.
 */
class ProductCsvLineMapper implements LineMapper<ProductCsvRow> {

    private final DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();

    ProductCsvLineMapper() {
        tokenizer.setNames(ProductCsvRow.COLUMNS);
    }

    @Override
    public ProductCsvRow mapLine(String line, int lineNumber) {
        FieldSet fields = tokenizer.tokenize(line);
        return new ProductCsvRow(
                lineNumber,
                line,
                fields.readString("name"),
                fields.readString("description"),
                fields.readString("price"),
                fields.readString("stock_quantity"),
                fields.readString("category"));
    }
}
