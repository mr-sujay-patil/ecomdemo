package com.ecomdemo.catalog.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(name = "GeneratedDescriptionResponse",
        description = "Copy written by the configured language model, already saved to the product.")
public record GeneratedDescriptionResponse(
        @Schema(example = "1")
        Long productId,

        @Schema(description = "Now the product's description.",
                example = "A compact 87-key mechanical keyboard with hot-swappable switches.")
        String description,

        @Schema(example = "[\"mechanical keyboard\", \"hot-swap\", \"pbt keycaps\"]")
        List<String> tags,

        @Schema(example = "Mechanical Keyboard with Hot-Swap Switches")
        String seoTitle,

        @Schema(description = "The model that wrote it, as the provider reported it. Null if not reported.",
                example = "gpt-4.1-mini", nullable = true)
        String model,

        @Schema(description = "Tokens in the prompt. Null if the provider did not report usage.", nullable = true)
        Integer promptTokens,

        @Schema(description = "Tokens in the reply. Null if the provider did not report usage.", nullable = true)
        Integer completionTokens,

        Instant generatedAt) {

    static GeneratedDescriptionResponse from(DescriptionGeneration generation) {
        return new GeneratedDescriptionResponse(
                generation.getProductId(),
                generation.getDescription(),
                generation.getTags(),
                generation.getSeoTitle(),
                generation.getModel(),
                generation.getPromptTokens(),
                generation.getCompletionTokens(),
                generation.getGeneratedAt());
    }
}
