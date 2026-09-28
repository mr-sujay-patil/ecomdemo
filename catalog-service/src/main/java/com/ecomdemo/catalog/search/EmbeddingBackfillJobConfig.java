package com.ecomdemo.catalog.search;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.boot.batch.autoconfigure.BatchTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The embedding backfill: every product, embedded again, twenty at a time (Phase 28).
 *
 * <p>The event-driven indexer keeps the index current from the moment it runs. Everything before
 * that moment needs this: the ten seeded products, a catalogue from before Phase 28, products
 * changed while no model was configured, events dead-lettered during a model outage, or a switch to
 * a different embedding model (whose vectors cannot be compared with the old ones).
 *
 * <h2>Why a chunk-oriented job and not a loop</h2>
 *
 * <ul>
 *   <li><strong>Chunks</strong>: twenty products are embedded in ONE model call and written in one
 *       transaction. Per-product calls would be twenty round trips, and one big call for ten
 *       thousand products would exceed the model's input limit.
 *   <li><strong>Keyset paging</strong> ({@code WHERE id > :last ORDER BY id}): each page starts after
 *       the last id read, so products added or deleted mid-run shift nothing, and page 500 costs
 *       what page 1 does. OFFSET paging would skip or repeat rows under concurrent edits.
 *   <li><strong>A JDBC JobRepository</strong> ({@code spring-boot-starter-batch-jdbc}): the reader
 *       saves its position after every chunk, so a run that fails at product 6000 can be restarted
 *       from there, and any catalog pod can report on a run another pod started.
 * </ul>
 *
 * <p>Idempotent by construction - every write is an upsert of the product's own row - so running it
 * twice, or while the indexer is busy, is wasteful at worst and never wrong.
 *
 * <p>One transaction per chunk DOES stay open during that chunk's model call, which Phase 27 avoided
 * for the admin's request. Here it is one connection, for one background job, for about a second
 * per chunk; the pool has ten.
 */
@Configuration
class EmbeddingBackfillJobConfig {

    static final String JOB = "productEmbeddingBackfill";
    static final String STEP = "embedProducts";
    static final int CHUNK_SIZE = 20;

    @Bean
    Job productEmbeddingBackfill(JobRepository jobRepository, Step embedProducts) {
        return new JobBuilder(JOB, jobRepository).start(embedProducts).build();
    }

    @Bean
    Step embedProducts(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<IndexedProduct> productReader, ProductSearchIndex index) {
        ItemWriter<IndexedProduct> writer = chunk -> index.upsert(chunk.getItems());
        return new StepBuilder(STEP, jobRepository)
                .<IndexedProduct, IndexedProduct>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(productReader)
                .writer(writer)
                .build();
    }

    /** Step-scoped: a paging reader holds its position, so each run needs a fresh one. */
    @Bean
    @StepScope
    JdbcPagingItemReader<IndexedProduct> productReader(DataSource dataSource) throws Exception {
        return new JdbcPagingItemReaderBuilder<IndexedProduct>()
                .name("productReader")
                .dataSource(dataSource)
                .selectClause("SELECT id, name, description, category, price")
                .fromClause("FROM product")
                .sortKeys(Map.of("id", Order.ASCENDING))
                .pageSize(CHUNK_SIZE)
                .rowMapper((rs, row) -> new IndexedProduct(rs.getLong("id"), rs.getString("name"),
                        rs.getString("description"), rs.getString("category"), rs.getBigDecimal("price")))
                .build();
    }

    /**
     * Makes {@code JobOperator.start} return at once, with the job running on this executor. A
     * backfill of a real catalogue takes minutes; an HTTP request must not wait for it, so the
     * endpoint answers 202 with an execution id to ask about later. The only job in this service,
     * so nothing else changes behaviour.
     */
    @Bean
    @BatchTaskExecutor
    TaskExecutor backfillTaskExecutor() {
        return new SimpleAsyncTaskExecutor("embedding-backfill-");
    }
}
