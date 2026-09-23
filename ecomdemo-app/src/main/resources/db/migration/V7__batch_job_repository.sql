-- Spring Batch's JobRepository schema.
--
-- WHY THIS IS A MIGRATION AND NOT A FRAMEWORK FEATURE
--
-- Spring Batch is not a scheduler and it is not a for-loop: what makes it worth the ceremony is
-- that every run is WRITTEN DOWN. A job knows it has run before, a failed run knows how far it
-- got, and a completed instance refuses to run twice. All of that lives in these six tables, so
-- they are as much production schema as `product` or `orders` are - and therefore they belong in
-- Flyway, reviewed and versioned, like everything else.
--
-- Spring Boot 4 agrees by omission: unlike Boot 3 it ships no `spring.batch.jdbc.initialize-schema`
-- property at all, so nothing creates these tables automatically. If this file did not exist the
-- application would simply fail on the first job with "relation batch_job_instance does not exist".
--
-- The statements below are Spring Batch 6.0.5's own `schema-postgresql.sql`, copied verbatim so
-- that a future upgrade can be diffed against the jar's copy. It is also valid H2 (the test
-- profile runs it, and the shipped `schema-h2.sql` differs only in using IDENTITY columns and
-- LONGVARCHAR - neither of which matters here, because the ids come from the sequences below on
-- both engines).
--
-- WHAT EACH TABLE IS FOR
--
--   BATCH_JOB_INSTANCE           one row per (job name + identifying parameters). This is the
--                                row that makes "this file has already been imported" a fact the
--                                database knows.
--   BATCH_JOB_EXECUTION          one row per ATTEMPT at an instance. A restart adds a second row
--                                against the same instance - which is why a JobInstance can have
--                                many JobExecutions, and why the distinction matters.
--   BATCH_JOB_EXECUTION_PARAMS   the parameters of each attempt, with IDENTIFYING saying whether
--                                a parameter takes part in the instance's identity.
--   BATCH_STEP_EXECUTION         per step, per attempt: the read, write, skip, commit and
--                                rollback counters the API reports back.
--   BATCH_*_EXECUTION_CONTEXT    the serialized ExecutionContext - the reader's saved position.
--                                This is what a restart reads to resume mid-file instead of
--                                re-importing from row 1.

CREATE TABLE BATCH_JOB_INSTANCE (
	JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY,
	VERSION BIGINT,
	JOB_NAME VARCHAR(100) NOT NULL,
	JOB_KEY VARCHAR(32) NOT NULL,
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ;

CREATE TABLE BATCH_JOB_EXECUTION (
	JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY,
	VERSION BIGINT,
	JOB_INSTANCE_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL,
	END_TIME TIMESTAMP DEFAULT NULL,
	STATUS VARCHAR(10),
	EXIT_CODE VARCHAR(2500),
	EXIT_MESSAGE VARCHAR(2500),
	LAST_UPDATED TIMESTAMP,
	constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
	references BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
	JOB_EXECUTION_ID BIGINT NOT NULL,
	PARAMETER_NAME VARCHAR(100) NOT NULL,
	PARAMETER_TYPE VARCHAR(100) NOT NULL,
	PARAMETER_VALUE VARCHAR(2500),
	IDENTIFYING CHAR(1) NOT NULL,
	constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION (
	STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY,
	VERSION BIGINT NOT NULL,
	STEP_NAME VARCHAR(100) NOT NULL,
	JOB_EXECUTION_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL,
	END_TIME TIMESTAMP DEFAULT NULL,
	STATUS VARCHAR(10),
	COMMIT_COUNT BIGINT,
	READ_COUNT BIGINT,
	FILTER_COUNT BIGINT,
	WRITE_COUNT BIGINT,
	READ_SKIP_COUNT BIGINT,
	WRITE_SKIP_COUNT BIGINT,
	PROCESS_SKIP_COUNT BIGINT,
	ROLLBACK_COUNT BIGINT,
	EXIT_CODE VARCHAR(2500),
	EXIT_MESSAGE VARCHAR(2500),
	LAST_UPDATED TIMESTAMP,
	constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT,
	constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
	references BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT,
	constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

-- Sequences, not IDENTITY columns: Spring Batch asks a DataFieldMaxValueIncrementer for the next
-- id BEFORE the insert, because it needs the id in hand to write the child rows of the same
-- execution. Both PostgreSQL and H2 have a sequence incrementer, so one set of statements serves
-- both engines.
CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_INSTANCE_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
