package com.ecomdemo.catalog.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What the model is asked to return, and the <strong>structured output</strong> of Phase 27.
 *
 * <p>Spring AI turns this record into a JSON schema and appends it to the prompt, then parses the
 * reply back into the record. The descriptions below are part of that schema, so they are
 * instructions the model reads, not documentation for people.
 *
 * <p>The constraints are the other half, and they are not decoration either. A model asked for
 * "at most 1000 characters" will sometimes send 1400, and a parse that succeeds is not a reply
 * that fits the {@code VARCHAR(1000)} it is going into. {@link ProductCopyGenerator} validates
 * every reply against these before anything is saved.
 */
public record ProductCopy(
        @JsonPropertyDescription("Shopper-facing product description, plain text, 2 to 4 sentences, "
                + "at most 600 characters. Only claims supported by the product details.")
        @NotBlank @Size(max = 1000)
        String description,

        @JsonPropertyDescription("3 to 6 short lowercase search tags, one or two words each.")
        @NotEmpty @Size(max = 8)
        List<@NotBlank @Size(max = 40) String> tags,

        @JsonPropertyDescription("Search-engine page title, at most 60 characters.")
        @NotBlank @Size(max = 70)
        String seoTitle) {
}
