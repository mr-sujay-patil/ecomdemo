package com.ecomdemo.assistant.tools;

import java.lang.reflect.Type;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

/**
 * Sends a tool's sentence to the model as plain text, and anything else as JSON.
 *
 * <p>Spring AI's default turns every result into JSON, so a sentence arrives as {@code "..."} or
 * {@code {"note":"..."}}. Measured with llama3.2 on the evaluation set: given
 * {@code {"note":"Order 23 is not an order on your account."}} it answered with the off-topic
 * refusal, and given the same sentence as plain text it answered "You don't have an order with that
 * number." Products stay JSON - there the structure is the point.
 */
public class TextOrJsonResultConverter implements ToolCallResultConverter {

    private static final ToolCallResultConverter JSON = new DefaultToolCallResultConverter();

    @Override
    public String convert(@Nullable Object result, @Nullable Type returnType) {
        return result instanceof String text ? text : JSON.convert(result, returnType);
    }
}
