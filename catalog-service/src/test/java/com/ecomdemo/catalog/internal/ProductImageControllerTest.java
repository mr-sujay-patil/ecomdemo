package com.ecomdemo.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.NOT_MODIFIED;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.ecomdemo.catalog.ProductImage;
import com.ecomdemo.catalog.ProductImageService;
import com.ecomdemo.jwt.ServiceTokens;
import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.support.WithSecurityRules;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The HTTP contract of {@code GET /api/products/{id}/image}: status, content type, caching and
 * hardening headers, conditional requests. Web slice only; the service is a mock.
 *
 * <p>The caller is the gateway presenting a catalog:read service token, which is what it does for
 * every anonymous browser read. This service itself stays an internal API: no token, no image.
 */
@WebMvcTest(ProductImageController.class)
@WithSecurityRules
@WithMockUser(username = "gateway", authorities = "SCOPE_" + ServiceTokens.CATALOG_READ)
class ProductImageControllerTest {

    private static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
    private static final ProductImage IMAGE =
            new ProductImage(SVG, MediaType.valueOf("image/svg+xml"), "\"abc123\"");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ProductImageService images;

    @Test
    @DisplayName("200 with the bytes, the media type, a strong ETag and public one-day caching")
    void servesTheImage() {
        when(images.find(1L)).thenReturn(IMAGE);

        var result = mvc.get().uri("/api/products/1/image").exchange();

        assertThat(result).hasStatus(OK);
        assertThat(result).hasContentType("image/svg+xml");
        assertThat(result).headers()
                .hasValue(HttpHeaders.ETAG, "\"abc123\"")
                .hasValue("X-Content-Type-Options", "nosniff")
                .hasValue("Cross-Origin-Resource-Policy", "cross-origin");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("public").contains("max-age=86400");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(SVG);
    }

    @Test
    @DisplayName("an SVG is served with a Content-Security-Policy that forbids everything, scripts included")
    void svgCarriesACsp() {
        when(images.find(1L)).thenReturn(IMAGE);

        var csp = mvc.get().uri("/api/products/1/image").exchange().getResponse().getHeader("Content-Security-Policy");

        assertThat(csp).contains("default-src 'none'").contains("sandbox");
    }

    @Test
    @DisplayName("a raster image carries no CSP, and keeps the other headers")
    void rasterHasNoCsp() {
        when(images.find(2L)).thenReturn(new ProductImage(new byte[] {1, 2, 3}, MediaType.IMAGE_PNG, "\"png1\""));

        var result = mvc.get().uri("/api/products/2/image").exchange();

        assertThat(result).hasStatus(OK).hasContentType(MediaType.IMAGE_PNG);
        assertThat(result.getResponse().getHeader("Content-Security-Policy")).isNull();
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("If-None-Match with the current ETag is a 304 with no body")
    void conditionalRequest() {
        when(images.find(1L)).thenReturn(IMAGE);

        var result = mvc.get().uri("/api/products/1/image").header(HttpHeaders.IF_NONE_MATCH, "\"abc123\"").exchange();

        assertThat(result).hasStatus(NOT_MODIFIED);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"abc123\"");
    }

    @Test
    @DisplayName("a stale ETag gets the full image again")
    void staleEtag() {
        when(images.find(1L)).thenReturn(IMAGE);

        assertThat(mvc.get().uri("/api/products/1/image").header(HttpHeaders.IF_NONE_MATCH, "\"old\"")).hasStatus(OK);
    }

    @Test
    @DisplayName("an unknown product, or one without an image, is a 404 with the standard error body")
    void notFound() {
        when(images.find(99L)).thenThrow(NotFoundException.product(99L));

        var result = mvc.get().uri("/api/products/99/image").exchange();

        assertThat(result).hasStatus(NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(404);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("no token, no image: this service is internal, and anonymous browsers reach it through the gateway")
    void needsAToken() {
        assertThat(mvc.get().uri("/api/products/1/image")).hasStatus(UNAUTHORIZED);
    }
}
