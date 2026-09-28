package com.ecomdemo.assistant.memory;

import com.ecomdemo.assistant.AssistantProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conversations, kept in Redis as one list per conversation (Phase 29).
 *
 * <h2>What "memory" is, for a model that has none</h2>
 *
 * A chat model is stateless: every call starts from nothing. What feels like memory is the
 * application sending the earlier turns again with each new question. So memory is a storage
 * question (where the turns live between requests) and a budget question (how many are sent back -
 * every one costs tokens and time). Spring AI splits it the same way: this class is the storage,
 * {@code MessageWindowChatMemory} is the budget (the last N messages), and
 * {@code MessageChatMemoryAdvisor} does the replaying.
 *
 * <h2>Why Redis, and why this class rather than Spring AI's</h2>
 *
 * Redis because the assistant has several instances and no database: a conversation must survive
 * the next request landing on a different pod, and it is disposable - an expiry is exactly the
 * right lifetime. Spring AI 2.0 has a Redis repository of its own, but it brings Jedis (this project
 * uses Lettuce), gson, and a RediSearch index to find conversations. This needs a list, a TTL and
 * one atomic replace; forty lines on the {@link StringRedisTemplate} the project already has.
 *
 * <h2>Keys, and why the user id is in them</h2>
 *
 * The conversation id comes from the client, and a client can send anybody's. So the id this class
 * sees is always {@code <userId>:<conversationId>} - built by the service from the TOKEN, never from
 * the request. Two customers who send the same conversation id get two different conversations; one
 * customer cannot read or append to another's, whatever they send.
 */
@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    static final String PREFIX = "assistant:memory:";

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final AssistantProperties properties;

    public RedisChatMemoryRepository(StringRedisTemplate redis, JsonMapper json, AssistantProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public List<String> findConversationIds() {
        List<String> ids = new ArrayList<>();
        try (var keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + "*").count(100).build())) {
            keys.forEachRemaining(key -> ids.add(key.substring(PREFIX.length())));
        }
        return ids;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<String> stored = redis.opsForList().range(PREFIX + conversationId, 0, -1);
        return stored == null ? List.of() : stored.stream().map(this::toMessage).toList();
    }

    /**
     * Replaces the whole conversation with {@code messages} - MessageWindowChatMemory hands over the
     * already-trimmed window every time - and restarts its expiry. In one MULTI/EXEC, so a reader
     * never sees the list between the delete and the push.
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = PREFIX + conversationId;
        List<String> values = messages.stream()
                .filter(message -> message.getMessageType() != MessageType.TOOL)
                .map(this::toJson)
                .toList();
        redis.execute(new SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public List<Object> execute(RedisOperations operations) throws DataAccessException {
                operations.multi();
                operations.delete(key);
                if (!values.isEmpty()) {
                    operations.opsForList().rightPushAll(key, values);
                    operations.expire(key, properties.memoryTtl());
                }
                return operations.exec();
            }
        });
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redis.delete(PREFIX + conversationId);
    }

    private String toJson(Message message) {
        return json.writeValueAsString(new StoredMessage(message.getMessageType().name(), message.getText()));
    }

    private Message toMessage(String value) {
        StoredMessage stored = json.readValue(value, StoredMessage.class);
        String text = stored.text() == null ? "" : stored.text();
        return switch (MessageType.valueOf(stored.type())) {
            case USER -> new UserMessage(text);
            case ASSISTANT -> new AssistantMessage(text);
            case SYSTEM -> new SystemMessage(text);
            case TOOL -> throw new IllegalStateException("Tool messages are never stored");
        };
    }

    /** Only the type and the text: tool calls and metadata are not part of what is replayed. */
    record StoredMessage(String type, String text) {
    }
}
