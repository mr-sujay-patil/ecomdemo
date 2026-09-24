package com.ecomdemo.batch;

import com.ecomdemo.clients.catalog.CatalogGateway;
import com.ecomdemo.clients.inventory.InventoryGateway;
import java.nio.file.Path;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Job 1: import a product CSV.
 *
 * <h2>The shape of a chunk-oriented step</h2>
 *
 * <p>{@code chunk(n, transactionManager)} says: read n items one at a time, process each one,
 * hand the whole batch to the writer, commit, repeat. The transaction spans <em>process and
 * write</em> — the reader sits outside it, which is why a file reader can keep its place in the
 * file across a rollback. That single sentence explains almost everything else about the
 * framework: why the writer is handed a list, why the chunk size is the knob that matters, and
 * why a restart resumes at a chunk boundary rather than at an exact row.
 *
 * <p>The alternative, a <strong>tasklet</strong> step, is one method called once inside one
 * transaction — the right shape when there is no stream of items to iterate (see
 * {@code SalesReportJobConfig}, which uses one for the report's summary section).
 *
 * <h2>Fault tolerance</h2>
 *
 * <p>{@code faultTolerant()} turns on the machinery that lets a step survive a bad item. Two
 * exception types are skippable here and nothing else is: a line that does not parse, and a line
 * the catalogue rejects. The skip limit turns "a few bad rows" into a number — cross it and the
 * step fails with {@code SkipLimitExceededException}, on the principle that a file which is
 * mostly malformed is a different file and not a partial import.
 *
 * <p>A skipped item costs more than a good one: to find out which item of a chunk failed, Spring
 * Batch rolls the chunk back and replays it item by item. That is invisible in the result and
 * very visible in the timings, and it is the reason a skip limit should be a tolerance, not a
 * strategy.
 *
 * <h2>Restart</h2>
 *
 * <p>The reader saves its position in the step's ExecutionContext after every commit
 * ({@code saveState} is on by default and needs only a {@code name} to key the entries). Relaunch
 * the job with the same {@code inputFile} parameter after a failure and Spring Batch finds the
 * failed execution of that instance, restores the position, and resumes from the last committed
 * chunk. The rows of the chunk that was rolled back are read again — harmless here precisely
 * because {@link ProductImportProcessor} upserts.
 *
 * <p>The hazard worth knowing: a restart trusts the saved LINE NUMBER. Correcting bad lines in
 * place is safe; replacing the file with a different one that happens to share a path would have
 * the job resume at line 4,000 of a file it has never read. That is why the upload endpoint
 * stages each upload under a name of its own.
 */
@Configuration
@EnableConfigurationProperties(BatchProperties.class)
class ProductImportJobConfig {

    static final String IMPORT_STEP = "importProducts";

    /**
     * A job with one step. {@code start(step)} is all a linear job needs; flows, decisions and
     * parallel splits exist on the same builder and none of them is warranted here.
     */
    @Bean
    Job productImportJob(JobRepository jobRepository, Step importProductsStep) {
        return new JobBuilder(BatchJobs.PRODUCT_IMPORT, jobRepository)
                .start(importProductsStep)
                .build();
    }

    @Bean
    Step importProductsStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            FlatFileItemReader<ProductCsvRow> productCsvReader, ProductImportProcessor processor,
            ProductUpsertWriter productUpsertWriter, RejectedRowRecorder rejectedRowRecorder,
            BatchProperties properties) {
        return new StepBuilder(IMPORT_STEP, jobRepository)
                // chunk(size) and a separate transactionManager(...), not the one-call
                // chunk(size, transactionManager): that overload builds the pre-6.0
                // implementation, which Spring Batch 6 deprecates and 7 removes. It still works,
                // and it announces itself in the log on every startup.
                .<ProductCsvRow, ImportedProduct>chunk(properties.chunkSize())
                .transactionManager(transactionManager)
                .reader(productCsvReader)
                .processor(processor)
                .writer(productUpsertWriter)
                .faultTolerant()
                .skip(FlatFileParseException.class, InvalidProductRowException.class)
                .skipLimit(properties.skipLimit())
                .skipListener(rejectedRowRecorder)
                .listener(productUpsertWriter)
                .build();
    }

    /**
     * {@code @StepScope} is what lets a bean see the job's parameters: the bean is created when
     * the step starts, and {@code #{jobParameters[...]}} is resolved against that execution. A
     * singleton reader would have to be told its file some other way, and two concurrent imports
     * would share it.
     */
    @Bean
    @StepScope
    FlatFileItemReader<ProductCsvRow> productCsvReader(
            @Value("#{jobParameters['" + BatchJobs.PARAM_INPUT_FILE + "']}") String inputFile) {
        return new FlatFileItemReaderBuilder<ProductCsvRow>()
                // The name is not cosmetic: it prefixes the keys this reader writes into the
                // ExecutionContext, and without it the reader refuses to save state at all -
                // which would silently turn restart into "start again from line 1".
                .name("productCsvReader")
                .resource(new FileSystemResource(inputFile))
                // The header line names the columns for a human; the tokenizer is configured in
                // code, so to the reader it is simply a line to ignore.
                .linesToSkip(1)
                .lineMapper(new ProductCsvLineMapper())
                // Fail if the file is not there, rather than treating it as empty and reporting
                // a COMPLETED import of nothing.
                .strict(true)
                .build();
    }

    @Bean
    @StepScope
    ProductUpsertWriter productUpsertWriter(CatalogGateway catalogue, InventoryGateway inventory) {
        return new ProductUpsertWriter(catalogue, inventory);
    }

    /**
     * The error file sits beside the upload it came from, named after it. Deriving it rather than
     * passing it as a second job parameter keeps the import job's identity down to one thing —
     * the file being imported — which is what makes "re-run with the same parameters" mean
     * "restart this import" and nothing else.
     */
    @Bean
    @StepScope
    RejectedRowRecorder rejectedRowRecorder(
            @Value("#{jobParameters['" + BatchJobs.PARAM_INPUT_FILE + "']}") String inputFile) {
        return new RejectedRowRecorder(errorFileFor(Path.of(inputFile)));
    }

    /** The error file for an upload, as both the job and the API compute it. */
    static Path errorFileFor(Path inputFile) {
        return inputFile.resolveSibling(inputFile.getFileName() + ".errors.csv");
    }
}
