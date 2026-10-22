package io.datasync.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.Reader;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the YAML config and resolves {@code ${env:NAME}} references. Unknown fields are ignored so a file
 * written for later milestones (schedule, watermark, ...) still loads.
 */
public final class ConfigLoader {

    private static final Pattern ENV_REF = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ConfigLoader() {}

    public static DataSyncConfig load(Reader yaml, UnaryOperator<String> env) throws IOException {
        return YAML.treeToValue(resolve(YAML.readTree(yaml), env), DataSyncConfig.class);
    }

    private static JsonNode resolve(JsonNode node, UnaryOperator<String> env) {
        if (node.isTextual()) return TextNode.valueOf(substitute(node.textValue(), env));
        if (node instanceof ObjectNode obj) obj.properties().forEach(e -> e.setValue(resolve(e.getValue(), env)));
        if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) arr.set(i, resolve(arr.get(i), env));
        }
        return node;
    }

    private static String substitute(String text, UnaryOperator<String> env) {
        return ENV_REF.matcher(text).replaceAll(m -> {
            String value = env.apply(m.group(1));
            if (value == null) throw new IllegalArgumentException("environment variable " + m.group(1) + " is not set");
            return Matcher.quoteReplacement(value);
        });
    }
}
