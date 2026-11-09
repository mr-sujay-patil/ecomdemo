package com.ecomdemo.shared.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rules {@link ResponseSchemaContract} applies, on a document built by hand (KI-084, KI-086). */
class ResponseSchemaContractTest {

    @Test
    @DisplayName("a returned schema lists every property as required, the nullable ones too (KI-087)")
    void requiresEveryProperty() {
        OpenAPI api = document();

        new ResponseSchemaContract().customise(api);

        assertThat(schema(api, "Reply").getRequired()).containsExactly("id", "lines", "note", "pending");
        assertThat(schema(api, "Line").getRequired()).containsExactly("name", "extra");
    }

    @Test
    @DisplayName("a nullable property pointing at a schema becomes anyOf [that schema, null] (KI-086)")
    void aNullableObjectIsTheObjectOrNull() {
        OpenAPI api = document();

        new ResponseSchemaContract().customise(api);

        Schema<?> pending = schema(api, "Reply").getProperties().get("pending");
        assertThat(pending.get$ref()).isNull();
        assertThat(pending.getDescription()).isEqualTo("Waiting for a confirmation, or null.");
        assertThat(pending.getAnyOf()).extracting(Schema::get$ref)
                .containsExactly("#/components/schemas/Line", null);
        assertThat(pending.getAnyOf().get(1).getTypes()).containsExactly("null");
    }

    @Test
    @DisplayName("a schema a request body also uses keeps its own required list")
    void leavesRequestSchemasAlone() {
        OpenAPI api = document();

        new ResponseSchemaContract().customise(api);

        assertThat(schema(api, "Payload").getRequired()).containsExactly("text");
    }

    /**
     * {@code POST /things} takes a Payload and answers a Reply: {id, lines: [Line], note?,
     * pending: {$ref Line, type null}}; a Line is {name, extra?}.
     */
    private static OpenAPI document() {
        Schema<Object> line = new JsonSchema()
                .addProperty("name", new JsonSchema().types(Set.of("string")))
                .addProperty("extra", new JsonSchema().types(Set.of("string", "null")));
        Schema<Object> pending = new JsonSchema().$ref("#/components/schemas/Line")
                .types(Set.of("null")).description("Waiting for a confirmation, or null.");
        Schema<Object> reply = new JsonSchema()
                .addProperty("id", new JsonSchema().types(Set.of("integer")))
                .addProperty("lines", new JsonSchema().types(Set.of("array"))
                        .items(new JsonSchema().$ref("#/components/schemas/Line")))
                .addProperty("note", new JsonSchema().types(Set.of("string")).nullable(true))
                .addProperty("pending", pending);
        Schema<Object> payload = new JsonSchema()
                .addProperty("text", new JsonSchema().types(Set.of("string")))
                .addProperty("hint", new JsonSchema().types(Set.of("string")));
        payload.setRequired(List.of("text"));

        Operation post = new Operation()
                .requestBody(new RequestBody().content(json("#/components/schemas/Payload")))
                .responses(new ApiResponses().addApiResponse("200",
                        new ApiResponse().content(json("#/components/schemas/Reply"))));
        return new OpenAPI()
                .paths(new Paths().addPathItem("/things", new PathItem().post(post)))
                .components(new Components()
                        .addSchemas("Reply", reply)
                        .addSchemas("Line", line)
                        .addSchemas("Payload", payload));
    }

    private static Content json(String ref) {
        return new Content().addMediaType("application/json",
                new MediaType().schema(new JsonSchema().$ref(ref)));
    }

    private static Schema<?> schema(OpenAPI api, String name) {
        return api.getComponents().getSchemas().get(name);
    }
}
