package com.ecomdemo.batch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemWriter;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Job 2: one day's sales, as a CSV.
 *
 * <h2>Two steps, on purpose</h2>
 *
 * <p>This job exists partly to show the other kind of step. The summary is a
 * <strong>tasklet</strong>: two aggregate queries and a few lines of file, done once, inside one
 * transaction. There is no stream of items to iterate, so the chunk machinery — a reader that
 * returns one item at a time, a commit every n of them, a saved position to restart from — would
 * be pure ceremony.
 *
 * <p>The best-seller table is a <strong>chunk</strong> step, because it is a stream: however many
 * products sold that day, read one row at a time out of a database cursor and written out in
 * batches. Ten rows today; on a real catalogue, more than fits in memory. The shape does not have
 * to change for that to be true, which is the argument for using it even when today's data is
 * small.
 *
 * <p>Steps run in order and either can fail independently. On a restart Spring Batch skips steps
 * that already COMPLETED, so a failure in the second step resumes there and does not write the
 * summary twice.
 *
 * <h2>The day boundary</h2>
 *
 * <p>{@code placed_at} is a {@code TIMESTAMP WITH TIME ZONE}, so "Tuesday" is not a property of
 * the column — it is a half-open range {@code [start of Tuesday, start of Wednesday)} in some
 * zone. The zone is the JVM's, which is the same one the cron fires in, and the range is built
 * once here so both queries agree on it. Half-open rather than {@code BETWEEN}: an order placed
 * at exactly midnight belongs to one day, not to both.
 */
@Configuration
class SalesReportJobConfig {

    private static final Logger log = LoggerFactory.getLogger(SalesReportJobConfig.class);

    static final String SUMMARY_STEP = "writeSalesSummary";
    static final String TOP_PRODUCTS_STEP = "writeTopProducts";

    /** How many best sellers the report lists. */
    private static final int TOP_N = 10;

    /** Keys the summary step leaves in the job's ExecutionContext for the next step and for tests. */
    static final String CONTEXT_ORDERS = "sales.orders";
    static final String CONTEXT_REVENUE = "sales.revenue";
    static final String CONTEXT_REPORT_FILE = "sales.reportFile";

    private static final String SUMMARY_SQL = """
            SELECT count(*) AS order_count, coalesce(sum(total_amount), 0) AS revenue
            FROM orders
            WHERE placed_at >= ? AND placed_at < ?
            """;

    private static final String TOP_PRODUCTS_SQL = """
            SELECT oi.product_id       AS product_id,
                   oi.product_name     AS product_name,
                   sum(oi.quantity)    AS units_sold,
                   sum(oi.unit_price * oi.quantity) AS revenue
            FROM order_item oi
            JOIN orders o ON o.id = oi.order_id
            WHERE o.placed_at >= ? AND o.placed_at < ?
            GROUP BY oi.product_id, oi.product_name
            ORDER BY units_sold DESC, revenue DESC, product_name ASC
            FETCH FIRST %d ROWS ONLY
            """.formatted(TOP_N);

    @Bean
    Job salesReportJob(JobRepository jobRepository, Step writeSalesSummaryStep,
            Step writeTopProductsStep) {
        return new JobBuilder(BatchJobs.SALES_REPORT, jobRepository)
                .start(writeSalesSummaryStep)
                .next(writeTopProductsStep)
                .build();
    }

