package com.ecomdemo.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A language model that says what the test tells it to, and remembers what it was asked.
 *
 * <p>No test in this build calls a real model: a real one costs money, needs a key CI does not
 * have, and never answers the same way twice - which is the opposite of a test. What is tested is
 * everything around the model: the prompt that goes out, and what this service does with each
 * kind of answer, including the bad ones a real model only produces now and then.
 */
public class ScriptedChatModel implements ChatModel {

    public static final String MODEL = "scripted-model";

    private final Deque<Supplier<ChatResponse>> replies = new ArrayDeque<>();
    private final List<Prompt> prompts = new ArrayList<>();

    /** The next call answers with this text, reporting the given token usage. */
    public ScriptedChatModel replyWith(String text, int promptTokens, int completionTokens) {
        replies.add(() -> new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder()
                        .model(MODEL)
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build()));
        return this;
    }

    /** The next call fails the way a provider does: with an exception, not an answer. */
    public ScriptedChatModel failWith(RuntimeException failure) {
        replies.add(() -> {
            throw failure;
        });
        return this;
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        Supplier<ChatResponse> reply = replies.poll();
        if (reply == null) {
            throw new IllegalStateException("ScriptedChatModel was called with no reply scripted");
        }
        return reply.get();
    }

    public synchronized List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public synchronized void reset() {
        replies.clear();
        prompts.clear();
    }

    /** A reply that satisfies every limit on ProductCopy. */
    public static String validReply() {
        return """
                {"description": "A compact 87-key keyboard with hot-swappable switches.",
                 "tags": ["Mechanical Keyboard", " hot-swap", "mechanical keyboard", "pbt keycaps"],
                 "seoTitle": "Mechanical Keyboard with Hot-Swap Switches"}
                """;
    }
}
