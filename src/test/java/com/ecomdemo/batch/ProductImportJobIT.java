package com.ecomdemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.batch.dto.ProductImportResponse;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.support.IntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The import job, end to end: a real file uploaded over real HTTP into a real PostgreSQL.
 *
 * <p>This is where the phase's "Done when" is proven — ten thousand rows, with the invalid ones
 * skipped — and where restart stops being a diagram. Nothing here stubs the framework: the job
 * runs through the same {@code JobOperator}, writes the same {@code BATCH_*} rows and reads the
 * same staged file that a production upload would.
 *
 * <p>Every product this class creates is named {@code "IT Import ..."} and deleted afterwards
 * with one SQL statement. Ten thousand rows left behind would not break the other integration
 * tests, but they would make every catalogue listing in the rest of the run haul them over HTTP
 * and through the cache.
 */
class ProductImportJobIT extends IntegrationTest {

    private static final String PREFIX = "IT Import ";
    private static final String HEADER = "name,description,price,stock_quantity,category";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CacheManager cacheManager;

    private TestRestTemplate admin;

    private final List<Path> writtenFiles = new ArrayList<>();

    @BeforeEach
    void signIn() {
        admin = asAdmin();
        removeImportedProducts();
    }

    @AfterEach
    void cleanUp() {
        removeImportedProducts();
        writtenFiles.forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        writtenFiles.clear();
    }

    private void removeImportedProducts() {
        jdbc.update("DELETE FROM product WHERE name LIKE ?", PREFIX + "%");
        // The listing cache would otherwise keep serving the products this class just deleted to
        // whichever test class runs next.
        evictTheCatalogueListing();
    }

    private void evictTheCatalogueListing() {
        var listing = cacheManager.getCache(com.ecomdemo.cache.CacheNames.PRODUCT_LIST);
        if (listing != null) {
            listing.evict("all");
        }
    }

