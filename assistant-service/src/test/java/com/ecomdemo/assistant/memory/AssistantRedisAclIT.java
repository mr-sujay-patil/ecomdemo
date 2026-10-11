package com.ecomdemo.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.assistant.AssistantProperties;
import com.ecomdemo.assistant.actions.PendingActions;
import com.ecomdemo.assistant.actions.PendingCartAddition;
import com.ecomdemo.support.RedisAcl;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The assistant's conversations and proposed cart additions work when assistant-service is the Redis user
 * {@code assistant} (Phase 37), and that user can touch nothing else.
 *
 * <p>The code is the service's own: {@link RedisChatMemoryRepository} (SCAN, LRANGE, and a MULTI/EXEC that
 * replaces a conversation) and {@link PendingActions} (SET with a TTL, GETDEL), on Boot's {@code StringRedisTemplate}
 * logged in as {@code assistant}. Redis's {@code ACL LOG} must stay empty.
 */
@DisplayName("The assistant's Redis keys under the assistant ACL user (Phase 37)")
class AssistantRedisAclIT {

    private static GenericContainer<?> redis;

    @BeforeAll
    static void start() throws Exception {
        redis = RedisAcl.start();
    }

    @AfterAll
    static void stop() {
        if (redis != null) {
            redis.stop();
        }
    }

    private ApplicationContextRunner assistantRedis() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.host=" + redis.getHost(),
                        "spring.data.redis.port=" + redis.getMappedPort(6379),
                        "spring.data.redis.username=assistant",
                        "spring.data.redis.password=" + RedisAcl.TEST_PASSWORD);
    }

    @Test
    @DisplayName("a conversation is saved, listed, read and deleted, a proposal is made and taken, and Redis refused nothing")
    void theAssistantWorks() {
        AssistantProperties properties = new AssistantProperties(null, null, null, null, 20, null, null, 5, null);
        JsonMapper json = JsonMapper.builder().build();
        assistantRedis().run(context -> {
            RedisAcl.forgetRefusals(redis);
            StringRedisTemplate template = context.getBean(StringRedisTemplate.class);
            RedisChatMemoryRepository memory = new RedisChatMemoryRepository(template, json, properties);
            PendingActions actions = new PendingActions(template, json, properties);

            memory.saveAll("42:acl-probe", List.<Message>of(new UserMessage("hello"), new AssistantMessage("hi")));
            assertThat(memory.findConversationIds()).contains("42:acl-probe");
            assertThat(memory.findByConversationId("42:acl-probe")).extracting(Message::getText)
                    .containsExactly("hello", "hi");
            memory.saveAll("42:acl-probe", List.<Message>of(new UserMessage("again")));
            assertThat(memory.findByConversationId("42:acl-probe")).hasSize(1);
            memory.deleteByConversationId("42:acl-probe");
            assertThat(memory.findByConversationId("42:acl-probe")).isEmpty();

            PendingCartAddition proposed = actions.propose(42, 7, "ACL probe", 1, new BigDecimal("12.50"));
            assertThat(actions.take(42, proposed.id())).contains(proposed);
            assertThat(actions.take(42, proposed.id())).isEmpty();

            assertThat(RedisAcl.refusals(redis)).as("Redis's ACL LOG").isEmpty();
        });
    }

    @Test
    @DisplayName("the assistant user is refused the catalogue cache, the rate limiter's keys and FLUSHALL")
    void theAssistantUserIsRefusedEverythingElse() throws Exception {
        assertThat(RedisAcl.cli(redis, "assistant", "GET", "product::1")).startsWith("NOPERM");
        assertThat(RedisAcl.cli(redis, "assistant", "GET", "request_rate_limiter.{x}.tokens")).startsWith("NOPERM");
        assertThat(RedisAcl.cli(redis, "assistant", "FLUSHALL")).startsWith("NOPERM");
        assertThat(RedisAcl.cli(redis, "assistant", "KEYS", "*")).startsWith("NOPERM");
    }
}
