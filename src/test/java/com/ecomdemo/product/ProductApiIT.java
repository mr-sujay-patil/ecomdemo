package com.ecomdemo.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.common.ApiError;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The catalogue over real HTTP and real PostgreSQL.
 *
 * <p>What this adds over {@code ProductControllerTest} (which mocks the service) and
 * {@code ProductServiceTest} (which mocks the repository): nothing is mocked. The request is
 * serialised, routed, validated, mapped to an entity, written by Hibernate as PostgreSQL SQL,
 * committed, and read back through a second request — so it is also the first test that can fail
 * because of the database rather than because of Java.
 *
 * <p>Since Phase 8 the writes need an ADMIN and the reads need nobody, and the credentials here
 * are real: {@link #admin} holds a token issued by the running application after it verified the
 * seeded administrator's password against the BCrypt hash migration V5 put in the container's
 * database. The slice tests assert the same rules with a fabricated principal; this is the only
 * place the rules, a real login and a real signature are exercised together.
 */
class ProductApiIT extends IntegrationTest {

    /** Products created by a test, deleted again afterwards so the next test class starts clean. */
    private final List<Long> createdIds = new ArrayList<>();

    private TestRestTemplate admin;
    private TestRestTemplate customer;

    @BeforeEach
    void signIn() {
        admin = asAdmin();
        customer = asCustomer("it-product-shopper");
    }

    @AfterEach
    void deleteCreatedProducts() {
        createdIds.forEach(id -> admin.delete("/api/products/" + id));
        createdIds.clear();
    }

    private ProductResponse create(String name, String price, int stock, String category) {
        ResponseEntity<ProductResponse> response = admin.postForEntity(
                "/api/products",
                new ProductRequest(name, name + " description", new BigDecimal(price), stock, category),
                ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProductResponse created = response.getBody();
        assertThat(created).isNotNull();
        createdIds.add(created.id());
        return created;
    }

    @Test
    @DisplayName("the seeded catalogue is served from the migrated database")
    void listReturnsTheSeededCatalogue() {
        ResponseEntity<ProductResponse[]> response =
                rest.getForEntity("/api/products", ProductResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // V2 seeds ten products into an empty database; this container has run V1-V4 and nothing
        // else, so the whole Flyway chain is being asserted here as much as the endpoint is.
        assertThat(response.getBody()).hasSize(10);
        assertThat(response.getBody()).extracting(ProductResponse::name).contains("Mechanical Keyboard");
    }

    @Test
    @DisplayName("a created product survives the round trip to PostgreSQL")
    void createThenGetReturnsTheSameProduct() {
        ProductResponse created = create("IT Desk Lamp", "39.95", 7, "Office");

        ResponseEntity<ProductResponse> fetched =
                rest.getForEntity("/api/products/" + created.id(), ProductResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().name()).isEqualTo("IT Desk Lamp");
        assertThat(fetched.getBody().stockQuantity()).isEqualTo(7);
        assertThat(fetched.getBody().category()).isEqualTo("Office");
        // NUMERIC(10,2) in V1, so the scale comes back as the column stores it, not as it was
        // sent. H2 is more forgiving about this than PostgreSQL is.
        assertThat(fetched.getBody().price()).isEqualByComparingTo("39.95");
    }

    @Test
    @DisplayName("an update is written and a delete really removes the row")
    void updateThenDelete() {
        ProductResponse created = create("IT Standing Desk", "499.00", 3, "Office");

        admin.put(
                "/api/products/" + created.id(),
                new ProductRequest("IT Standing Desk v2", "Adjustable", new BigDecimal("549.00"), 4, "Office"));

        ResponseEntity<ProductResponse> updated =
                rest.getForEntity("/api/products/" + created.id(), ProductResponse.class);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().name()).isEqualTo("IT Standing Desk v2");
        assertThat(updated.getBody().price()).isEqualByComparingTo("549.00");
        assertThat(updated.getBody().stockQuantity()).isEqualTo(4);

        admin.delete("/api/products/" + created.id());
        createdIds.remove(created.id());

        assertThat(rest.getForEntity("/api/products/" + created.id(), ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an unknown id is a 404 carrying the shared error body")
    void unknownIdReturns404() {
        ResponseEntity<ApiError> response =
                rest.getForEntity("/api/products/999999", ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().message()).contains("999999");
    }

    @Test
    @DisplayName("validation rejects a bad product before it reaches the database")
    void invalidProductReturns400() {
        ResponseEntity<ApiError> response = admin.postForEntity(
                "/api/products",
                new ProductRequest("", "no name", new BigDecimal("-1.00"), -5, null),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().message()).contains("name");
    }

    @Test
    @DisplayName("browsing the catalogue needs no account at all")
    void readsArePublic() {
        // `rest` is the unauthenticated template: no Authorization header is sent
        assertThat(rest.getForEntity("/api/products", ProductResponse[].class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ProductResponse created = create("IT Public Item", "9.99", 1, "IT");
        assertThat(rest.getForEntity("/api/products/" + created.id(), ProductResponse.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("changing the catalogue anonymously is 401, and as a customer 403")
    void writesRequireAnAdmin() {
        ProductRequest body =
                new ProductRequest("IT Forbidden Item", "1.00", new BigDecimal("1.00"), 1, "IT");

        // 401: the server does not know who this is. Sending credentials could change the answer.
        ResponseEntity<ApiError> anonymous = rest.postForEntity("/api/products", body, ApiError.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody()).isNotNull();
        assertThat(anonymous.getBody().status()).isEqualTo(401);

        // 403: the server knows exactly who this is, and the answer is still no. Repeating the
        // request with the same credentials will never help.
        ResponseEntity<ApiError> asCustomer = customer.postForEntity("/api/products", body, ApiError.class);
        assertThat(asCustomer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asCustomer.getBody()).isNotNull();
        assertThat(asCustomer.getBody().status()).isEqualTo(403);

        // and nothing was created by either attempt
        ResponseEntity<ProductResponse[]> all = rest.getForEntity("/api/products", ProductResponse[].class);
        assertThat(all.getBody()).isNotNull();
        assertThat(all.getBody()).extracting(ProductResponse::name).doesNotContain("IT Forbidden Item");
    }

}
