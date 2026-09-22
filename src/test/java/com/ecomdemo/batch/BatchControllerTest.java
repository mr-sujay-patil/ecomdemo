package com.ecomdemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.ecomdemo.batch.dto.JobExecutionResponse;
import com.ecomdemo.batch.dto.ProductImportResponse;
import com.ecomdemo.batch.dto.StepExecutionResponse;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.support.WithSecurityRules;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.multipart.MultipartFile;

/**
 * Web-slice tests for {@link BatchController}: the HTTP contract of the batch endpoints, with
 * {@link BatchService} mocked out. No job runs here — what is being checked is who may call
 * these endpoints, how a multipart upload reaches the service, and which status each failure
 * comes back as.
 *
 * <p>The authorization cases are the important ones. These endpoints can rewrite the whole
 * catalogue from a file, so "a CUSTOMER gets 403" is not a formality; it is the difference
 * between an admin tool and an open one.
 */
@WebMvcTest(BatchController.class)
@WithSecurityRules
@WithMockUser(roles = "ADMIN")
class BatchControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private BatchService batchService;

    private static final String IMPORT_PATH = "/api/admin/batch/product-import";

    private static MockMultipartFile csv() {
        return new MockMultipartFile("file", "products.csv", "text/csv",
                "name,description,price,stock_quantity,category\nWidget,a widget,9.99,3,TEST\n"
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static JobExecutionResponse execution(String status) {
        return new JobExecutionResponse(11, 3, BatchJobs.PRODUCT_IMPORT, status, status,
                LocalDateTime.of(2026, 9, 22, 10, 0), LocalDateTime.of(2026, 9, 22, 10, 1),
                100, 98, 2, null,
                List.of(new StepExecutionResponse("importProducts", status, 100, 98, 2, 2, 1)));
    }

    @Test
    @DisplayName("an ADMIN upload runs the import and gets the counters back")
    void importReturnsTheExecution() {
        when(batchService.importProducts(any(MultipartFile.class))).thenReturn(
                new ProductImportResponse(execution("COMPLETED"), "/batch/uploads/x-products.csv",
                        "/batch/uploads/x-products.csv.errors.csv"));

        assertThat(mvc.perform(multipart(IMPORT_PATH).file(csv())))
                .hasStatus(OK)
                .bodyJson()
                .extractingPath("$.execution.status").isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("the uploaded part named 'file' is what reaches the service")
    void passesTheUploadedFileThrough() {
        when(batchService.importProducts(any(MultipartFile.class))).thenReturn(
                new ProductImportResponse(execution("COMPLETED"), "/staged.csv", null));

        mvc.perform(multipart(IMPORT_PATH).file(csv()));

        var uploaded = org.mockito.ArgumentCaptor.forClass(MultipartFile.class);
        verify(batchService).importProducts(uploaded.capture());
        assertThat(uploaded.getValue().getOriginalFilename()).isEqualTo("products.csv");
    }

    @Test
    @DisplayName("a job that FAILED is still a 200: the run happened, and the body says how it went")
    void aFailedJobIsReportedInTheBody() {
        when(batchService.importProducts(any(MultipartFile.class))).thenReturn(
                new ProductImportResponse(execution("FAILED"), "/staged.csv", "/staged.csv.errors.csv"));

        assertThat(mvc.perform(multipart(IMPORT_PATH).file(csv())))
                .hasStatus(OK)
                .bodyJson()
                .extractingPath("$.errorFile").isEqualTo("/staged.csv.errors.csv");
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("a CUSTOMER is refused, and the import never runs")
    void refusesACustomer() {
        assertThat(mvc.perform(multipart(IMPORT_PATH).file(csv()))).hasStatus(FORBIDDEN);
        verify(batchService, never()).importProducts(any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("an anonymous caller is asked to authenticate")
    void refusesAnonymous() {
        assertThat(mvc.perform(multipart(IMPORT_PATH).file(csv()))).hasStatus(UNAUTHORIZED);
        verify(batchService, never()).importProducts(any());
    }

    @Test
    @DisplayName("an execution can be looked up by id")
    void looksUpAnExecution() {
        when(batchService.findExecution(11)).thenReturn(execution("COMPLETED"));

        assertThat(mvc.get().uri("/api/admin/batch/executions/{id}", 11))
                .hasStatus(OK)
                .bodyJson()
                .extractingPath("$.instanceId").isEqualTo(3);
    }

    @Test
    @DisplayName("an unknown execution id is a 404 in the standard error shape")
    void unknownExecutionIsNotFound() {
        when(batchService.findExecution(anyLong()))
                .thenThrow(new NotFoundException("Job execution 99 not found"));

        assertThat(mvc.get().uri("/api/admin/batch/executions/{id}", 99))
                .hasStatus(NOT_FOUND)
                .bodyJson()
                .extractingPath("$.message").isEqualTo("Job execution 99 not found");
    }

    @Test
    @DisplayName("restarting something that is not a failed import is a 409")
    void restartingANonFailedExecutionConflicts() {
        when(batchService.restartProductImport(anyLong()))
                .thenThrow(new ConflictException("Execution 11 is COMPLETED"));

        assertThat(mvc.post().uri("/api/admin/batch/executions/{id}/restart", 11))
                .hasStatus(CONFLICT);
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("and a CUSTOMER cannot restart one either")
    void refusesACustomerRestart() {
        assertThat(mvc.post().uri("/api/admin/batch/executions/{id}/restart", 11))
                .hasStatus(FORBIDDEN);
        verify(batchService, never()).restartProductImport(anyLong());
    }
}
