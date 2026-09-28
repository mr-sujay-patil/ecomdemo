package com.ecomdemo.assistant.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * catalog-service AND the application, played by the JDK's own HTTP server.
 *
 * <p>Real HTTP rather than a mocked client, because the thing most worth checking here is on the
 * wire: that every request the assistant makes carries the CUSTOMER's token in its Authorization
 * header. A mock of {@code StoreClient} would have been told what to expect, and could not notice
 * a service token, or no token, being sent instead.
 */
public class FakeStore implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<Request> requests = new ArrayList<>();

    public FakeStore() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(), exchange.getRequestHeaders().getFirst("Authorization"), body);
            synchronized (requests) {
                requests.add(request);
            }
            Reply reply = replies.getOrDefault(request.method() + " " + request.path(),
                    new Reply(404, "{\"status\":404,\"message\":\"not scripted\"}"));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** {@code on("GET /api/orders/7/status", 403, "{...}")}. */
    public FakeStore on(String methodAndPath, int status, String body) {
        replies.put(methodAndPath, new Reply(status, body));
        return this;
    }

    public List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public List<Request> requests(String method, String path) {
        return requests().stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
    }

    public void reset() {
        replies.clear();
        synchronized (requests) {
            requests.clear();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public record Request(String method, String path, String query, String authorization, String body) {
    }

    private record Reply(int status, String body) {
    }

    /** The seeded catalogue's headphones, as catalog-service returns a product. */
    public static String headphones() {
        return """
                {"id":4,"name":"Noise-Cancelling Headphones","description":"Over-ear ANC headphones, 30h battery",
                 "price":14999.00,"stockQuantity":12,"category":"AUDIO"}""";
    }

    public static String searchResult(String product) {
        return "{\"query\":\"q\",\"results\":[{\"product\":" + product + ",\"similarity\":0.71}]}";
    }
}
