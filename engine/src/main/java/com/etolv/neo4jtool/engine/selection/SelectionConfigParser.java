package com.etolv.neo4jtool.engine.selection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** Reads a selection from YAML (JSON is valid YAML too). Unknown keys are rejected so typos do not go unnoticed. */
public final class SelectionConfigParser {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private SelectionConfigParser() {
    }

    public static SelectionConfig parse(String text) {
        if (text == null || text.isBlank()) {
            return SelectionConfig.everything();
        }
        try {
            SelectionConfig config = MAPPER.readValue(text, SelectionConfig.class);
            return config == null ? SelectionConfig.everything() : config;
        } catch (JsonProcessingException e) {
            Throwable cause = e.getCause() instanceof IllegalArgumentException ? e.getCause() : e;
            String message = cause == e ? e.getOriginalMessage() : cause.getMessage();
            throw new IllegalArgumentException("Invalid selection: " + message, e);
        }
    }

    public static String toYaml(SelectionConfig config) {
        try {
            return MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write selection", e);
        }
    }
}