    /**
     * A tasklet step: {@link Tasklet#execute} is called once and returns
     * {@link RepeatStatus#FINISHED}. Returning {@code CONTINUABLE} instead would have Spring Batch
     * call it again in a new transaction — the way to write a loop whose end only the tasklet
     * knows.
     */
    @Bean
    Step writeSalesSummaryStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager, JdbcTemplate jdbcTemplate,
            BatchProperties properties) {
        return new StepBuilder(SUMMARY_STEP, jobRepository)
                .tasklet(summaryTasklet(jdbcTemplate, properties), transactionManager)
                .build();
    }

    private Tasklet summaryTasklet(JdbcTemplate jdbcTemplate, BatchProperties properties) {
        return (contribution, chunkContext) -> {
            LocalDate day = reportDate(chunkContext.getStepContext().getJobParameters());
            OffsetDateTime from = startOfDay(day);
            OffsetDateTime to = startOfDay(day.plusDays(1));

            DailySales sales = jdbcTemplate.queryForObject(SUMMARY_SQL,
                    (rs, rowNum) -> new DailySales(rs.getLong("order_count"),
                            rs.getBigDecimal("revenue")),
                    from, to);
            DailySales summary = sales == null ? new DailySales(0, BigDecimal.ZERO) : sales;

            Path report = reportFileFor(properties.reportDirectory(), day);
            writeHeader(report, day, summary);

            // The ExecutionContext is how steps talk to each other - and it is persisted, so
            // these values survive into the BATCH_JOB_EXECUTION_CONTEXT row and can be read back
            // long after the job is over. The JOB context (rather than the step's) is what the
            // next step and the tests can see.
            var jobContext = chunkContext.getStepContext().getStepExecution().getJobExecution()
                    .getExecutionContext();
            jobContext.putLong(CONTEXT_ORDERS, summary.orders());
            jobContext.putString(CONTEXT_REVENUE, summary.revenue().toPlainString());
            jobContext.putString(CONTEXT_REPORT_FILE, report.toAbsolutePath().toString());

            log.info("sales report for {}: {} orders, revenue {} -> {}", day, summary.orders(),
                    summary.revenue(), report);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step writeTopProductsStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcCursorItemReader<TopProduct> topProductReader,
            FlatFileItemWriter<TopProduct> topProductWriter, BatchProperties properties) {
        return new StepBuilder(TOP_PRODUCTS_STEP, jobRepository)
                .<TopProduct, TopProduct>chunk(properties.chunkSize(), transactionManager)
                .reader(topProductReader)
                // No processor. An ItemProcessor is optional, and a step that only moves rows
                // from a query to a file has nothing to transform - an identity processor would
                // be a class that exists to be mentioned.
                .writer(topProductWriter)
                .build();
    }

    /**
     * A cursor reader holds one open {@code ResultSet} and walks it, so the rows never all exist
     * in memory at once. The alternative in the same package, a paging reader, re-issues the
     * query with an offset per page — cheaper on connections, and it needs a stable sort to avoid
     * losing rows when the underlying data changes between pages.
     */
    @Bean
    @StepScope
    JdbcCursorItemReader<TopProduct> topProductReader(DataSource dataSource,
            @Value("#{jobParameters['" + BatchJobs.PARAM_REPORT_DATE + "']}") LocalDate day) {
        return new JdbcCursorItemReaderBuilder<TopProduct>()
                .name("topProductReader")
                .dataSource(dataSource)
                .sql(TOP_PRODUCTS_SQL)
                .queryArguments(startOfDay(day), startOfDay(day.plusDays(1)))
                .rowMapper((rs, rowNum) -> new TopProduct(
                        rs.getLong("product_id"),
                        rs.getString("product_name"),
                        rs.getLong("units_sold"),
                        rs.getBigDecimal("revenue")))
                .build();
    }

    /**
     * {@code append(true)} because the tasklet already wrote the header block; without it the
     * writer would truncate the file it is supposed to be adding to. It is also what makes a
     * restart of this step resume rather than start the table again.
     */
    @Bean
    @StepScope
    FlatFileItemWriter<TopProduct> topProductWriter(BatchProperties properties,
            @Value("#{jobParameters['" + BatchJobs.PARAM_REPORT_DATE + "']}") LocalDate day) {
        return new FlatFileItemWriterBuilder<TopProduct>()
                .name("topProductWriter")
                .resource(new FileSystemResource(
                        reportFileFor(properties.reportDirectory(), day).toFile()))
                .append(true)
                .lineAggregator(product -> "%d,%s,%d,%s".formatted(
                        product.productId(),
                        csv(product.productName()),
                        product.unitsSold(),
                        product.revenue().toPlainString()))
                .build();
    }

    /** Where a day's report is written. Shared by both steps and by the tests. */
    static Path reportFileFor(Path reportDirectory, LocalDate day) {
        return reportDirectory.resolve("sales-%s.csv".formatted(day));
    }

    private static LocalDate reportDate(java.util.Map<String, Object> jobParameters) {
        Object value = jobParameters.get(BatchJobs.PARAM_REPORT_DATE);
        if (value instanceof LocalDate date) {
            return date;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static OffsetDateTime startOfDay(LocalDate day) {
        return day.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static void writeHeader(Path report, LocalDate day, DailySales summary) {
        String header = """
                # EcomDemo daily sales report
                # date,%s
                # orders,%d
                # revenue,%s
                product_id,product_name,units_sold,revenue
                """.formatted(day, summary.orders(), summary.revenue().toPlainString());
        try {
            Files.createDirectories(report.toAbsolutePath().getParent());
            Files.writeString(report, header, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the sales report " + report, e);
        }
    }

    /** Quotes a product name so a comma in it cannot shift the columns. */
    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
