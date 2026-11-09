package com.ecomdemo.shared.internal;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * Makes every published response schema say what it always contains (KI-084, KI-086).
 *
 * <p>springdoc writes {@code required} only for what a request validates, so no response schema
 * had one, and a client generated from the document (the web app's, for one) typed every field of
 * every answer as optional: {@code price?: number} for a price that is never missing. Nothing in
 * the backend leaves a field out of a response (no {@code NON_NULL}): every key is written, as
 * {@code null} when it has no value. So in each schema a response returns, every property is
 * listed as required, a nullable one included: {@code required} says the key is there, and the
 * property's type says whether its value may be null (KI-087: the first version left nullable
 * properties out, which a client reads as "may be missing").
 *
 * <p>A schema that a request body also uses is left alone: there a property can be optional
 * without being nullable, and {@code required} already comes from its validation.
 *
 * <p>A nullable property that points at another schema was published as
 * {@code {"$ref": ..., "type": "null"}}. In OpenAPI 3.1 a keyword beside a {@code $ref} has to
 * hold as well, so that reads as "an object that is also null": only {@code null} fits, and
 * generators took it for "always present". It becomes {@code anyOf: [{$ref}, {type: null}]},
 * which says exactly "this object or null" (KI-086).
 */
final class ResponseSchemaContract implements OpenApiCustomizer {

    private static final String COMPONENT = "#/components/schemas/";

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null
                || openApi.getPaths() == null) {
            return;
        }
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        Set<String> returned = new TreeSet<>();
        Set<String> sent = new TreeSet<>();
        openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
            if (operation.getResponses() != null) {
                operation.getResponses().values()
                        .forEach(response -> collect(response.getContent(), schemas, returned));
            }
            if (operation.getRequestBody() != null) {
                collect(operation.getRequestBody().getContent(), schemas, sent);
            }
        }));
        returned.removeAll(sent);

        for (String name : returned) {
            Schema<?> schema = schemas.get(name);
            if (schema == null || schema.getProperties() == null) {
                continue;
            }
            schema.getProperties().replaceAll((property, value) -> objectOrNull(value));
            schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
        }
    }

    static boolean nullable(Schema<?> property) {
        return Boolean.TRUE.equals(property.getNullable())
                || hasNullType(property)
                || (property.getAnyOf() != null
                        && property.getAnyOf().stream().anyMatch(ResponseSchemaContract::hasNullType));
    }

    private static boolean hasNullType(Schema<?> schema) {
        return schema.getTypes() != null && schema.getTypes().contains("null");
    }

    /** {@code {$ref, type: null}} as {@code anyOf: [{$ref}, {type: null}]}; anything else as it is. */
    private static Schema<?> objectOrNull(Schema<?> property) {
        if (property.get$ref() == null || !nullable(property)) {
            return property;
        }
        Schema<Object> object = new JsonSchema().$ref(property.get$ref());
        Schema<Object> none = new JsonSchema().types(new LinkedHashSet<>(List.of("null")));
        return new JsonSchema().anyOf(List.of(object, none)).description(property.getDescription());
    }

    private static void collect(Content content, Map<String, Schema> schemas, Set<String> found) {
        if (content != null) {
            content.values().forEach(media -> collect(media.getSchema(), schemas, found));
        }
    }

    /** Adds the component schemas {@code schema} reaches, its own {@code $ref} included. */
    private static void collect(Schema<?> schema, Map<String, Schema> schemas, Set<String> found) {
        if (schema == null) {
            return;
        }
        String ref = schema.get$ref();
        if (ref != null && ref.startsWith(COMPONENT)) {
            String name = ref.substring(COMPONENT.length());
            if (found.add(name)) {
                collect(schemas.get(name), schemas, found);
            }
        }
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(property -> collect(property, schemas, found));
        }
        collect(schema.getItems(), schemas, found);
        for (List<Schema> options : new List[] {schema.getAllOf(), schema.getAnyOf(), schema.getOneOf()}) {
            if (options != null) {
                options.forEach(option -> collect(option, schemas, found));
            }
        }
        if (schema.getAdditionalProperties() instanceof Schema<?> values) {
            collect(values, schemas, found);
        }
    }
}
