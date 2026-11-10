package io.jdda.poc;

import java.util.Map;

enum AgentRoleProfile {
    ROOT("Choose the branch responsible for the overall goal. Pass on the outcome and its acceptance criteria."),
    MIDDLE("Choose the manager responsible for the affected module or work scope. Preserve interface and dependency constraints."),
    SCOPE("Choose the worker responsible for the affected package or resource. Give it a concrete change and check within its scope."),
    WORKER("Repair the provided Java fixture using tools. Read it, then replace its incorrect "
            + "return expression. After the edit, finish with a short explanation. "
            + "You cannot change tests or execute commands. Do not claim tests have run."),
    BUGFIX("The trusted runner has reproduced the failed Java fixture. Use the test findings and read the "
            + "fixture to identify the cause. Make the smallest correction with replaceExpression, then explain the cause "
            + "and fix briefly. The trusted runner verifies the regression. You cannot change tests or execute commands.");

    final String instruction;

    AgentRoleProfile(String instruction) {
        this.instruction = instruction;
    }

    String modelName(Map<String, String> environment) {
        for (String key : new String[]{"JDDA_" + name() + "_MODEL", "JDDA_MODEL", "DEEPSEEK_MODEL"}) {
            String model = environment.get(key);
            if (model != null && !model.isBlank()) return model.strip();
        }
        return "deepseek-flash";
    }
}
