package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaskGuidance {
    private record Target(String taskId, long revision) {}
    private static final int MAX_INSTRUCTIONS = 8;
    private static final Set<String> EDITABLE = Set.of("DRAFT", "WAITING_DEPENDENCIES", "READY", "LEASED", "RUNNING", "BLOCKED");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ControlStore store;

    TaskGuidance(JdbcTemplate jdbc, ObjectMapper json, ControlStore store) {
        this.jdbc = jdbc;
        this.json = json;
        this.store = store;
    }

    static boolean enabled(JsonNode task) {
        return task.path("operatorGuidance").asBoolean(false)
                && Set.of("repository-files-v1", "generated-java-v1").contains(task.path("toolPolicyId").asText());
    }

    private ObjectNode instructions(String p, String t, long r) {
        var result = json.createObjectNode();
        var items = result.putArray("items");
        var rows = jdbc.query("""
                SELECT version, instruction, actor, created_at FROM jdda.task_guidance
                WHERE project_id=? AND task_id=? AND revision=? ORDER BY version
                """, (rs, n) -> json.createObjectNode().put("version", rs.getInt("version"))
                        .put("text", rs.getString("instruction")).put("actor", rs.getString("actor"))
                        .put("createdAt", rs.getTimestamp("created_at").toInstant().toString()), p, t, r);
        rows.forEach(items::add);
        return result.put("version", rows.isEmpty() ? 0 : rows.getLast().get("version").asInt());
    }

    ControlStore.Reply append(String p, String t, long r, String actor, JsonNode body, String key) {
        String instruction = body.path("text").asText().strip();
        var expected = body.path("expectedVersion");
        if (!body.isObject() || body.size() != 2 || !body.path("text").isTextual() || instruction.isBlank()
                || instruction.length() > 1200 || instruction.indexOf('\0') >= 0
                || !expected.isIntegralNumber() || !expected.canConvertToInt() || expected.asInt() < 0)
            throw new ApiFailure(400, "INVALID_PAYLOAD", "Expected a task instruction and its current version.");
        return store.mutate(p, "guidance:" + t + ":" + r + ":" + actor, key, body, () -> {
            var task = store.task(p, t, r);
            if (!enabled(task.path("task")) || !EDITABLE.contains(task.at("/snapshot/state").asText()))
                throw new ApiFailure(409, "GUIDANCE_UNAVAILABLE", "This task does not accept instructions in its current state.");
            int latest = instructions(p, t, r).get("version").asInt();
            if (latest != expected.asInt()) throw new ApiFailure(409, "INSTRUCTION_UPDATED", "The instructions changed. Refresh and review them.");
            if (latest >= MAX_INSTRUCTIONS) throw new ApiFailure(409, "GUIDANCE_LIMIT", "This task revision has reached its instruction limit.");
            jdbc.update("""
                    INSERT INTO jdda.task_guidance(project_id,task_id,revision,version,instruction,actor) VALUES (?,?,?,?,?,?)
                    """, p, t, r, latest + 1, instruction, actor);
            return new ControlStore.Reply(201, instructions(p, t, r), null);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    JsonNode checkpoint(String p, String attempt, String worker, long epoch) {
        var rows = jdbc.query("""
                SELECT a.task_id, a.revision FROM jdda.attempt a JOIN jdda.task_record t USING(project_id,task_id,revision)
                WHERE a.project_id=? AND a.attempt_id=? AND a.worker_id=? AND (a.lease->>'leaseEpoch')::bigint=?
                  AND a.lease->>'status'='ACTIVE' AND (a.lease->>'expiresAt')::timestamptz > now()
                  AND t.snapshot->>'currentAttemptId'=a.attempt_id AND t.snapshot->>'state' IN ('LEASED','RUNNING')
                  AND t.package->>'toolPolicyId' IN ('repository-files-v1','generated-java-v1')
                  AND t.package->'operatorGuidance'='true'::jsonb
                """, (rs, n) -> new Target(rs.getString("task_id"), rs.getLong("revision")), p, attempt, worker, epoch);
        if (rows.isEmpty()) throw new ApiFailure(409, "GUIDANCE_UNAVAILABLE", "An active instruction-enabled attempt is required.");
        var target = rows.getFirst();
        return instructions(p, target.taskId(), target.revision());
    }

    void requireCurrent(String p, String t, long r, Integer version) {
        int latest = instructions(p, t, r).get("version").asInt();
        if (version == null || version != latest)
            throw new ApiFailure(409, "INSTRUCTION_UPDATED", "Read the current instructions before submitting a result.");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    JsonNode trace(String p, String t, long r) {
        var view = store.task(p, t, r);
        var task = view.get("task");
        var state = view.get("snapshot");
        var trace = json.createObjectNode();
        trace.set("snapshot", state);
        trace.set("delegation", task.path("delegation").isObject() ? task.path("delegation") : json.nullNode());
        var guidance = instructions(p, t, r);
        guidance.put("available", enabled(task) && EDITABLE.contains(state.path("state").asText())
                && guidance.get("version").asInt() < MAX_INSTRUCTIONS);
        trace.set("guidance", guidance);
        trace.set("attempts", rows("""
                SELECT jsonb_build_object('agentId',worker_id,'attemptId',attempt_id,'state',body->'state',
                    'createdAt',body->'createdAt','finishedAt',body->'finishedAt','outcome',result->'outcome',
                    'commit',result->'resultCommit','digest',result->'resultDigest','guidanceVersion',body->'guidanceVersion')
                FROM jdda.attempt WHERE project_id=? AND task_id=? AND revision=?
                ORDER BY (body->>'attemptNumber')::bigint DESC LIMIT 50
                """, p, t, r));
        trace.set("events", rows("""
                SELECT jsonb_build_object('state',body->'state','createdAt',created_at)
                FROM jdda.event WHERE project_id=? AND task_id=? AND revision=? AND body->'state' IS NOT NULL
                ORDER BY event_id DESC LIMIT 50
                """, p, t, r));
        trace.set("releases", rows("""
                SELECT jsonb_build_object('candidate',c.candidate_sha,'targetRef',c.body->'targetRef',
                    'status',coalesce(l.body->>'status',e.body->>'status','CANDIDATE'),'runId',e.body->'runId')
                FROM jdda.integration_candidate c LEFT JOIN jdda.integration_release l USING(project_id,candidate_sha)
                LEFT JOIN jdda.ci_evidence e ON e.project_id=c.project_id AND e.candidate_sha=c.candidate_sha
                    AND e.repo_id=c.body->>'repoId'
                WHERE c.project_id=? AND c.body->'sources' @> jsonb_build_array(jsonb_build_object('taskId',?::text,'revision',?::bigint))
                ORDER BY c.body->>'createdAt' DESC LIMIT 20
                """, p, t, r));
        return trace;
    }

    private JsonNode rows(String sql, Object... parameters) {
        return json.valueToTree(jdbc.query(sql, (rs, n) -> store.parse(rs.getString(1)), parameters));
    }
}
