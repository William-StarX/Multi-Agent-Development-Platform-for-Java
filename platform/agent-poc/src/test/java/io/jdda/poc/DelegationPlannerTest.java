package io.jdda.poc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import static org.junit.jupiter.api.Assertions.*;

class DelegationPlannerTest {
    private JsonNode topology() throws Exception {
        return new ObjectMapper().readTree("""
                {"agents":[
                  {"agentId":"root","status":"ACTIVE","roles":["ROOT"]},
                  {"agentId":"middle","parentId":"root","status":"ACTIVE","roles":["MANAGER"]},
                  {"agentId":"scope","parentId":"middle","status":"ACTIVE","roles":["MANAGER"]},
                  {"agentId":"wrong-scope","parentId":"middle","status":"ACTIVE","roles":["MANAGER"]},
                  {"agentId":"worker","parentId":"scope","status":"ACTIVE","roles":["WORKER"],"hostId":"host","capabilities":["java","maven"]},
                  {"agentId":"db-worker","parentId":"wrong-scope","status":"ACTIVE","roles":["WORKER"],"hostId":"host","capabilities":["database"]},
                  {"agentId":"bugfix","parentId":"root","status":"ACTIVE","roles":["BUGFIX"]}
                ]}
                """);
    }

    @Test void graphPassesOnlyTheValidatedParentAssignmentToTheNextStage() throws Exception {
        var prompts = new ArrayList<String>();
        var replies = List.of("{\"to\":\"middle\",\"instruction\":\"Coordinate discount repair\"}",
                "{\"to\":\"scope\",\"instruction\":\"Own payable calculation\"}",
                "{\"to\":\"worker\",\"instruction\":\"Subtract discount and clamp to zero\"}");
        var roles = new ArrayList<AgentRoleProfile>();
        var plan = DelegationPlanner.plan(topology(), "Fix discount", role -> prompt -> {
            assertTrue(prompt.getSystemMessage().getText().contains(role.instruction));
            roles.add(role);
            prompts.add(prompt.getUserMessage().getText());
            return reply(replies.get(role.ordinal()));
        }, new CallBudget());
        assertEquals(List.of(AgentRoleProfile.ROOT, AgentRoleProfile.MIDDLE, AgentRoleProfile.SCOPE), roles);
        assertFalse(prompts.get(0).contains("bugfix"));
        assertFalse(prompts.get(1).contains("wrong-scope"));
        assertTrue(prompts.get(1).contains("Coordinate discount repair"));
        assertTrue(prompts.get(2).contains("Own payable calculation"));
        assertEquals("scope", plan.at("/steps/2/from").asText());
        assertEquals("worker", plan.at("/steps/2/to").asText());
    }

    @Test void graphStopsBeforeCallingTheNextAgentWhenRootSkipsALevel() throws Exception {
        var topology = topology();
        var budget = new CallBudget();
        ChatModel model = prompt -> reply("{\"to\":\"worker\",\"instruction\":\"Skip the managers\"}");
        assertThrows(Exception.class, () -> DelegationPlanner.plan(topology, "Fix discount", role -> model, budget));
        assertEquals(1, budget.calls);
    }

    @Test void roleOverridePreservesTheSharedDeepSeekFallback() {
        var environment = Map.of("JDDA_SCOPE_MODEL", "scope-specialist", "DEEPSEEK_MODEL", "shared-model");
        assertEquals("scope-specialist", AgentRoleProfile.SCOPE.modelName(environment));
        assertEquals("shared-model", AgentRoleProfile.ROOT.modelName(environment));
        assertEquals("deepseek-flash", AgentRoleProfile.WORKER.modelName(Map.of()));
    }

    private ChatResponse reply(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
