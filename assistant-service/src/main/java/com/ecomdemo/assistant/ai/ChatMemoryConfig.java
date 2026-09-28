package com.ecomdemo.assistant.ai;

import com.ecomdemo.assistant.AssistantProperties;
import com.ecomdemo.assistant.memory.RedisChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The memory strategy: a sliding window of the last {@code memoryMessages} messages.
 *
 * <p>The simplest strategy, and chosen for that. Its weakness is plain: whatever slid out of the
 * window is forgotten, however important. The alternatives trade that for cost or complexity -
 * summarising old turns (a model call to save model tokens), or retrieving relevant old turns by
 * embedding them (RAG over the conversation). A shopping chat is short and mostly about the last
 * few messages, so twenty is generous; the Redis TTL forgets the rest after a day of silence.
 *
 * <p>Declared here rather than left to Spring AI's auto-configuration, which would build the same
 * class with its own default size rather than the one in {@code ecomdemo.assistant.*}.
 */
@Configuration
class ChatMemoryConfig {

    @Bean
    ChatMemory chatMemory(RedisChatMemoryRepository repository, AssistantProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.memoryMessages())
                .build();
    }
}
