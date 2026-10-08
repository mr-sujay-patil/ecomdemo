package com.ecomdemo.clients.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.jwt.ServiceTokenProvider;
import com.ecomdemo.jwt.ServiceTokens;
import com.ecomdemo.support.TestJwt;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * KI-007. The catalogue lists one page at a time; {@code findAll()} still means the whole catalogue,
 * so it has to follow the pages rather than return the first.
 */
@DisplayName("catalog client paging")
class CatalogClientPagingTest {

    /** 250 products: two full pages of 100 and a last page of 50. */
    private static final int TOTAL = 250;

    private static HttpServer catalog;
    private static final List<String> requests = new ArrayList<>();

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CatalogClientConfig.class)
            .withBean(CatalogClient.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(ServiceTokenProvider.class,
                    () -> () -> TestJwt.service("ecomdemo-app", ServiceTokens.CATALOG_READ))
            .withPropertyValues("ecomdemo.catalog.base-url=http://localhost:" + catalog.getAddress().getPort());

    @BeforeAll
    static void startCatalog() throws IOException {
        catalog = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        catalog.createContext("/api/products", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            synchronized (requests) {
                requests.add(query);
            }
            int page = Integer.parseInt(query.replaceAll(".*page=(\\d+).*", "$1"));
            int size = Integer.parseInt(query.replaceAll(".*size=(\\d+).*", "$1"));
            int from = Math.min(page * size, TOTAL);
            int to = Math.min(from + size, TOTAL);
            String body = IntStream.range(from, to)
                    .mapToObj(i -> "{\"id\":" + i + ",\"name\":\"P" + i + "\",\"price\":1.00}")
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Total-Count", String.valueOf(TOTAL));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        catalog.start();
    }

    @AfterAll
    static void stopCatalog() {
        catalog.stop(0);
    }

    @Test
    @DisplayName("findAll follows the pages until a short one, and returns every product once")
    void findAllFollowsThePages() {
        context.run(ctx -> {
            requests.clear();

            List<ProductSnapshot> all = ctx.getBean(CatalogGateway.class).findAll();

            assertThat(all).hasSize(TOTAL);
            assertThat(all).extracting(ProductSnapshot::id).doesNotHaveDuplicates();
            assertThat(requests).containsExactly("page=0&size=100", "page=1&size=100", "page=2&size=100");
        });
    }
}
