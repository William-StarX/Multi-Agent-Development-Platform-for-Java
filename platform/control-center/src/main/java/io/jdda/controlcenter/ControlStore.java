package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ControlStore {
    record Reply(int status, JsonNode body, String etag) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final ContractValidation contracts;

    ControlStore(JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate transactions,
            ContractValidation contracts) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = transactions;
        this.contracts = contracts;
    }

    JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (java.io.IOException error) { throw new IllegalStateException("Invalid stored JSON.", error); }
    }

    private JsonNode find(String sql, Object... args) {
        var rows = jdbc.query(sql, (rs, row) -> parse(rs.getString(1)), args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    long topologyVersion(String project) {
        ContractValidation.identifier(project);
        var versions = jdbc.query("SELECT topology_version FROM jdda.project WHERE project_id=?",
                (rs, row) -> rs.getLong(1), project);
        if (versions.isEmpty()) throw new ApiFailure(404, "NOT_FOUND", "Project not found.");
        return versions.getFirst();
    }

    private Reply receipt(String project, String operation, String key, JsonNode body,
            Supplier<Reply> change) {
        if (key == null || !key.matches("[A-Za-z0-9._-]{1,128}"))
            throw new ApiFailure(400, "INVALID_PAYLOAD", "Idempotency-Key is required.");
        JsonNode previous = find("""
                SELECT jsonb_build_object('request', request_body, 'response', response)
                FROM jdda.request_receipt WHERE project_id=? AND operation=? AND request_key=?
                """, project, operation, key);
        if (previous != null) {
            if (!previous.get("request").equals(body))
                throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT", "Key was used with different content.");
            JsonNode response = previous.get("response");
            return new Reply(response.get("status").asInt(), response.get("body"),
                    response.get("etag").isNull() ? null : response.get("etag").asText());
        }
        Reply result = change.get();
        jdbc.update("""
                INSERT INTO jdda.request_receipt(project_id,operation,request_key,request_body,response)
                VALUES (?,?,?,?::jsonb,?::jsonb)
                """, project, operation, key, body.toString(), json.valueToTree(result).toString());
        return result;
    }

    Reply mutate(String project, String operation, String key, JsonNode body,
            Supplier<Reply> change) {
        ContractValidation.identifier(project);
        return transactions.execute(status -> {
            var rows = jdbc.queryForList("SELECT 1 FROM jdda.project WHERE project_id=? FOR UPDATE",
                    Integer.class, project);
            if (rows.isEmpty()) throw new ApiFailure(404, "NOT_FOUND", "Project not found.");
            return receipt(project, operation, key, body, change);
        });
    }

    Reply createProject(JsonNode request, String key) {
        if (!request.isObject() || request.size() != 1 || !request.path("projectId").isTextual())
            throw new ApiFailure(400, "INVALID_PAYLOAD", "Expected projectId.");
        String project = request.get("projectId").asText();
        ContractValidation.identifier(project);
        return transactions.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                    rs -> { }, "jdda-project:" + project);
            return receipt(project, "createProject", key, request, () -> {
                if (jdbc.queryForObject("SELECT count(*) FROM jdda.project WHERE project_id=?",
                        Integer.class, project) != 0)
                    throw new ApiFailure(409, "PROJECT_EXISTS", "Project already exists.");
                jdbc.update("INSERT INTO jdda.project(project_id) VALUES (?)", project);
                insertAgent(project, initialAgent(project, "root", null, "ROOT"));
                insertAgent(project, initialAgent(project, "library", "root", "LIBRARY"));
                insertAgent(project, initialAgent(project, "bugfix", "root", "BUGFIX"));
                return new Reply(201, project(project), null);
            });
        });
    }

    private ObjectNode initialAgent(String project, String id, String parent, String role) {
        ObjectNode node = json.createObjectNode().put("schemaVersion", "1").put("projectId", project)
                .put("agentId", id).put("parentId", parent).put("endpoint", (String) null)
                .put("hostId", (String) null).put("topologyVersion", 1).put("rowVersion", 1)
                .put("status", "ACTIVE");
        node.putArray("roles").add(role);
        node.putArray("capabilities");
        return node;
    }

    private void insertAgent(String project, JsonNode body) {
        jdbc.update("INSERT INTO jdda.agent(project_id,agent_id,parent_id,body) VALUES (?,?,?,?::jsonb)",
                project, body.get("agentId").asText(),
                body.get("parentId").isNull() ? null : body.get("parentId").asText(), body.toString());
    }

    private Map<String, JsonNode> agents(String project) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        jdbc.query("SELECT agent_id,body FROM jdda.agent WHERE project_id=? ORDER BY agent_id",
                rs -> { nodes.put(rs.getString(1), parse(rs.getString(2))); }, project);
        return nodes;
    }

    JsonNode project(String project) {
        ContractValidation.identifier(project);
        JsonNode result = find("""
                SELECT jsonb_build_object('projectId', p.project_id, 'topologyVersion', p.topology_version,
                    'agents', (SELECT jsonb_agg(a.body || jsonb_build_object('topologyVersion', p.topology_version)
                        ORDER BY a.agent_id) FROM jdda.agent a WHERE a.project_id=p.project_id))
                FROM jdda.project p WHERE p.project_id=?
                """, project);
        if (result == null) throw new ApiFailure(404, "NOT_FOUND", "Project not found.");
        return result;
    }

    JsonNode agent(String project, String agent) {
        ContractValidation.identifier(project);
        ContractValidation.identifier(agent);
        JsonNode node = find("""
                SELECT a.body || jsonb_build_object('topologyVersion', p.topology_version)
                FROM jdda.agent a JOIN jdda.project p USING (project_id)
                WHERE a.project_id=? AND a.agent_id=?
                """, project, agent);
        if (node == null) throw new ApiFailure(404, "NOT_FOUND", "Agent not found.");
        return node;
    }

    static boolean role(JsonNode node, String role) {
        for (JsonNode value : node.get("roles")) if (role.equals(value.asText())) return true;
        return false;
    }

    private void validTopology(Map<String, JsonNode> nodes) {
        if (nodes.size() > 1000) throw new ApiFailure(422, "INVALID_TOPOLOGY", "Node limit exceeded.");
        for (String uniqueRole : List.of("ROOT", "LIBRARY", "BUGFIX")) {
            if (nodes.values().stream().filter(node -> role(node, uniqueRole)).count() != 1)
                throw new ApiFailure(422, "INVALID_TOPOLOGY", "Exactly one " + uniqueRole + " is required.");
        }
        for (JsonNode node : nodes.values()) {
            if (role(node, "ROOT")) {
                if (!node.get("parentId").isNull())
                    throw new ApiFailure(422, "INVALID_TOPOLOGY", "Root cannot have a parent.");
            } else {
                JsonNode parent = nodes.get(node.get("parentId").asText());
                if (parent == null || !(role(parent, "ROOT") || role(parent, "MANAGER")))
                    throw new ApiFailure(422, "INVALID_TOPOLOGY", "Parent must be a Root or Manager.");
                if ((role(node, "LIBRARY") || role(node, "BUGFIX")) && !role(parent, "ROOT"))
                    throw new ApiFailure(422, "INVALID_TOPOLOGY", "Dedicated agents must report to Root.");
            }
            Set<String> visited = new HashSet<>();
            JsonNode current = node;
            while (current != null) {
                if (!visited.add(current.get("agentId").asText()) || visited.size() > 8)
                    throw new ApiFailure(422, "INVALID_TOPOLOGY", "Cycle or depth limit exceeded.");
                current = current.get("parentId").isNull() ? null : nodes.get(current.get("parentId").asText());
            }
        }
    }

    Reply registerAgent(String project, JsonNode request, String key) {
        contracts.validate("AgentRegistration", request);
        sameProject(project, request);
        return mutate(project, "registerAgent", key, request, () -> {
            var nodes = agents(project);
            String id = request.get("agentId").asText();
            if (nodes.containsKey(id)) throw new ApiFailure(409, "AGENT_EXISTS", "Agent already exists.");
            long version = topologyVersion(project) + 1;
            ObjectNode node = request.deepCopy();
            node.put("topologyVersion", version).put("rowVersion", 1).put("status", "ACTIVE");
            nodes.put(id, node);
            validTopology(nodes);
            insertAgent(project, node);
            jdbc.update("UPDATE jdda.project SET topology_version=? WHERE project_id=?", version, project);
            return new Reply(201, node, "\"1\"");
        });
    }

    Reply reparent(String project, String id, JsonNode request, String key, String ifMatch) {
        contracts.validate("ParentChange", request);
        if (ifMatch == null) throw new ApiFailure(428, "PRECONDITION_REQUIRED", "If-Match is required.");
        ObjectNode fingerprint = request.deepCopy();
        fingerprint.put("_ifMatch", ifMatch);
        return mutate(project, "reparent:" + id, key, fingerprint, () -> {
            ObjectNode node = (ObjectNode) agent(project, id);
            if (!etag(node).equals(ifMatch) ||
                    request.get("topologyVersion").asLong() != topologyVersion(project))
                throw new ApiFailure(409, "VERSION_CONFLICT", "Node or topology version changed.");
            // Runtime scheduling is not yet exposed; this guard also protects future active tasks.
            if (jdbc.queryForObject("""
                    SELECT count(*) FROM jdda.task_record WHERE project_id=?
                    AND snapshot->>'state' NOT IN ('DRAFT','SUCCEEDED','FAILED','CANCELLED','SUPERSEDED')
                    """, Integer.class, project) > 0)
                throw new ApiFailure(409, "ACTIVE_EXECUTION", "Project has active work.");
            node.put("parentId", request.get("parentId").asText());
            var nodes = agents(project);
            nodes.put(id, node);
            validTopology(nodes);
            long version = topologyVersion(project) + 1;
            node.put("topologyVersion", version).put("rowVersion", node.get("rowVersion").asLong() + 1);
            jdbc.update("UPDATE jdda.agent SET parent_id=?,body=?::jsonb WHERE project_id=? AND agent_id=?",
                    node.get("parentId").asText(), node.toString(), project, id);
            jdbc.update("UPDATE jdda.project SET topology_version=? WHERE project_id=?", version, project);
            return new Reply(200, node, etag(node));
        });
    }

    private void sameProject(String project, JsonNode body) {
        if (!project.equals(body.get("projectId").asText()))
            throw new ApiFailure(422, "PROJECT_MISMATCH", "Body and path projectId differ.");
    }

    void validateDelegation(String project, JsonNode task) {
        JsonNode steps = task.at("/delegation/steps");
        if (steps.isMissingNode()) return;
        JsonNode sender = agent(project, steps.get(0).get("from").asText());
        if (!activeRole(sender, "ROOT"))
            throw new ApiFailure(422, "INVALID_DELEGATION", "Delegation must start at the active Root.");

        for (int index = 0; index < steps.size(); index++) {
            JsonNode step = steps.get(index);
            JsonNode recipient = agent(project, step.get("to").asText());
            String expectedRole = index == 2 ? "WORKER" : "MANAGER";
            if (!sender.get("agentId").equals(step.get("from"))
                    || !step.get("from").equals(recipient.get("parentId")) || !activeRole(recipient, expectedRole))
                throw new ApiFailure(422, "INVALID_DELEGATION", "Each step must target an active direct report of the expected role.");
            sender = recipient;
        }
        if (!task.get("managerId").equals(sender.get("parentId")) || sender.get("hostId").isNull())
            throw new ApiFailure(422, "INVALID_DELEGATION", "Delegated Worker must have a host and report to the task manager.");
        Set<String> capabilities = new HashSet<>();
        sender.get("capabilities").forEach(value -> capabilities.add(value.asText()));
        for (JsonNode capability : task.get("requiredCapabilities")) {
            if (!capabilities.contains(capability.asText()))
                throw new ApiFailure(422, "INVALID_DELEGATION", "Delegated Worker lacks a required capability.");
        }
    }

    private static boolean activeRole(JsonNode agent, String role) {
        return agent.get("roles").size() == 1 && role(agent, role) && "ACTIVE".equals(agent.get("status").asText());
    }

    void validateTask(String project, JsonNode request) {
        contracts.validate("TaskPackage", request);
        sameProject(project, request);
    }

    Reply createTask(String project, JsonNode request, String key) {
        validateTask(project, request);
        return mutate(project, "createTask", key, request, () -> new Reply(201, insertDraft(project, request), "\"1\""));
    }

    // Called with the project lock held by the task or batch mutation.
    ObjectNode insertDraft(String project, JsonNode request) {
        String id = request.get("taskId").asText();
        long revision = request.get("revision").asLong();
        if (find("SELECT package FROM jdda.task_record WHERE project_id=? AND task_id=? AND revision=?",
                project, id, revision) != null)
            throw new ApiFailure(409, "TASK_EXISTS", "Task revision already exists.");
        Long latest = jdbc.queryForObject(
                "SELECT coalesce(max(revision),0) FROM jdda.task_record WHERE project_id=? AND task_id=?",
                Long.class, project, id);
        if (revision != latest + 1 || request.get("topologyVersion").asLong() != topologyVersion(project))
            throw new ApiFailure(409, "VERSION_CONFLICT", "Revision or topology version changed.");
        JsonNode manager = agent(project, request.get("managerId").asText());
        if (!(role(manager, "ROOT") || role(manager, "MANAGER")))
            throw new ApiFailure(422, "INVALID_MANAGER", "Manager must have management authority.");
        validateDelegation(project, request);
        JsonNode parent = request.get("parentTaskId");
        if (!parent.isNull() && (id.equals(parent.asText()) || jdbc.queryForObject(
                "SELECT count(*) FROM jdda.task_record WHERE project_id=? AND task_id=?",
                Integer.class, project, parent.asText()) == 0))
            throw new ApiFailure(422, "INVALID_DEPENDENCY", "Parent task must already exist in this project.");
        if (latest > 0) {
            JsonNode original = find(
                    "SELECT package FROM jdda.task_record WHERE project_id=? AND task_id=? AND revision=1",
                    project, id);
            if (!original.get("parentTaskId").equals(parent))
                throw new ApiFailure(422, "INVALID_DEPENDENCY", "Task parent is immutable across revisions.");
        }
        Set<String> dependencies = new HashSet<>();
        for (JsonNode dependency : request.get("dependencies")) {
            String predecessor = dependency.get("taskId").asText();
            long predecessorRevision = dependency.get("revision").asLong();
            if (id.equals(predecessor) || !dependencies.add(predecessor + ":" + predecessorRevision)
                    || find("SELECT package FROM jdda.task_record WHERE project_id=? AND task_id=? AND revision=?",
                    project, predecessor, predecessorRevision) == null)
                throw new ApiFailure(422, "INVALID_DEPENDENCY", "Dependencies must be unique existing task revisions.");
        }
        ObjectNode snapshot = json.createObjectNode().put("schemaVersion", "1").put("projectId", project)
                .put("taskId", id).put("revision", revision).put("state", "DRAFT").put("rowVersion", 1)
                .put("leaseEpoch", 0).put("currentAttemptId", (String) null).put("updatedAt", Instant.now().toString());
        jdbc.update("""
                INSERT INTO jdda.task_record(project_id,task_id,revision,package,snapshot)
                VALUES (?,?,?,?::jsonb,?::jsonb)
                """, project, id, revision, request.toString(), snapshot.toString());
        return snapshot;
    }

    JsonNode task(String project, String id, long revision) {
        topologyVersion(project);
        ContractValidation.identifier(id);
        JsonNode result = find("""
                SELECT jsonb_build_object('task',package,'snapshot',snapshot)
                FROM jdda.task_record WHERE project_id=? AND task_id=? AND revision=?
                """, project, id, revision);
        if (result == null) throw new ApiFailure(404, "NOT_FOUND", "Task revision not found.");
        return result;
    }

    JsonNode library(String project, String query, long after, int limit, int maxBytes) {
        topologyVersion(project);
        if (query.length() > 128 || after < 0 || limit < 1 || limit > 100 || maxBytes < 2048 || maxBytes > 65536)
            throw new ApiFailure(400, "INVALID_PAYLOAD", "Invalid search or pagination bounds.");
        var rows = jdbc.query("""
                SELECT index_id,task_id,revision,snapshot FROM jdda.task_record
                WHERE project_id=? AND index_id>? AND strpos(lower(task_id),lower(?))>0
                ORDER BY index_id LIMIT ?
                """, (rs, row) -> {
            ObjectNode summary = (ObjectNode) parse(rs.getString("snapshot"));
            summary.remove(List.of("leaseEpoch", "currentAttemptId", "updatedAt", "schemaVersion", "projectId"));
            summary.put("sourceVersion", summary.get("rowVersion").asLong());
            summary.put("summary", rs.getString("task_id") + " / " + summary.get("state").asText());
            summary.put("detailRef", "/api/v1/projects/" + project + "/tasks/" + rs.getString("task_id")
                    + "/revisions/" + rs.getLong("revision"));
            return Map.entry(rs.getLong("index_id"), summary);
        }, project, after, query, limit + 1);
        ObjectNode page = json.createObjectNode();
        var items = page.putArray("items");
        long cursor = after;
        int bytes = 128;
        for (var row : rows) {
            int size = row.getValue().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            if (items.size() == limit || bytes + size > maxBytes) break;
            items.add(row.getValue());
            cursor = row.getKey();
            bytes += size;
        }
        boolean truncated = rows.size() > items.size();
        page.put("truncated", truncated);
        page.put("nextCursor", truncated ? Long.toString(cursor) : null);
        return page;
    }

    static String etag(JsonNode node) {
        return "\"" + node.get("rowVersion").asLong() + "\"";
    }
}
