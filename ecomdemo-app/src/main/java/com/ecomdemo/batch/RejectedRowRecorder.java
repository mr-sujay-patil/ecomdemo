package com.ecomdemo.batch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

/**
 * Writes every rejected row to an error file, so a skip is something somebody can act on.
 *
 * <p>A skip limit without an error file is data loss with a counter attached: the job reports
 * "COMPLETED, 37 skipped" and nobody can say which 37 or why. The file this writes sits next to
 * the upload as {@code <upload>.errors.csv} and holds the line number, the reason, and the
 * original text of the line — enough to fix the source file and re-upload it.
 *
 * <p>Both ways a row can be rejected are covered, and they arrive through different callbacks:
 *
 * <ul>
 *   <li>{@link #onSkipInRead(Throwable)} — the line could not even be split into the five
 *       expected columns. The row object does not exist yet, so the detail comes out of the
 *       {@link FlatFileParseException}, which carries the offending line and its number.
 *   <li>{@link #onSkipInProcess(ProductCsvRow, Throwable)} — the line parsed but the catalogue
 *       refused it. Here the item itself is handed over, which is why {@link ProductCsvRow}
 *       carries its line number and raw text.
 * </ul>
 *
 * <p>There is no {@code onSkipInWrite}: the writer's failures are database failures, and none of
 * them is skippable in this step. A row that reaches the writer is either saved or fails the job.
 *
 * <p><strong>The file is opened and closed per rejection.</strong> That looks wasteful and is
 * deliberate: the number of writes is bounded by the skip limit (50 by default), and a listener
 * that held a stream open would need somewhere to close it on every path out of the step —
 * including the one where the step fails. It also means the partial error file survives a job
 * that dies, which is exactly when somebody wants to read it.
 *
 * <p>Note that this write is NOT part of the chunk transaction. Spring Batch calls skip
 * listeners outside it on purpose — a rollback must not erase the record of why a row was
 * rejected.
 */
class RejectedRowRecorder implements SkipListener<ProductCsvRow, Object> {

    private static final Logger log = LoggerFactory.getLogger(RejectedRowRecorder.class);

    /** The header written once, when the file is created. */
    private static final String HEADER = "line,reason,original_line" + System.lineSeparator();

    private final Path errorFile;

    RejectedRowRecorder(Path errorFile) {
        this.errorFile = errorFile;
    }

    /** Where the rejected rows are written. Surfaced in the API response. */
    Path errorFile() {
        return errorFile;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        if (t instanceof FlatFileParseException parse) {
            record(parse.getLineNumber(), rootMessage(parse), parse.getInput());
        } else {
            record(0, rootMessage(t), "");
        }
    }

    @Override
    public void onSkipInProcess(ProductCsvRow row, Throwable t) {
        record(row.line(), rootMessage(t), row.raw());
    }

    private void record(int line, String reason, String original) {
        log.warn("import rejected line {}: {}", line, reason);
        try {
            if (Files.notExists(errorFile)) {
                Files.createDirectories(errorFile.toAbsolutePath().getParent());
                Files.writeString(errorFile, HEADER, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }
            Files.writeString(errorFile,
                    "%d,%s,%s%s".formatted(line, quote(reason), quote(original),
                            System.lineSeparator()),
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Losing the error file must not be quieter than losing the row. Failing here fails
            // the step, which is the honest outcome: the job can no longer say what it skipped.
            throw new UncheckedIOException("cannot write the import error file " + errorFile, e);
        }
    }

    /** The message that explains the rejection, which for a wrapped parse failure is the cause's. */
    private static String rootMessage(Throwable t) {
        Throwable cause = t.getCause() == null ? t : t.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /** Minimal CSV quoting, so a reason or a line containing a comma cannot shift the columns. */
    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"").replace("\n", " ").replace("\r", "") + '"';
    }
}
