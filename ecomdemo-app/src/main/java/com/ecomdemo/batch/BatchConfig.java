package com.ecomdemo.batch;

import javax.sql.DataSource;
import org.springframework.batch.core.configuration.BatchConfigurationException;
import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Makes the JobRepository a <strong>database</strong> repository.
 *
 * <h2>Why this class has to exist</h2>
 *
 * <p>This is the single most surprising thing about Spring Batch 6, and it is silent. The default
 * {@code JobRepository} — the one Spring Boot's auto-configuration gives you — is
 * {@code ResourcelessJobRepository}: an in-memory stub that records nothing. Everything still
 * works. Jobs run, steps report counters, an instance that completed a moment ago is refused a
 * second time. And none of it is written down: restart the application and the framework has
 * forgotten every run it ever made.
 *
 * <p>That is the whole value proposition gone, quietly. Without persistence there is no restart
 * after a crash (the saved reader position died with the process), no "this file was already
 * imported last Tuesday", no record to look an execution up in. The
 * {@code BATCH_*} tables would sit there, created by Flyway, permanently empty — which is exactly
 * what happened here before this class was written, and what the integration tests now catch.
 *
 * <h2>How it takes over</h2>
 *
 * <p>Boot's {@code BatchAutoConfiguration} backs off entirely when the application supplies its
 * own {@link DefaultBatchConfiguration} subclass — that is what its
 * {@code @ConditionalOnMissingBean(DefaultBatchConfiguration.class)} means. So the way to change
 * one decision is to extend the class Boot itself extends and override the one method, rather
 * than to add a second {@code JobRepository} bean and fight over the name.
 *
 * <p>Everything else is inherited: the {@code JobOperator}, the {@code JobRegistry}, the
 * observation registry, and the synchronous {@code TaskExecutor} that makes
 * {@code jobOperator.start(...)} return only when the job has finished.
 *
 * <p>No table prefix, serializer or database type is set. The prefix defaults to {@code BATCH_},
 * which is what {@code V7__batch_job_repository.sql} created, and the database type is read from
 * the JDBC metadata — so the same configuration works against PostgreSQL in production and H2 in
 * the fast test suite, choosing the right id incrementer for each.
 */
@Configuration
public class BatchConfig extends DefaultBatchConfiguration {

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;

    public BatchConfig(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
    }

    @Override
    public JobRepository jobRepository() {
        JdbcJobRepositoryFactoryBean factory = new JdbcJobRepositoryFactoryBean();
        factory.setDataSource(dataSource);
        // The repository writes its own bookkeeping in transactions of its own, and it has to be
        // the SAME transaction manager the steps use - otherwise a chunk's commit and the record
        // that the chunk committed could disagree, which is the one inconsistency this whole
        // mechanism exists to prevent.
        factory.setTransactionManager(transactionManager);
        try {
            factory.afterPropertiesSet();
            return factory.getObject();
        } catch (Exception e) {
            // A JobRepository that cannot be built is not something to degrade gracefully from:
            // the alternative is the in-memory one, which looks like it works.
            throw new BatchConfigurationException("Cannot create the JDBC JobRepository", e);
        }
    }
}
