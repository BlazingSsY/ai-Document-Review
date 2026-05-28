package com.aireview.review.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thin wrapper around the networknt JSON Schema validator. We compile each
 * schema once and cache it by identity — the v2 pipeline reuses the same few
 * schemas (recall / verify) for every LLM call so the cache hit rate is ~100%.
 */
@Component
public class SchemaValidator {

    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final ConcurrentHashMap<JsonNode, JsonSchema> cache = new ConcurrentHashMap<>();

    public Result validate(JsonNode schemaNode, JsonNode payload) {
        JsonSchema schema = cache.computeIfAbsent(schemaNode, factory::getSchema);
        Set<ValidationMessage> errors = schema.validate(payload);
        if (errors.isEmpty()) {
            return new Result(true, "");
        }
        String reason = errors.stream()
                .map(ValidationMessage::getMessage)
                .limit(5)
                .collect(Collectors.joining("; "));
        return new Result(false, reason);
    }

    @Getter
    public static final class Result {
        private final boolean valid;
        private final String errorSummary;

        Result(boolean valid, String errorSummary) {
            this.valid = valid;
            this.errorSummary = errorSummary;
        }
    }
}
