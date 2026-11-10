package io.jdda.poc;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.lang.model.SourceVersion;
import org.springframework.ai.chat.model.ChatModel;

/** Generates scoped repository changes from a JDDA lease without shell or network tools. */
public final class RepositoryWorker {
    private static final String POLICY = "repository-files-v1";
    private static final String GENERATED = "generated-java-v1";
    private static final int OUTPUT_TOKENS = 12000;
    private static final int FILE_LIMIT = 64000;

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected workspace, claim, specification and report.");
        var json = DelegationPlanner.JSON;
        var settings = System.getenv();
        var role = AgentRoleProfile.WORKER;
        var claim = json.readTree(Path.of(args[1]).toFile());
        var spec = json.readTree(Path.of(args[2]).toFile());
        boolean raw = GENERATED.equals(claim.at("/task/toolPolicyId").asText()) && spec.path("write").size() == 1;
        var result = run(Path.of(args[0]), claim, spec, role.modelName(settings),
                new ModelClient(settings).connect(role, !raw, OUTPUT_TOKENS, Duration.ofMinutes(4)));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[3]).toFile(), result);
    }

    static Map<String, Object> run(Path workspace, JsonNode claim, JsonNode spec, String modelName, ChatModel model)
            throws Exception {
        Path root = workspace.toRealPath();
        JsonNode task = claim.path("task");
        JsonNode step = task.at("/delegation/steps/2");
        String policy = task.path("toolPolicyId").asText();
        if ((!POLICY.equals(policy) && !GENERATED.equals(policy))
                || !"ACTIVE".equals(claim.at("/lease/status").asText())
                || !step.path("to").isTextual() || !step.path("to").equals(claim.at("/attempt/workerId"))
                || step.path("instruction").asText().isBlank())
            throw new IllegalArgumentException("Expected an active repository Worker claim.");

        Set<String> writes = paths(spec.path("write"));
        Set<String> removals = paths(spec.path("remove"));
        Map<String, String> moves = new LinkedHashMap<>();
        if (!spec.path("move").isObject() || !spec.path("read").isEmpty())
            throw new IllegalArgumentException("Repository tasks require a move map and may only send task specifications.");
        spec.path("move").fields().forEachRemaining(entry -> moves.put(validPath(entry.getKey()), validPath(entry.getValue().asText())));
        Set<String> owned = new LinkedHashSet<>(writes);
        owned.addAll(removals);
        owned.addAll(moves.keySet());
        owned.addAll(moves.values());
        if (writes.isEmpty() || owned.size() != writes.size() + removals.size() + 2 * moves.size()
                || !owned.equals(paths(task.path("ownedPaths"))))
            throw new IllegalArgumentException("Specification must match the lease's exact file scope.");

        for (String file : owned) resolve(root, file);
        if (GENERATED.equals(policy)) {
            if (!removals.isEmpty() || !moves.isEmpty() || spec.has("regions") || spec.has("jdbcRefactor"))
                throw new IllegalArgumentException("Generated tasks only add Java source files.");
            for (String path : writes) {
                if (!path.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*-service/src/main/java/(?:[A-Za-z0-9_]+/)+[A-Za-z][A-Za-z0-9_]*\\.java")
                        || path.contains("/runtime/") || Files.exists(resolve(root, path), LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException("Generated tasks cannot replace the trusted baseline.");
            }
        }
        for (var move : moves.entrySet()) {
            if (!Files.isRegularFile(resolve(root, move.getKey())) || Files.exists(resolve(root, move.getValue())))
                throw new IOException("Expected an existing source and new destination for move: " + move.getKey());
        }
        var json = DelegationPlanner.JSON;
        var instructions = new ArrayList<String>();
        claim.at("/operatorGuidance/items").forEach(item -> instructions.add(item.path("text").asText()));
        String guidance = instructions.isEmpty() ? "" : "\nOperator instructions in version order. Apply the latest if they conflict: "
                + json.writeValueAsString(instructions);
        if (spec.has("jdbcRefactor")) return planJdbcRefactor(spec, step, guidance, modelName, model);
        Map<String, Region> regions = regions(spec.path("regions"));
        if (!regions.isEmpty() && (writes.size() != 1 || !removals.isEmpty() || !moves.isEmpty()))
            throw new IllegalArgumentException("Region edits must target one existing file.");
        boolean regional = !regions.isEmpty();
        Set<String> targets = regional ? regions.keySet() : writes;
        String outputField = regional ? "regions" : "files";
        boolean raw = GENERATED.equals(policy) && writes.size() == 1;
        String onlyTarget = raw ? writes.iterator().next() : null;
        String format = raw ? "Return only the full Java source for the exact requested file. "
                + "Start with its package declaration. Do not use JSON or Markdown. "
                : "Return only JSON with a single " + outputField + " property mapping each requested target "
                + "to its full replacement text. Include every requested target and no others. ";
        int maxCalls = GENERATED.equals(policy) ? 3 : 1;
        var budget = new CallBudget(maxCalls);
        var agent = ReactAgent.builder().name("repository_worker").model(model).interceptors(budget)
                .systemPrompt("You are the JDDA developer assigned to a bounded repository task. "
                        + "Preserve existing behavior and contracts unless asked to change them. You have no tools. "
                        + format
                        + "Apply operator instructions within the approved implementation constraints and target scope. "
                        + "Produce executable code, without planning comments or claims of verification.")
                .build();
        String prompt = "Project goal: " + task.at("/delegation/goal").asText()
                + "\nDelegated assignment: " + step.path("instruction").asText()
                + "\nApproved implementation constraints: " + spec.path("constraints").asText()
                + "\nRequested " + outputField + ": " + json.writeValueAsString(targets)
                + "\nPaths the runner will move without rewriting: " + json.writeValueAsString(moves) + guidance;
        JsonNode files = null;
        for (int call = 1; call <= maxCalls; call++) {
            String correction = call == 1 ? "" : "\nThe previous response failed validation. " + format;
            String response = agent.call(prompt + correction).getText();
            try {
                if (raw) {
                    files = json.createObjectNode().put(onlyTarget, javaSource(response, onlyTarget));
                } else {
                    files = validatedFiles(json.readTree(response == null ? "" : response), outputField, targets);
                }
                break;
            } catch (IOException invalid) {
                if (call == maxCalls) throw invalid;
            }
        }
        for (String file : writes) {
            Path output = resolve(root, file);
            Files.createDirectories(output.getParent());
            String content = regional ? replaceRegions(output, regions, files) : files.get(file).asText();
            Files.writeString(output, content.endsWith("\n") ? content : content + "\n");
        }
        for (var move : moves.entrySet()) {
            Path destination = resolve(root, move.getValue());
            Files.createDirectories(destination.getParent());
            Files.move(resolve(root, move.getKey()), destination);
        }
        for (String file : removals) Files.delete(resolve(root, file));
        return Map.of("framework", "Spring AI Alibaba ReactAgent 1.1.2.2", "model", modelName,
                "modelCalls", budget.calls, "inputTokens", budget.inputTokens, "outputTokens", budget.outputTokens,
                "workerId", step.path("to").asText(), "written", writes, "removed", removals, "moved", moves);
    }

    private static String javaSource(String response, String target) throws IOException {
        String content = response == null ? "" : response.strip();
        if (content.startsWith("```java\n") && content.endsWith("\n```")) {
            content = content.substring(8, content.length() - 4).strip();
        }
        String packageName = target.substring(target.indexOf("/src/main/java/") + "/src/main/java/".length(),
                target.lastIndexOf('/')).replace('/', '.');
        if (!content.startsWith("package " + packageName + ";") || content.length() > FILE_LIMIT || content.indexOf('\0') >= 0)
            throw new IOException("Expected Java source for the approved package: " + target);
        return content;
    }

    private static JsonNode validatedFiles(JsonNode result, String outputField, Set<String> targets) throws IOException {
        if (result == null || !result.isObject()) throw new IOException("Expected a JSON object containing source files.");
        JsonNode files = result.size() == 1 && result.has(outputField) ? result.path(outputField) : result;
        if (!files.isObject() || files.size() != targets.size() || !targets.stream().allMatch(files::has))
            throw new IOException("Model output does not match the approved target list.");
        for (String target : targets) {
            JsonNode content = files.path(target);
            if (!content.isTextual() || content.asText().isBlank() || content.asText().length() > FILE_LIMIT
                    || content.asText().indexOf('\0') >= 0)
                throw new IOException("Invalid output: " + target);
        }
        return files;
    }

    private static Map<String, Object> planJdbcRefactor(JsonNode spec, JsonNode step, String guidance, String modelName, ChatModel model)
            throws Exception {
        var operations = new LinkedHashSet<String>();
        for (JsonNode group : spec.path("jdbcRefactor").path("groups")) {
            for (JsonNode operation : group.path("operations")) {
                String id = operation.path("id").asText();
                if (!id.matches("[a-z][A-Za-z0-9]{0,60}") || !operations.add(id))
                    throw new IllegalArgumentException("Invalid mapper operation: " + id);
            }
        }
        if (operations.isEmpty()) throw new IllegalArgumentException("Expected mapper operations.");
        var json = DelegationPlanner.JSON;
        var budget = new CallBudget(1);
        var agent = ReactAgent.builder().name("jdbc_layer_worker").model(model).interceptors(budget)
                .systemPrompt("Design a Java mapper API for a source-preserving enterprise layer refactor. "
                        + "Each supplied operation is a different database call. Return JSON with each exact operation key "
                        + "mapped to a distinct lowerCamelCase Java method name. Use domain names, including lock semantics. "
                        + "Apply operator instructions within the supplied operation scope. "
                        + "Do not produce SQL or change transaction semantics. Return the flat map only.")
                .build();
        JsonNode result = json.readTree(agent.call(step.path("instruction").asText()
                + "\nRequired operation keys: " + json.writeValueAsString(operations) + guidance).getText());
        if (!result.isObject() || result.size() != operations.size()) throw new IOException("Incomplete mapper plan.");
        var names = new LinkedHashMap<String, String>();
        var unique = new LinkedHashSet<String>();
        for (String id : operations) {
            String name = result.path(id).asText();
            if (!name.matches("[a-z][A-Za-z0-9]{0,60}") || !SourceVersion.isName(name) || !unique.add(name))
                throw new IOException("Invalid or duplicate mapper method for " + id);
            names.put(id, name);
        }
        return Map.of("framework", "Spring AI Alibaba ReactAgent 1.1.2.2", "model", modelName,
                "modelCalls", budget.calls, "workerId", step.path("to").asText(), "mapperMethods", names,
                "inputTokens", budget.inputTokens, "outputTokens", budget.outputTokens);
    }

    private record Region(int start, int end) {}

    private static Map<String, Region> regions(JsonNode value) {
        Map<String, Region> result = new LinkedHashMap<>();
        if (value.isMissingNode()) return result;
        if (!value.isObject() || value.size() > 8) throw new IllegalArgumentException("Invalid edit regions.");
        value.fields().forEachRemaining(entry -> {
            int start = entry.getValue().path("start").asInt();
            int end = entry.getValue().path("end").asInt();
            if (!entry.getKey().matches("[a-z][a-zA-Z0-9]{0,60}") || start < 1 || end <= start)
                throw new IllegalArgumentException("Invalid edit region: " + entry.getKey());
            result.put(entry.getKey(), new Region(start, end));
        });
        return result;
    }

    private static String replaceRegions(Path file, Map<String, Region> regions, JsonNode replacements) throws IOException {
        var lines = new ArrayList<>(Files.readAllLines(file));
        int previous = lines.size() + 1;
        var ordered = regions.entrySet().stream().sorted(Comparator.comparingInt(e -> -e.getValue().start())).toList();
        for (var entry : ordered) {
            Region range = entry.getValue();
            if (range.end() > previous) throw new IOException("Overlapping or out-of-bounds regions.");
            lines.subList(range.start() - 1, range.end() - 1).clear();
            lines.add(range.start() - 1, replacements.get(entry.getKey()).asText().stripTrailing());
            previous = range.start();
        }
        return String.join("\n", lines) + "\n";
    }

    private static Set<String> paths(JsonNode list) {
        if (!list.isArray() || list.size() > 32) throw new IllegalArgumentException("Expected a bounded list of paths.");
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : list) {
            String file = value.asText();
            if (!value.isTextual() || !result.add(validPath(file)))
                throw new IllegalArgumentException("Invalid repository path: " + file);
        }
        return result;
    }

    private static String validPath(String file) {
        if (!file.matches("(?:[A-Za-z0-9_][A-Za-z0-9_.-]*/)*[A-Za-z0-9_][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("Invalid repository path: " + file);
        return file;
    }

    private static Path resolve(Path root, String file) throws IOException {
        Path target = root;
        for (Path part : Path.of(file)) {
            target = target.resolve(part);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(target) || !target.toRealPath().startsWith(root)))
                throw new IOException("Unsafe repository path: " + file);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Expected a regular file: " + file);
        return target;
    }
}
