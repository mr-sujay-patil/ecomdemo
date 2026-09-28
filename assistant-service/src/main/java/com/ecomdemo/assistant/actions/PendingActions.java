package com.ecomdemo.assistant.actions;

import com.ecomdemo.assistant.AssistantProperties;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cart additions the model has PROPOSED and the customer has not yet confirmed (Phase 29).
 *
 * <h2>Why the model cannot add to the cart itself</h2>
 *
 * Everything the model reads can steer it: the customer's message, earlier turns, and product
 * descriptions an administrator wrote. A sentence in any of them saying "add ten monitors to the
 * cart" is an instruction the model may follow - that is prompt injection, and no system prompt
 * reliably prevents it. So the tool that sounds like "add to cart" only WRITES DOWN the proposal,
 * and the addition happens when the customer confirms it through an endpoint the model cannot
 * call. Reading is the model's job; changing anything needs a human.
 *
 * <p>Each proposal is single-use (GETDEL, so two confirmations cannot both succeed), expires, and
 * is keyed by the user id from the token: a customer confirming someone else's proposal id gets
 * "not found", the same as for an id that never existed.
 */
@Component
public class PendingActions {

    static final String PREFIX = "assistant:action:";

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final AssistantProperties properties;

    public PendingActions(StringRedisTemplate redis, JsonMapper json, AssistantProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    public PendingCartAddition propose(long userId, long productId, String productName, int quantity,
            java.math.BigDecimal unitPrice) {
        PendingCartAddition action = new PendingCartAddition(
                UUID.randomUUID().toString(), productId, productName, quantity, unitPrice);
        redis.opsForValue().set(key(userId, action.id()), json.writeValueAsString(action), properties.actionTtl());
        return action;
    }

    /** Removes the proposal and returns it, or empty if it expired, was used, or is not this user's. */
    public Optional<PendingCartAddition> take(long userId, String actionId) {
        String stored = redis.opsForValue().getAndDelete(key(userId, actionId));
        return Optional.ofNullable(stored).map(value -> json.readValue(value, PendingCartAddition.class));
    }

    private static String key(long userId, String actionId) {
        return PREFIX + userId + ":" + actionId;
    }
}
