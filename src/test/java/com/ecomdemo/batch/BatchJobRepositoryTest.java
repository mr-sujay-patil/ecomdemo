package com.ecomdemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.batch.dto.JobExecutionResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves that the runs are actually written down.
 *
 * <p>This is the test the phase most needed and nearly did not have. Spring Batch 6 defaults to
 * {@link ResourcelessJobRepository}, an in-memory repository under which <em>every other test
 * here still passes</em>: jobs run, counters come back, a completed instance is refused a second
 * time — all from state that dies with the JVM. The {@code BATCH_*} tables simply stay empty, and
 * with them go restart-after-a-crash and any record of what ran.
 *
 * <p>So the assertion is deliberately about rows in a table rather than about behaviour: the
 * behaviour looked right in both worlds. See {@code BatchConfig} for the one override that makes
 * it true.
 *
 * <p>It runs in the fast suite, against H2. Persistence is persistence; nothing about this claim
 * needs PostgreSQL.
 */
@SpringBootTest
class BatchJobRepositoryTest {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private BatchService batchService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("the JobRepository writes to the database, and is not the in-memory default")
    void theJobRepositoryIsBackedByTheDatabase() {
        assertThat(jobRepository)
                .as("ResourcelessJobRepository records nothing; every run would be forgotten")
                .isNotInstanceOf(ResourcelessJobRepository.class);
    }

    @Test
    @DisplayName("a run leaves rows in BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION and BATCH_STEP_EXECUTION")
    void aRunIsRecordedInTheBatchTables() {
        // A date nothing else in the suite uses, so this instance belongs to this test.
        LocalDate day = LocalDate.of(1999, 12, 31);

        JobExecutionResponse execution = batchService.runSalesReport(day);

        assertThat(execution.status()).isEqualTo("COMPLETED");

        List<Map<String, Object>> executions = jdbc.queryForList(
                "SELECT job_execution_id, job_instance_id, status FROM batch_job_execution "
                        + "WHERE job_execution_id = ?",
                execution.id());
        assertThat(executions).as("the execution the API reported is the one in the table")
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("status")).isEqualTo("COMPLETED");
                    assertThat(((Number) row.get("job_instance_id")).longValue())
                            .isEqualTo(execution.instanceId());
                });

        assertThat(jdbc.queryForObject(
                        "SELECT job_name FROM batch_job_instance WHERE job_instance_id = ?",
                        String.class, execution.instanceId()))
                .isEqualTo(BatchJobs.SALES_REPORT);

        // Both steps, with their counters - the numbers the API reads back come from here.
        assertThat(jdbc.queryForList(
                        "SELECT step_name FROM batch_step_execution WHERE job_execution_id = ? "
                                + "ORDER BY step_execution_id",
                        String.class, execution.id()))
                .containsExactly(SalesReportJobConfig.SUMMARY_STEP,
                        SalesReportJobConfig.TOP_PRODUCTS_STEP);

        // And the identifying parameter is stored with it, which is what makes the instance
        // findable again tomorrow.
        assertThat(jdbc.queryForObject(
                        "SELECT parameter_value FROM batch_job_execution_params "
                                + "WHERE job_execution_id = ? AND parameter_name = ?",
                        String.class, execution.id(), BatchJobs.PARAM_REPORT_DATE))
                .isEqualTo(day.toString());
    }
}
