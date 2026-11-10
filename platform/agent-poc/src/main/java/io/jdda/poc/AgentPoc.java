package io.jdda.poc;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;

public final class AgentPoc {
    public static void main(String[] args) throws Exception {
        var role = args.length == 4 && "--bugfix".equals(args[3]) ? AgentRoleProfile.BUGFIX : AgentRoleProfile.WORKER;
        var settings = System.getenv();
        run(args, role, role.modelName(settings), new ModelClient(settings).connect(role, false));
    }

    static void run(String[] args, AgentRoleProfile role, String modelName, ChatModel model) throws Exception {
        boolean repair = role == AgentRoleProfile.BUGFIX;
        if (repair ? args.length != 4 : args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("Expected fixture workspace, report and optional claimed task or Bugfix claim.");
        var json = new ObjectMapper();
        String instruction = "Fix PriceCalculator.payable: subtract discount from subtotal and never return a negative amount. "
                + "Inputs are nonnegative long values. Use readFixture and replaceExpression.";
        JsonNode task = args.length >= 3 ? json.readTree(Path.of(args[2]).toFile()) : null;
        if (repair) {
            var paths = task.at("/task/ownedPaths");
            if (!"bugfix".equals(task.at("/attempt/workerId").asText()) || !task.at("/repair/resultDigest").isTextual()
                    || !"fixture-six-tests".equals(task.at("/task/acceptanceProfile").asText()) || paths.size() != 1
                    || !FixtureTools.SOURCE.equals(paths.path(0).asText()))
                throw new IllegalArgumentException("Expected a Bugfix claim scoped to the Java fixture.");
            instruction = "The trusted baseline ran six tests. Four assertions failed, including payable after discount "
                    + "and the zero floor. Diagnose and repair the fixture. " + instruction;
            task = task.get("task");
        }
        boolean delegated = task != null && task.has("delegation");
        if (delegated || (!repair && task != null)) {
            var steps = task.path("delegation").path("steps");
            if (steps.size() != 3 || steps.path(2).path("instruction").asText().isBlank())
                throw new IllegalArgumentException("Claimed task must contain the Worker instruction.");
            String assignment = steps.get(2).get("instruction").asText();
            instruction = repair ? instruction + "\nOriginal assignment: " + assignment : assignment;
        }
        var tools = new FixtureTools(Path.of(args[0]));
        var budget = new CallBudget();
        var agent = ReactAgent.builder().name(repair ? "dedicated_bugfix" : "fixture_bugfix")
                .model(model).tools(ToolCallbacks.from(tools)).interceptors(budget)
                .systemPrompt(role.instruction)
                .build();
        agent.call(instruction);
        if (!tools.actions.contains("replaceExpression")) throw new IllegalStateException("Agent did not edit the fixture.");
        var report = Map.of("framework", "spring-ai-alibaba-agent-framework:1.1.2.2", "model", modelName,
                "modelCalls", budget.calls, "inputTokens", budget.inputTokens, "outputTokens", budget.outputTokens,
                "toolActions", tools.actions, "delegated", delegated, "role", role.name(), "testStatus", "PENDING_TRUSTED_RUNNER");
        Files.writeString(Path.of(args[1]), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Agent edit completed; calls=" + budget.calls + "; report=" + args[1]);
    }
}