    @Test
    @DisplayName("ten thousand rows import, the invalid ones are skipped and listed in the error file")
    void importsTenThousandRowsAndSkipsTheInvalidOnes() throws IOException {
        int validRows = 10_000;
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (int i = 1; i <= validRows; i++) {
            csv.append("%s%05d,row %d,%d.99,%d,BULK%n".formatted(PREFIX, i, i, 10 + (i % 90), i % 50));
        }
        // Seven rows that the catalogue will not take, one per rule, plus one the tokenizer
        // cannot even split. They are appended rather than interleaved so the counts below are
        // easy to reason about; the restart test below mixes them in.
        List<String> badRows = List.of(
                ",no name,9.99,1,BAD",
                PREFIX + "bad price,x,twelve,1,BAD",
                PREFIX + "negative price,x,-1.00,1,BAD",
                PREFIX + "zero price,x,0.00,1,BAD",
                PREFIX + "too precise,x,1.005,1,BAD",
                PREFIX + "bad stock,x,9.99,four,BAD",
                PREFIX + "negative stock,x,9.99,-2,BAD",
                PREFIX + "too few columns,9.99");
        badRows.forEach(row -> csv.append(row).append('\n'));

        // Prime the listing cache, so the assertion at the end is about eviction and not about a
        // cache that happened to be empty.
        admin.getForEntity("/api/products", ProductResponse[].class);

        ProductImportResponse response = upload(csv.toString());

        assertThat(response.execution().status()).isEqualTo("COMPLETED");
        assertThat(response.execution().readCount())
                .as("the eight bad rows include one the reader could not parse, which is never read")
                .isEqualTo(validRows + badRows.size() - 1);
        assertThat(response.execution().writeCount()).isEqualTo(validRows);
        assertThat(response.execution().skipCount()).isEqualTo(badRows.size());

        // Committed in chunks of 100, so a hundred transactions rather than one. That is the
        // chunk size doing its job, and it is the number to look at when tuning: one transaction
        // for the whole file would hold locks for its duration and lose everything on the last
        // row, and one per row would pay a commit ten thousand times.
        assertThat(response.execution().steps()).singleElement()
                .satisfies(step -> {
                    assertThat(step.name()).isEqualTo(ProductImportJobConfig.IMPORT_STEP);
                    assertThat(step.commitCount()).isGreaterThan(50);
                    // Note what is NOT asserted: rollbacks. Spring Batch 6's chunk-oriented step
                    // handles a skippable item as it meets it, where the pre-6.0 implementation
                    // rolled the chunk back and replayed it item by item to find the culprit.
                    // Same outcome, considerably less work - and a rollback count of zero on a
                    // run with skips, which under the old model would have been a bug.
                    assertThat(step.rollbackCount()).isZero();
                });

        assertThat(countImported()).isEqualTo(validRows);

        // Every rejection is written down, with the line it came from.
        assertThat(response.errorFile()).isNotNull();
        List<String> errors = Files.readAllLines(Path.of(response.errorFile()));
        assertThat(errors).hasSize(badRows.size() + 1);
        assertThat(errors.get(0)).isEqualTo("line,reason,original_line");
        assertThat(errors).anyMatch(line -> line.contains("price 'twelve' is not a number"))
                .anyMatch(line -> line.contains("name is required"))
                .anyMatch(line -> line.contains("stock_quantity '-2' must not be negative"));

        // And the catalogue the API serves is the one the import produced, not the cached one
        // from before it.
        ResponseEntity<ProductResponse[]> listing =
                admin.getForEntity("/api/products", ProductResponse[].class);
        assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listing.getBody())
                .anyMatch(product -> (PREFIX + "00001").equals(product.name()));
    }

    @Test
    @DisplayName("re-importing the same catalogue updates the products rather than duplicating them")
    void reimportUpdatesRatherThanDuplicates() {
        String first = HEADER + "\n" + PREFIX + "Repeat,first,10.00,5,A\n";
        String second = HEADER + "\n" + PREFIX + "Repeat,second,20.00,9,B\n";

        assertThat(upload(first).execution().writeCount()).isEqualTo(1);
        assertThat(countImported()).isEqualTo(1);

        // A second upload is a NEW JobInstance - the staged path differs - so it runs rather than
        // being refused. What stops it duplicating the product is the upsert, not the framework.
        assertThat(upload(second).execution().writeCount()).isEqualTo(1);
        assertThat(countImported()).as("still one product, updated in place").isEqualTo(1);

        assertThat(jdbc.queryForObject(
                        "SELECT description FROM product WHERE name = ?", String.class,
                        PREFIX + "Repeat"))
                .isEqualTo("second");
    }

    @Test
    @DisplayName("too many bad rows fails the job, and a restart resumes where it stopped")
    void failsOnTheSkipLimitAndRestartsWhereItStopped() throws IOException {
        // 250 good rows, then 60 the catalogue refuses. The default skip limit is 50, so the job
        // gets through the 250 - committing them in chunks of 100 - tolerates 50 bad ones, and
        // then dies on the 51st. Nothing is injected to make this happen: it is a plausibly bad
        // file, which is the point.
        int goodRows = 250;
        int badRows = 60;

        ProductImportResponse failed = upload(csvWithBadTail(goodRows, badRows, false));

        assertThat(failed.execution().status()).isEqualTo("FAILED");
        assertThat(failed.execution().failureMessage()).contains("Skip limit");
        assertThat(failed.execution().skipCount()).isEqualTo(50);
        assertThat(failed.execution().writeCount())
                .as("the chunks that committed before the limit was hit survive the failure")
                .isEqualTo(goodRows);
        assertThat(countImported()).isEqualTo(goodRows);

        // The operator fixes the offending lines IN PLACE, in the STAGED file the job actually
        // read - same path, same line count - and restarts. The path matters twice over: it is
        // the job's identifying parameter, so a different path would be a different import
        // altogether, and the saved position is a line count, so a file with different lines
        // before it would have the reader resume in the wrong place.
        Files.writeString(Path.of(failed.inputFile()), csvWithBadTail(goodRows, badRows, true),
                StandardCharsets.UTF_8);

        ResponseEntity<ProductImportResponse> restart = admin.postForEntity(
                "/api/admin/batch/executions/{id}/restart", null, ProductImportResponse.class,
                failed.execution().id());
        assertThat(restart.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProductImportResponse resumed = restart.getBody();
        assertThat(resumed).isNotNull();

        assertThat(resumed.execution().status()).isEqualTo("COMPLETED");
        assertThat(resumed.execution().instanceId())
                .as("a restart is a second EXECUTION of the same INSTANCE")
                .isEqualTo(failed.execution().instanceId());
        assertThat(resumed.execution().id()).isNotEqualTo(failed.execution().id());

        // It resumed rather than starting again: only the tail the first run never reached was
        // read the second time.
        assertThat(resumed.execution().readCount()).isPositive().isLessThan(goodRows);

        // And here is the part of restart that a diagram never shows. A restart resumes from the
        // last COMMIT, and the 50 rows that were SKIPPED are behind it - they were read, rejected
        // and committed past. Restarting does not reconsider them however well the file is fixed.
        // What the restart recovers is the work that was never done, not the work that was
        // refused.
        long recovered = countImported();
        assertThat(recovered)
                .as("the 250 committed rows plus the tail the failure stopped it reaching")
                .isEqualTo(goodRows + badRows - failed.execution().skipCount());
        assertThat(recovered).isLessThan(goodRows + badRows);

        // The rejected rows are not lost, though: they are in the error file, and re-importing
        // the corrected file as a NEW upload picks them up - safely, because the import upserts,
        // so the 260 products already there are updated rather than duplicated.
        assertThat(failed.errorFile()).isNotNull();
        assertThat(Files.readAllLines(Path.of(failed.errorFile())))
                .as("one header plus one line per rejected row")
                .hasSize((int) failed.execution().skipCount() + 1);

        ProductImportResponse reimport = upload(csvWithBadTail(goodRows, badRows, true));
        assertThat(reimport.execution().status()).isEqualTo("COMPLETED");
        assertThat(countImported()).isEqualTo(goodRows + badRows);
    }

    @Test
    @DisplayName("a run that succeeded cannot be restarted")
    void refusesToRestartACompletedRun() {
        ProductImportResponse completed =
                upload(HEADER + "\n" + PREFIX + "Done,x,1.00,1,A\n");
        assertThat(completed.execution().status()).isEqualTo("COMPLETED");

        ResponseEntity<String> refused = admin.postForEntity(
                "/api/admin/batch/executions/{id}/restart", null, String.class,
                completed.execution().id());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("only a FAILED execution can be restarted");
    }

    @Test
    @DisplayName("an execution can be read back out of the JobRepository afterwards")
    void looksUpAnExecutionAfterTheFact() {
        ProductImportResponse response = upload(HEADER + "\n" + PREFIX + "Lookup,x,1.00,1,A\n");

        ResponseEntity<com.ecomdemo.batch.dto.JobExecutionResponse> looked = admin.getForEntity(
                "/api/admin/batch/executions/{id}", com.ecomdemo.batch.dto.JobExecutionResponse.class,
                response.execution().id());

        assertThat(looked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(looked.getBody()).isNotNull();
        assertThat(looked.getBody().jobName()).isEqualTo(BatchJobs.PRODUCT_IMPORT);
        assertThat(looked.getBody().writeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a CUSTOMER cannot import a catalogue")
    void refusesACustomer() {
        TestRestTemplate shopper = asCustomer("it-batch-shopper");

        ResponseEntity<String> refused = shopper.postForEntity(
                "/api/admin/batch/product-import",
                multipart(HEADER + "\n" + PREFIX + "Nope,x,1.00,1,A\n"), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(countImported()).isZero();
    }

    /** A file whose tail is either 60 rejected rows or the same 60 rows corrected. */
    private String csvWithBadTail(int goodRows, int badRows, boolean fixed) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (int i = 1; i <= goodRows; i++) {
            csv.append("%s%05d,row %d,%d.00,%d,RESTART%n".formatted(PREFIX, i, i, 10 + i, i));
        }
        for (int i = goodRows + 1; i <= goodRows + badRows; i++) {
            csv.append("%s%05d,row %d,%s,%d,RESTART%n"
                    .formatted(PREFIX, i, i, fixed ? "%d.00".formatted(10 + i) : "not-a-price", i));
        }
        return csv.toString();
    }

    private long countImported() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM product WHERE name LIKE ?", Long.class, PREFIX + "%");
        return count == null ? 0 : count;
    }

    private ProductImportResponse upload(String csv) {
        ResponseEntity<ProductImportResponse> response = admin.postForEntity(
                "/api/admin/batch/product-import", multipart(csv), ProductImportResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProductImportResponse body = response.getBody();
        assertThat(body).isNotNull();
        rememberStagedFiles(body);
        return body;
    }

    private void rememberStagedFiles(ProductImportResponse response) {
        writtenFiles.add(Path.of(response.inputFile()));
        if (response.errorFile() != null) {
            writtenFiles.add(Path.of(response.errorFile()));
        }
    }

    private HttpEntity<MultiValueMap<String, Object>> multipart(String csv) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "products.csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }
}
