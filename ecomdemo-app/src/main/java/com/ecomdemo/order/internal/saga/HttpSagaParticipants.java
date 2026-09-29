package com.ecomdemo.order.internal.saga;

import com.ecomdemo.jwt.ServiceTokenProvider;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link SagaParticipants} over HTTP, with this application's SERVICE token (Phase 32).
 *
 * <p>Its own clients rather than {@code InventoryClient}'s, for one reason: TIMEOUTS. The shared
 * inventory client has none yet (known issue KI-004), and a sweep that hangs on one silent service
 * would stop reconciling every other order behind it. Here every call gives up after
 * {@code ecomdemo.saga.read-timeout} and the order waits for the next sweep.
 */
@Component
class HttpSagaParticipants implements SagaParticipants {

    private final RestClient payment;
    private final RestClient inventory;

    HttpSagaParticipants(RestClient.Builder builder, SagaProperties properties, ServiceTokenProvider tokens) {
        this.payment = client(builder, properties.paymentBaseUrl(), properties, tokens);
        this.inventory = client(builder, properties.inventoryBaseUrl(), properties, tokens);
    }

    private static RestClient client(
            RestClient.Builder builder, String baseUrl, SagaProperties properties, ServiceTokenProvider tokens) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.token());
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public PaymentVerdict settlePayment(Long orderId, BigDecimal amount) {
        try {
            SettlementBody body = payment.post()
                    .uri("/internal/saga/orders/{orderId}/settle", orderId)
                    .body(new SettleRequest(amount))
                    .retrieve()
                    .body(SettlementBody.class);
            if (body == null || body.status() == null) {
                throw new ParticipantUnavailableException(
                        "payment-service answered the settlement of order " + orderId + " with no status", null);
            }
            return new PaymentVerdict(body.status(), body.reason());
        } catch (RestClientException e) {
            throw new ParticipantUnavailableException(
                    "payment-service could not settle order " + orderId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void closeStock(Long orderId, String reason) {
        try {
            inventory.post()
                    .uri("/api/inventory/orders/{orderId}/close", orderId)
                    .body(new CloseRequest(reason))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ParticipantUnavailableException(
                    "inventory-service could not close order " + orderId + ": " + e.getMessage(), e);
        }
    }

    private record SettleRequest(BigDecimal amount) {}

    private record SettlementBody(PaymentVerdict.Status status, String reason) {}

    private record CloseRequest(String reason) {}
}
