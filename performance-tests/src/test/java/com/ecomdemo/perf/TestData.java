package com.ecomdemo.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Puts the data a run needs in place, BEFORE the clock starts, and hands out bearer tokens.
 *
 * <p>Idempotent, so it runs at the start of every simulation against whatever state the stack is
 * in: a user that exists (409) is fine, a product that exists is reused, and every perf product's
 * stock is topped back up to {@link #STOCK} so no run is ever cancelled for stock.
 *
 * <p><b>Why tokens are fetched here and not inside each session.</b> A login is a BCrypt check,
 * deliberately slow (tens of milliseconds of CPU). Logging in at the start of every browse session
 * would make "browse" mostly a password-hashing benchmark. Real shoppers log in once and then
 * browse for a while on the same token; this does the same. Login cost is its own question.
 *
 * <p>Plain {@code java.net.http}, not Gatling: this is setup, and nothing here should appear in the
 * report.
 */
final class TestData {

    static final String USER_PREFIX = "perf-user-";
    static final String PRODUCT_PREFIX = "Perf Product ";
    /** Far more than any run can sell, and far below the int limit. */
    static final int STOCK = 1_000_000;

    record Prepared(List<String> customerTokens, List<Long> productIds) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static Prepared prepared;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private TestData() {}

    /** Once per JVM: a simulation class may be constructed more than once by the runner. */
    static synchronized Prepared prepare() {
        if (prepared == null) {
            prepared = new TestData().run();
        }
        return prepared;
    }

    private Prepared run() {
        long started = System.nanoTime();
        String admin = login(PerfConfig.ADMIN_USER, PerfConfig.ADMIN_PASSWORD);
        List<Long> products = ensureProducts(admin);
        List<String> tokens = customerTokens();
        System.out.printf(
                "[perf setup] %d customers logged in, %d products stocked at %d, in %d ms%n",
                tokens.size(), products.size(), STOCK, (System.nanoTime() - started) / 1_000_000);
        return new Prepared(List.copyOf(tokens), List.copyOf(products));
    }

    /**
     * Registers (or reuses) and logs in every perf user. In parallel, but no more than 8 at a time:
     * registration and login are ANONYMOUS, and the gateway rate-limits anonymous traffic per
     * client IP (50/s, burst 100). Every request from this machine shares that one bucket; going
     * wider just buys 429s. {@link #send} retries those anyway.
     */
    private List<String> customerTokens() {
        Semaphore permits = new Semaphore(8);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 1; i <= PerfConfig.USERS; i++) {
                String username = USER_PREFIX + i;
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        register(username);
                        return login(username, PerfConfig.CUSTOMER_PASSWORD);
                    } finally {
                        permits.release();
                    }
                }));
            }
            List<String> tokens = new ArrayList<>();
            for (Future<String> future : futures) {
                tokens.add(future.get());
            }
            return tokens;
        } catch (Exception ex) {
            throw new IllegalStateException("perf setup: could not prepare the customers", ex);
        }
    }

    private void register(String username) {
        String body = json(Map.of(
                "username", username,
                "password", PerfConfig.CUSTOMER_PASSWORD,
                "fullName", "Perf Test " + username));
        HttpResponse<String> response = send(post("/api/customers/register", null, body));
        if (response.statusCode() != 201 && response.statusCode() != 409) {
            throw fail("register " + username, response);
        }
    }

    private String login(String username, String password) {
        String body = json(Map.of("username", username, "password", password));
        HttpResponse<String> response = send(post("/api/auth/login", null, body));
        if (response.statusCode() != 200) {
            throw fail("login " + username, response);
        }
        return read(response).get("accessToken").asText();
    }

    /**
     * PRODUCTS perf products, created on the first run and reused afterwards (matched by name),
     * each put back to {@link #STOCK}. Prices are small: the mock payment provider declines
     * anything above 10 000, and a declined payment would be a CANCELLED order - a failure of the
     * test data, not of the system.
     */
    private List<Long> ensureProducts(String admin) {
        Map<String, Long> existing = new HashMap<>();
        // KI-007: the listing is paged (at most 100 a page), so follow the pages until a short one;
        // matching by name on the first page alone would create a duplicate set on every run.
        for (int page = 0;; page++) {
            HttpResponse<String> list = send(get("/api/products?page=" + page + "&size=100", admin));
            if (list.statusCode() != 200) {
                throw fail("list products", list);
            }
            JsonNode products = read(list);
            for (JsonNode product : products) {
                existing.put(product.get("name").asText(), product.get("id").asLong());
            }
            if (products.size() < 100) {
                break;
            }
        }

        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= PerfConfig.PRODUCTS; i++) {
            String name = PRODUCT_PREFIX + i;
            Long id = existing.get(name);
            if (id == null) {
                Map<String, Object> product = new HashMap<>();
                product.put("name", name);
                product.put("description", "Created by the Gatling setup for load tests.");
                product.put("price", "%d.99".formatted(i));
                product.put("stockQuantity", STOCK);
                product.put("category", "PERF");
                HttpResponse<String> created = send(post("/api/products", admin, json(product)));
                if (created.statusCode() != 201) {
                    throw fail("create " + name, created);
                }
                id = read(created).get("id").asLong();
            }
            HttpResponse<String> stocked = send(
                    put("/api/inventory/" + id, admin, json(Map.of("quantity", STOCK))));
            if (stocked.statusCode() != 200) {
                throw fail("stock " + name, stocked);
            }
            ids.add(id);
        }
        return ids;
    }

    /** Sends, and retries a 429 from the rate limiter up to 20 times, one second apart. */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            for (int attempt = 1; ; attempt++) {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 429 || attempt == 20) {
                    return response;
                }
                Thread.sleep(1000);
            }
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "perf setup: cannot reach " + PerfConfig.BASE_URL + " - is the stack up?", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static HttpRequest get(String path, String token) {
        return request(path, token).GET().build();
    }

    private static HttpRequest post(String path, String token, String body) {
        return request(path, token).POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static HttpRequest put(String path, String token, String body) {
        return request(path, token).PUT(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(PerfConfig.BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JsonNode read(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static IllegalStateException fail(String what, HttpResponse<String> response) {
        // The status and the first part of the body; never a request, which could hold a password.
        String body = response.body();
        return new IllegalStateException("perf setup: %s -> %d %s".formatted(
                what, response.statusCode(), body.substring(0, Math.min(200, body.length()))));
    }
}
