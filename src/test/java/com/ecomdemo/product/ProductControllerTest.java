package com.ecomdemo.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.NO_CONTENT;
import static org.springframework.http.HttpStatus.OK;

import com.ecomdemo.common.GlobalExceptionHandler;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Web-slice tests for {@link ProductController}.
 *
 * <p>{@code @WebMvcTest} starts only the web layer — the controller, JSON conversion, Bean
 * Validation and {@link GlobalExceptionHandler} — with no database and no service beans. The
 * service is supplied as a {@code @MockitoBean}, so these tests say nothing about business
 * rules (the unit tests do that) and everything about the HTTP contract: status codes, headers
 * and JSON shape.
 *
 * <p>{@link MockMvcTester} is the AssertJ-flavoured MockMvc: the same fake request goes through
 * the same {@code DispatcherServlet} without a real socket, but the result is asserted with
 * {@code assertThat(...)} rather than Hamcrest matchers, which keeps every test in this project
 * written in one assertion style.
 */
@WebMvcTest(ProductController.class)
@Import(GlobalExceptionHandler.class)
class ProductControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ProductService productService;

    private static final ProductResponse KEYBOARD =
            new ProductResponse(1L, "Keyboard", "Tactile switches", new BigDecimal("8999.00"), 25, "PERIPHERALS");

    private static final String VALID_BODY =
            """
            {"name":"Keyboard","description":"Tactile switches","price":8999.00,"stockQuantity":25}
            """;

    @Nested
    @DisplayName("GET /api/products")
    class List_ {

        @Test
        void list_whenProductsExist_returns200AndAJsonArray() {
            // Given
            when(productService.findAll()).thenReturn(List.of(KEYBOARD));

            // When / Then: the JSON itself is asserted rather than a deserialised record.
            // This is the contract clients actually see, and JSONAssert compares numbers by
            // value, so a price serialised as 8999.0 or 8999.00 both match.
            assertThat(mvc.get().uri("/api/products"))
                    .hasStatus(OK)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            [{"id":1,"name":"Keyboard","description":"Tactile switches",
                              "price":8999.00,"stockQuantity":25}]
                            """);
        }

        @Test
        void list_whenCatalogueIsEmpty_returns200AndAnEmptyArray() {
            // Given
            when(productService.findAll()).thenReturn(List.of());

            // When / Then
            assertThat(mvc.get().uri("/api/products"))
                    .hasStatus(OK)
                    .bodyJson()
                    .extractingPath("$")
                    .asArray()
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("GET /api/products/{id}")
    class Get {

        @Test
        void get_whenTheProductExists_returns200AndTheProduct() {
            // Given
            when(productService.findById(1L)).thenReturn(KEYBOARD);

            // When / Then
            assertThat(mvc.get().uri("/api/products/1"))
                    .hasStatus(OK)
                    .bodyJson()
                    .extractingPath("$.name")
                    .isEqualTo("Keyboard");
        }

        @Test
        void get_whenTheProductIsMissing_returns404InTheApiErrorShape() {
            // Given
            when(productService.findById(404L)).thenThrow(NotFoundException.product(404L));

            // When / Then
            assertThat(mvc.get().uri("/api/products/404"))
                    .hasStatus(NOT_FOUND)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"status":404,"message":"Product 404 not found"}
                            """);
        }

        @Test
        void get_whenTheIdIsNotANumber_returns400() {
            // When / Then: the path variable cannot be bound to a Long
            assertThat(mvc.get().uri("/api/products/not-a-number")).hasStatus(BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("POST /api/products")
    class Create {

        @Test
        void create_whenTheBodyIsValid_returns201WithALocationHeader() {
            // Given
            when(productService.create(any(ProductRequest.class))).thenReturn(KEYBOARD);

            // When / Then
            assertThat(mvc.post()
                            .uri("/api/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .hasStatus(CREATED)
                    .hasHeader("Location", "/api/products/1")
                    .bodyJson()
                    .extractingPath("$.id")
                    .isEqualTo(1);
        }

        @Test
        void create_whenTheNameIsBlank_returns400AndNeverReachesTheService() {
            // Given
            String blankName =
                    """
                    {"name":"  ","price":8999.00,"stockQuantity":25}
                    """;

            // When / Then: @Valid rejects the body before the controller body runs
            assertThat(mvc.post()
                            .uri("/api/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(blankName))
                    .hasStatus(BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.message")
                    .isEqualTo("name must not be blank");
            verify(productService, never()).create(any());
        }

        @Test
        void create_whenSeveralFieldsAreInvalid_returns400ListingEveryFieldInOrder() {
            // Given: price below the minimum and a negative stock
            String invalid =
                    """
                    {"name":"Keyboard","price":0.00,"stockQuantity":-1}
                    """;

            // When / Then: the handler sorts the field errors, so the message is deterministic
            assertThat(mvc.post()
                            .uri("/api/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalid))
                    .hasStatus(BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.message")
                    .isEqualTo("price must be at least 0.01; stockQuantity must not be negative");
        }

        @Test
        void create_whenPriceIsMissing_returns400() {
            // Given
            String noPrice =
                    """
                    {"name":"Keyboard","stockQuantity":25}
                    """;

            // When / Then
            assertThat(mvc.post()
                            .uri("/api/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(noPrice))
                    .hasStatus(BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.message")
                    .isEqualTo("price is required");
        }

        @Test
        void create_whenTheBodyIsMalformedJson_returns400WithAGenericMessage() {
            // When / Then: nothing can be bound, so no field names are known
            assertThat(mvc.post()
                            .uri("/api/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": "))
                    .hasStatus(BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.message")
                    .isEqualTo("Malformed request body");
        }
    }

    @Nested
    @DisplayName("PUT /api/products/{id}")
    class Update {

        @Test
        void update_whenTheBodyIsValid_returns200AndTheUpdatedProduct() {
            // Given
            when(productService.update(eq(1L), any(ProductRequest.class))).thenReturn(KEYBOARD);

            // When / Then
            assertThat(mvc.put()
                            .uri("/api/products/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .hasStatus(OK)
                    .bodyJson()
                    .extractingPath("$.name")
                    .isEqualTo("Keyboard");
        }

        @Test
        void update_whenTheProductIsMissing_returns404() {
            // Given
            when(productService.update(eq(404L), any(ProductRequest.class)))
                    .thenThrow(NotFoundException.product(404L));

            // When / Then
            assertThat(mvc.put()
                            .uri("/api/products/404")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .hasStatus(NOT_FOUND);
        }

        @Test
        void update_whenTheBodyIsInvalid_returns400AndNeverReachesTheService() {
            // Given
            String invalid =
                    """
                    {"name":"Keyboard","price":8999.00}
                    """;

            // When / Then
            assertThat(mvc.put()
                            .uri("/api/products/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalid))
                    .hasStatus(BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.message")
                    .isEqualTo("stockQuantity is required");
            verify(productService, never()).update(any(), any());
        }
    }

    @Nested
    @DisplayName("DELETE /api/products/{id}")
    class Delete {

        @Test
        void delete_whenTheProductExists_returns204WithNoBody() {
            // When / Then
            assertThat(mvc.delete().uri("/api/products/1")).hasStatus(NO_CONTENT).hasBodyTextEqualTo("");
            verify(productService).delete(1L);
        }

        @Test
        void delete_whenTheProductIsMissing_returns404() {
            // Given
            org.mockito.Mockito.doThrow(NotFoundException.product(404L))
                    .when(productService)
                    .delete(404L);

            // When / Then
            assertThat(mvc.delete().uri("/api/products/404")).hasStatus(NOT_FOUND);
        }
    }
}
