package com.ecomdemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

/**
 * Unit tests for the error file.
 *
 * <p>Worth testing on its own because it is the only output of a skipped row. A skip that is
 * counted but not written down is data quietly dropped, and the failure mode — an error file
 * that is empty, or unparseable, or missing the line number — is invisible from the job's status.
 */
class RejectedRowRecorderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a row the catalogue refused is written with its line, reason and original text")
    void recordsAProcessingRejection() throws IOException {
        Path errorFile = tempDir.resolve("products.csv.errors.csv");
        RejectedRowRecorder recorder = new RejectedRowRecorder(errorFile);
        ProductCsvRow row = new ProductCsvRow(42, "Widget,,nope,4,TEST", "Widget", "", "nope", "4",
                "TEST");

        recorder.onSkipInProcess(row, new InvalidProductRowException(row, "price 'nope' is not a number"));

        List<String> lines = Files.readAllLines(errorFile);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo("line,reason,original_line");
        assertThat(lines.get(1))
                .isEqualTo("42,\"line 42: price 'nope' is not a number\",\"Widget,,nope,4,TEST\"");
    }

    @Test
    @DisplayName("a line that could not be parsed is recorded from the parse exception instead")
    void recordsAReadRejection() throws IOException {
        Path errorFile = tempDir.resolve("products.csv.errors.csv");
        RejectedRowRecorder recorder = new RejectedRowRecorder(errorFile);

        recorder.onSkipInRead(new FlatFileParseException(
                "Parsing error at line: 9", new IllegalArgumentException("wrong number of columns"),
                "only,three,columns", 9));

        assertThat(Files.readAllLines(errorFile).get(1))
                .startsWith("9,\"wrong number of columns\",\"only,three,columns\"");
    }

    @Test
    @DisplayName("the header is written once, however many rows are rejected")
    void writesTheHeaderOnce() throws IOException {
        Path errorFile = tempDir.resolve("products.csv.errors.csv");
        RejectedRowRecorder recorder = new RejectedRowRecorder(errorFile);

        for (int line = 1; line <= 3; line++) {
            ProductCsvRow row = new ProductCsvRow(line, "raw", "", "", "1.00", "1", null);
            recorder.onSkipInProcess(row, new InvalidProductRowException(row, "name is required"));
        }

        assertThat(Files.readAllLines(errorFile)).hasSize(4);
    }

    @Test
    @DisplayName("nothing is created when nothing is rejected, so the file's existence is the signal")
    void createsNoFileWhenThereAreNoRejections() {
        Path errorFile = tempDir.resolve("products.csv.errors.csv");
        new RejectedRowRecorder(errorFile);

        assertThat(errorFile).doesNotExist();
    }

    @Test
    @DisplayName("a reason or a line containing a quote cannot break the CSV")
    void quotesEmbeddedQuotes() throws IOException {
        Path errorFile = tempDir.resolve("products.csv.errors.csv");
        RejectedRowRecorder recorder = new RejectedRowRecorder(errorFile);
        ProductCsvRow row = new ProductCsvRow(1, "Laptop Sleeve 16\",x,1.00,1,", "Laptop Sleeve 16\"",
                "", "1.00", "1", null);

        recorder.onSkipInProcess(row, new InvalidProductRowException(row, "say \"what\""));

        assertThat(Files.readAllLines(errorFile).get(1))
                .contains("\"\"what\"\"")
                .contains("\"Laptop Sleeve 16\"\",x,1.00,1,\"");
    }
}
