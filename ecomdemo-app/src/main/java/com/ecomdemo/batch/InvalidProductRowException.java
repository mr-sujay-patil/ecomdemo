package com.ecomdemo.batch;

/**
 * A row that the catalogue will not accept: a missing name, a price that is not a number, a
 * negative stock count.
 *
 * <p>This is the type the import step declares as skippable, and giving it a type of its own is
 * the whole point. "Skip bad data" and "swallow bugs" look identical from a distance:
 * {@code .skip(Exception.class)} would also skip a {@code NullPointerException} in the processor
 * and a connection failure in the writer, and the job would report a tidy COMPLETED over a
 * broken system. A narrow exception thrown only by validation keeps the skip policy honest —
 * anything else still fails the job.
 */
public class InvalidProductRowException extends RuntimeException {

    private final transient ProductCsvRow row;

    public InvalidProductRowException(ProductCsvRow row, String reason) {
        super("line %d: %s".formatted(row.line(), reason));
        this.row = row;
    }

    public ProductCsvRow row() {
        return row;
    }
}
