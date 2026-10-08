package com.ecomdemo.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.shared.ApiError;
import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.jwt.ServiceTokens;
import com.ecomdemo.support.CatalogIntegrationTest;
import com.ecomdemo.support.TestJwt;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
class ProductApiIT extends CatalogIntegrationTest {

    /** Products created by a test, deleted again afterwards so the next test class starts clean. */
    private final List<Long> createdIds = new ArrayList<>();

    private TestRestTemplate admin;

    @BeforeEach
    void signIn() {
        // The application's SERVICE identity, which may write (as may a relayed ADMIN token; see
        // anAdminCanWrite). A CUSTOMER may not - aCustomerCannotWrite.
        admin = asService();
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

    /**
     * KI-007. The body stays a bare array (the web team's client reads it as one); the paging is in
     * the query and in the headers. A caller that sends nothing gets the first page of the default
     * size, never the whole catalogue.
     */
    @Test
    @DisplayName("the listing is paged: size limits the array, X-Total-Count and Link say where it is")
    void listIsPaged() {
        create("Paging A", "1.00", 1, "Paging");
        create("Paging B", "2.00", 1, "Paging");
        create("Paging C", "3.00", 1, "Paging");

        ResponseEntity<ProductResponse[]> first = rest.getForEntity("/api/products?size=2", ProductResponse[].class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).hasSize(2);
        long total = Long.parseLong(first.getHeaders().getFirst("X-Total-Count"));
        assertThat(total).as("every product is counted, not just this page").isGreaterThanOrEqualTo(3 + 2);
        assertThat(first.getHeaders().getFirst(HttpHeaders.LINK))
                .contains("rel=\"next\"").contains("rel=\"last\"").doesNotContain("rel=\"prev\"");

        ResponseEntity<ProductResponse[]> second =
                rest.getForEntity("/api/products?size=2&page=1", ProductResponse[].class);
        assertThat(second.getBody()).hasSize(2);
        assertThat(second.getBody()[0].id()).as("pages follow on, in id order")
                .isGreaterThan(first.getBody()[1].id());
        assertThat(second.getHeaders().getFirst(HttpHeaders.LINK)).contains("rel=\"prev\"");

        ResponseEntity<ProductResponse[]> beyond =
                rest.getForEntity("/api/products?size=2&page=100000", ProductResponse[].class);
        assertThat(beyond.getStatusCode()).as("a page past the end is empty, not an error").isEqualTo(HttpStatus.OK);
        assertThat(beyond.getBody()).isEmpty();
        assertThat(beyond.getHeaders().getFirst("X-Total-Count")).isEqualTo(String.valueOf(total));
    }

    @Test
    @DisplayName("with no parameters the listing is one page of the default size, with its headers")
    void listDefaultsToOnePage() {
        ResponseEntity<ProductResponse[]> response = rest.getForEntity("/api/products", ProductResponse[].class);

        assertThat(response.getBody()).hasSizeLessThanOrEqualTo(50);
        assertThat(response.getHeaders().getFirst("X-Total-Count")).isNotNull();
    }

    @Test
    @DisplayName("a size over the maximum, a zero size and a negative page are 400s with the shared error body")
    void badPagingIsRefused() {
        for (String query : new String[] {"size=101", "size=0", "page=-1", "size=abc"}) {
            ResponseEntity<ApiError> response = rest.getForEntity("/api/products?" + query, ApiError.class);
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).as(query).isNotNull();
        }
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

        // The DELETE's own status is asserted rather than ignored. `TestRestTemplate.delete()`
        // returns void and swallows it, so a delete that was refused used to surface one line
        // later as a JSON parse error — the GET returned the product, and Jackson complained that
        // it could not map a null `status` into ApiError's int. That is a genuinely confusing way
        // to be told "the delete did not happen".
        ResponseEntity<Void> deleted =
                admin.exchange(
                        "/api/products/" + created.id(), HttpMethod.DELETE, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
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
    @DisplayName("every path needs a token, including the reads")
    void nothingIsPublicHere() {
        // THESE TWO TESTS REPLACED "browsing needs no account" AND "writes require an admin", and the
        // swap is the boundary in one method.
        //
        // The catalogue was the shop window: reads open to the world, writes ADMIN-only. Those rules
        // still exist - they are just not THIS service's any more. It is internal now, its only caller
        // is another service presenting a SERVICE token, and it cannot tell an administrator from a
        // shopper. The shop-window rules live at the edge, on the application's own /api/products, and
        // are asserted there in ProductProxyAccessTest.
        //
        // What is left to assert here is the rule this service actually has, which the old pair would
        // have hidden: nothing is open, not even a GET.
        assertThat(anonymous.getForEntity("/api/products", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getForEntity("/api/products/1", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ProductRequest body =
                new ProductRequest("IT Forbidden Item", "1.00", new BigDecimal("1.00"), 1, "IT");
        assertThat(anonymous.postForEntity("/api/products", body, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Phase 31, OWASP API5. The comment above describes how this service used to be: it only asked
     * "is there a token?", which was fine while the gateway was the only way in. The security review
     * sent a CUSTOMER's token straight to this port and got past authorisation for a create and a
     * delete. Since then the gateway's rules are repeated here: reads for any valid token, writes
     * for ADMIN or SERVICE.
     */
    @Test
    @DisplayName("a CUSTOMER may read but not write, even when calling this service directly")
    void aCustomerCannotWrite() {
        TestRestTemplate customer = asUser("shopper", 7, "CUSTOMER");

        assertThat(customer.getForEntity("/api/products", ProductResponse[].class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ProductRequest body =
                new ProductRequest("IT Customer Write", "1.00", new BigDecimal("1.00"), 1, "IT");
        assertThat(customer.postForEntity("/api/products", body, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ProductResponse existing = create("IT Not Yours To Delete", "2.00", 1, "IT");
        assertThat(customer.exchange("/api/products/" + existing.id(), HttpMethod.DELETE, null, ApiError.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.getForEntity("/api/products/" + existing.id(), ProductResponse.class).getStatusCode())
                .as("the product is still there")
                .isEqualTo(HttpStatus.OK);

        assertThat(customer.getForEntity("/api/products/embeddings/1", ApiError.class).getStatusCode())
                .as("a backfill's progress is operations data, ADMIN only at the gateway and here")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Phase 33: service tokens carry scopes, and this chain checks them. The gateway's token
     * ({@code catalog:read}) browses and cannot write; catalog-service's own token
     * ({@code inventory:read} only) cannot even read here. Until Phase 33 both carried the one SERVICE
     * role, which could write the catalogue.
     */
    @Test
    @DisplayName("a service token must carry the scope: catalog:read reads, nothing else writes")
    void serviceTokensNeedTheirScope() {
        TestRestTemplate gateway = withToken(TestJwt.service("gateway-service", ServiceTokens.CATALOG_READ));
        TestRestTemplate outOfScope = withToken(TestJwt.service("catalog-service", ServiceTokens.INVENTORY_READ));

        assertThat(gateway.getForEntity("/api/products", ProductResponse[].class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ProductRequest body = new ProductRequest("IT Scope Write", "1.00", new BigDecimal("1.00"), 1, "IT");
        assertThat(gateway.postForEntity("/api/products", body, ApiError.class).getStatusCode())
                .as("catalog:read is not catalog:write")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outOfScope.getForEntity("/api/products", ApiError.class).getStatusCode())
                .as("a token with no catalog scope")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an ADMIN's relayed token may write, as it may at the gateway")
    void anAdminCanWrite() {
        TestRestTemplate admin = asUser("boss", 1, "ADMIN");
        ProductRequest body =
                new ProductRequest("IT Admin Write", "1.00", new BigDecimal("3.00"), 1, "IT");

        ResponseEntity<ProductResponse> created = admin.postForEntity("/api/products", body, ProductResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        createdIds.add(created.getBody().id());
    }


    // ---- Phase 34: product images -------------------------------------------------------------

    @Test
    @DisplayName("a seeded product carries imageUrl, and the URL serves the image with caching headers and a 304")
    void seededProductImageIsServed() {
        ProductResponse keyboard = rest.getForEntity("/api/products/1", ProductResponse.class).getBody();
        assertThat(keyboard).isNotNull();
        assertThat(keyboard.imageUrl()).isEqualTo("/api/products/1/image");

        ResponseEntity<byte[]> image = rest.getForEntity(keyboard.imageUrl(), byte[].class);

        assertThat(image.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(image.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("image/svg+xml"));
        assertThat(new String(image.getBody())).startsWith("<svg");
        assertThat(image.getHeaders().getCacheControl()).contains("public").contains("max-age=86400");
        assertThat(image.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(image.getHeaders().getFirst("Cross-Origin-Resource-Policy")).isEqualTo("cross-origin");
        String etag = image.getHeaders().getETag();
        assertThat(etag).isNotBlank();

        HttpHeaders conditional = new HttpHeaders();
        conditional.setIfNoneMatch(etag);
        ResponseEntity<byte[]> again = rest.exchange(
                keyboard.imageUrl(), HttpMethod.GET, new HttpEntity<>(conditional), byte[].class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    @DisplayName("the list carries imageUrl too, and some seeded products have none (null stays valid)")
    void listCarriesImageUrl() {
        ProductResponse[] all = rest.getForEntity("/api/products", ProductResponse[].class).getBody();

        assertThat(all).isNotNull();
        assertThat(all).anyMatch(p -> p.imageUrl() != null);
        assertThat(all).anyMatch(p -> p.imageUrl() == null);
        for (ProductResponse p : all) {
            if (p.imageUrl() != null) {
                assertThat(p.imageUrl()).isEqualTo("/api/products/" + p.id() + "/image");
            }
        }
    }

    @Test
    @DisplayName("a product created through the API has no image, and its /image is a 404")
    void createdProductHasNoImage() {
        ProductResponse created = create("Imageless", "10.00", 1, null);

        assertThat(created.imageUrl()).isNull();
        ResponseEntity<ApiError> missing = rest.getForEntity("/api/products/" + created.id() + "/image", ApiError.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an unknown product's /image is a 404; the image needs a token like every other path here")
    void unknownProductAndNoToken() {
        assertThat(rest.getForEntity("/api/products/999999/image", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(anonymous.getForEntity("/api/products/1/image", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
