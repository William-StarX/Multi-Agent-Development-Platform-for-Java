package io.jdda.controlcenter;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class GovernancePolicy {
    private static final int MAX_CAPACITY = 10000;
    private final ObjectReader reader;
    private volatile Snapshot current = new Snapshot("default", 0, null, new Settings(0, Map.of()));

    record Settings(int maxActiveAttempts, Map<String, Integer> projectLimits) {
        Settings {
            projectLimits = projectLimits == null ? Map.of() : Map.copyOf(projectLimits);
        }
    }

    record Snapshot(String source, long revision, Instant appliedAt, Settings settings) {}

    GovernancePolicy(ObjectMapper json) {
        reader = json.readerFor(Settings.class)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    Snapshot snapshot() { return current; }

    synchronized void apply(String content, String source) {
        if (content == null || content.isBlank() || content.length() > 65536)
            throw new IllegalArgumentException("Expected a nonempty governance document of up to 65536 characters.");
        Settings next;
        try {
            next = reader.readValue(content);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid governance JSON.");
        }
        if (next == null) throw new IllegalArgumentException("Expected a governance object.");
        validate(next);
        if (current.settings().equals(next) && current.source().equals(source)) return;

        current = new Snapshot(source, current.revision() + 1, Instant.now(), next);
    }

    private static void validate(Settings policy) {
        capacity(policy.maxActiveAttempts());
        if (policy.projectLimits().size() > 256) throw new IllegalArgumentException("Too many project limits.");
        policy.projectLimits().forEach((id, limit) -> {
            ContractValidation.identifier(id);
            capacity(limit);
        });
    }

    private static void capacity(int value) {
        if (value < 0 || value > MAX_CAPACITY) throw new IllegalArgumentException("Expected a limit from 0 to 10000.");
    }

    int maxActiveAttempts(String project) {
        Settings settings = current.settings();
        return settings.projectLimits().getOrDefault(project, settings.maxActiveAttempts());
    }

}
