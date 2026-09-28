package com.ecomdemo.assistant.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * A chat model that says what the test tells it to, in order - including "call this tool".
 *
 * <p>A tool call is a reply like any other: an assistant message carrying a request instead of
 * text. Spring AI then runs the tool for real (against {@link FakeStore}) and calls this model again
 * with the result appended, which is the next scripted reply's cue. So a test scripts the MODEL's
 * half of the conversation and checks everything the application did in between.
 *
 * <p>{@link #getOptions()} returns tool-calling options: Spring AI attaches the tools only to
 * options of that kind, and a plain {@code ChatOptions} would silently send the model no tools.
 */
public class ScriptedChatModel implements ChatModel {

    private final Deque<Function<Prompt, ChatResponse>> replies = new ArrayDeque<>();
    private final List<Prompt> prompts = new ArrayList<>();

    public ScriptedChatModel replyWith(String text) {
        replies.add(prompt -> response(new AssistantMessage(text)));
        return this;
    }

    public ScriptedChatModel callTool(String name, String argumentsJson) {
        replies.add(prompt -> response(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-" + UUID.randomUUID(), "function", name, argumentsJson)))
                .build()));
        return this;
    }

    /** Calls the same tool on every remaining turn: a model going round in circles. */
    public ScriptedChatModel callToolForever(String name, String argumentsJson) {
        for (int i = 0; i < 20; i++) {
            callTool(name, argumentsJson);
        }
        return this;
    }

    public ScriptedChatModel failWith(RuntimeException failure) {
        replies.add(prompt -> {
            throw failure;
        });
        return this;
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        Function<Prompt, ChatResponse> reply = replies.poll();
        if (reply == null) {
            throw new IllegalStateException("ScriptedChatModel was called with no reply scripted");
        }
        return reply.apply(prompt);
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    public synchronized List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public synchronized Prompt lastPrompt() {
        return prompts.getLast();
    }

    public synchronized void reset() {
        replies.clear();
        prompts.clear();
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().model("scripted-model").usage(new DefaultUsage(100, 20)).build());
    }
}
