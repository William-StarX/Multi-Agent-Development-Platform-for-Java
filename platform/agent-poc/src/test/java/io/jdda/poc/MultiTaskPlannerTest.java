package io.jdda.poc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static io.jdda.poc.DelegationPlanner.JSON;
import static org.junit.jupiter.api.Assertions.*;

class MultiTaskPlannerTest {
    @Test void plansWithinApprovedScopesAndKeepsExplicitIntegrationGates() throws Exception {
        var request = input();
        var original = request.deepCopy();
        var budget = new CallBudget(10);
        var plan = MultiTaskPlanner.plan(topology(), request, role -> prompt -> {
            String message = prompt.getUserMessage().getText();
            assertFalse(message.contains("abcdef0123456789"));
            if (message.startsWith("Goal:")) {
                assertEquals(AgentRoleProfile.ROOT, role);
                return reply("""
                        {"tasks":[
                          {"taskId":"JAVA","instruction":"Implement the handler","dependsOn":[]},
                          {"taskId":"DB","instruction":"Apply the schema change","dependsOn":["JAVA"]},
                          {"taskId":"REVIEW","instruction":"Check compatibility","dependsOn":["DB"]}
                        ]}
                        """);
            }
            String choices = message.lines().skip(1).findFirst().orElseThrow();
            assertFalse(choices.contains(","), choices);
            assertFalse(choices.contains("worker-incompatible"), choices);
            String child = choices.split("\"")[1];
            return reply("{\"to\":\"" + child + "\",\"instruction\":\"Follow the approved work.\"}");
        }, budget);
        assertEquals(10, budget.calls);
        assertEquals(original, request);
        assertEquals("worker-java", plan.at("/tasks/0/delegation/steps/2/to").asText());
        assertEquals("worker-db", plan.at("/tasks/1/delegation/steps/2/to").asText());
        assertEquals("worker-java", plan.at("/tasks/2/delegation/steps/2/to").asText());
        assertEquals(original.at("/units/1/task/baseCommit"), plan.at("/tasks/1/baseCommit"));
        assertEquals(original.at("/units/1/task/ownedPaths"), plan.at("/tasks/1/ownedPaths"));
        assertEquals(original.at("/units/1/task/budget"), plan.at("/tasks/1/budget"));
        assertEquals(1, plan.at("/tasks/1/dependencies").size());
        assertEquals("INTEGRATED", plan.at("/tasks/1/dependencies/0/gate").asText());
        assertEquals("DB", plan.at("/tasks/2/dependencies/0/taskId").asText());
        assertEquals("VERIFIED", plan.at("/tasks/2/dependencies/0/gate").asText());
    }

    @Test void stopsBeforeDelegatingCyclicOrUnauthorizedPlans() throws Exception {
        for (String output : List.of("""
                {"tasks":[{"taskId":"JAVA","instruction":"A","dependsOn":["DB"]},
                          {"taskId":"DB","instruction":"B","dependsOn":[]},
                          {"taskId":"REVIEW","instruction":"C","dependsOn":[]}]}
                """, """
                {"tasks":[{"taskId":"JAVA","instruction":"A","dependsOn":[],"ownedPaths":["**"]},
                          {"taskId":"DB","instruction":"B","dependsOn":[]},
                          {"taskId":"REVIEW","instruction":"C","dependsOn":[]}]}
                """)) {
            var budget = new CallBudget(10);
            assertThrows(IllegalArgumentException.class,
                    () -> MultiTaskPlanner.plan(topology(), input(), role -> prompt -> reply(output), budget));
            assertEquals(1, budget.calls);
        }
    }

    private ObjectNode input() {
        var request = JSON.createObjectNode().put("goal", "Change a handler and schema, then check compatibility");
        var units = request.putArray("units");
        units.addObject().put("summary", "Update Java handler").set("task", template("JAVA", "java"));
        var db = template("DB", "db");
        db.withArray("dependencies").addObject().put("taskId", "JAVA").put("revision", 1).put("gate", "INTEGRATED");
        units.addObject().put("summary", "Write a schema migration").set("task", db);
        units.addObject().put("summary", "Check the result").set("task", template("REVIEW", "java"));
        return request;
    }

    private ObjectNode template(String id, String scope) {
        var task = JSON.createObjectNode().put("taskId", id).put("projectId", "example").put("topologyVersion", 7)
                .put("managerId", "scope-" + scope).put("revision", 1).put("baseCommit", "abcdef0123456789".repeat(4));
        task.putArray("requiredCapabilities").add(scope);
        task.putArray("ownedPaths").add("src/" + id);
        task.putArray("dependencies");
        task.putObject("budget").put("tokenBudget", 1000);
        return task;
    }

    private JsonNode topology() throws Exception {
        return JSON.readTree("""
                {"projectId":"example","topologyVersion":7,"agents":[
                  {"agentId":"root","status":"ACTIVE","roles":["ROOT"]},
                  {"agentId":"middle-java","parentId":"root","status":"ACTIVE","roles":["MANAGER"]},
                  {"agentId":"middle-db","parentId":"root","status":"ACTIVE","roles":["MANAGER"]},
                  {"agentId":"scope-java","parentId":"middle-java","status":"ACTIVE","roles":["MANAGER"]},
                  {"agentId":"scope-db","parentId":"middle-db","status":"ACTIVE","roles":["MANAGER"]},
                  {"agentId":"worker-java","parentId":"scope-java","status":"ACTIVE","roles":["WORKER"],"hostId":"a","capabilities":["java"]},
                  {"agentId":"worker-incompatible","parentId":"scope-db","status":"ACTIVE","roles":["WORKER"],"hostId":"a","capabilities":["java"]},
                  {"agentId":"worker-db","parentId":"scope-db","status":"ACTIVE","roles":["WORKER"],"hostId":"b","capabilities":["db"]}
                ]}
                """);
    }

    private ChatResponse reply(String message) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(message))));
    }
}
