package io.jdda.poc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryWorkerTest {
    @Test void retriesMalformedGeneratedSourceBeforeWritingAndProtectsExistingFiles(@TempDir Path workspace)
            throws Exception {
        var json = DelegationPlanner.JSON;
        String path = "order-service/src/main/java/demo/controller/Order.java";
        var claim = json.readTree("""
                {"task":{"toolPolicyId":"generated-java-v1","ownedPaths":["%s"],
                  "delegation":{"goal":"Generate an order type","steps":[{},{},{"to":"worker","instruction":"Implement the order type"}]}},
                 "attempt":{"workerId":"worker"},"lease":{"status":"ACTIVE"}}
                """.formatted(path));
        var spec = json.readTree("""
                {"write":["%s"],"move":{},"remove":[],"read":[],"constraints":"Use plain Java"}
                """.formatted(path));
        var calls = new AtomicInteger();
        ChatModel model = prompt -> {
            assertFalse(Files.exists(workspace.resolve(path)));
            String response = calls.incrementAndGet() == 1 ? "package wrong.scope; record Order(long id) {}" :
                    "package demo.controller; record Order(long id) {}";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        };

        var result = RepositoryWorker.run(workspace, claim, spec, "fixture", model);
        assertEquals(2, result.get("modelCalls"));
        assertEquals("package demo.controller; record Order(long id) {}\n", Files.readString(workspace.resolve(path)));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryWorker.run(workspace, claim, spec, "fixture", model));
        assertEquals(2, calls.get());
    }

    @Test void passesOperatorInstructionsToTheModelWithinTheClaimedScope(@TempDir Path workspace) throws Exception {
        var json = DelegationPlanner.JSON;
        var claim = json.readTree("""
                {"task":{"toolPolicyId":"repository-files-v1","ownedPaths":["src/Order.java"],
                  "delegation":{"goal":"Generate an order type","steps":[{},{},{"to":"worker","instruction":"Implement the order type"}]}},
                 "attempt":{"workerId":"worker"},"lease":{"status":"ACTIVE"},
                 "operatorGuidance":{"version":2,"items":[{"text":"Use an integer ID"},{"text":"Use a long ID"}]}}
                """);
        var spec = json.readTree("""
                {"write":["src/Order.java"],"move":{},"remove":[],"read":[],"constraints":"Use plain Java"}
                """);
        ChatModel model = prompt -> {
            var input = prompt.getUserMessage().getText();
            assertTrue(input.contains("Use plain Java"));
            assertTrue(input.contains("[\"Use an integer ID\",\"Use a long ID\"]"));
            assertTrue(input.contains("Apply the latest if they conflict"));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "{\"files\":{\"src/Order.java\":\"record Order(long id) {}\"}}"))));
        };
        RepositoryWorker.run(workspace, claim, spec, "fixture", model);
        assertEquals("record Order(long id) {}\n", Files.readString(workspace.resolve("src/Order.java")));
    }
}
