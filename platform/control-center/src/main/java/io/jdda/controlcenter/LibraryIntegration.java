package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class LibraryIntegration {
    private final ControlStore store;
    private final JdbcTemplate jdbc;

    LibraryIntegration(ControlStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    JsonNode acceptedResult(String project, String taskId, long revision) {
        JsonNode task = store.task(project, taskId, revision);
        if (!"SUCCEEDED".equals(task.at("/snapshot/state").asText()))
            throw new ApiFailure(409, "RESULT_NOT_ACCEPTED", "The task revision must have an accepted result.");
        var results = jdbc.query("""
                SELECT jsonb_build_object('taskId', t.task_id, 'revision', t.revision,
                    'repoId', t.package->'repoId', 'baseCommit', t.package->'baseCommit',
                    'ownedPaths', t.package->'ownedPaths', 'toolPolicyId', t.package->'toolPolicyId',
                    'resultCommit', a.result->'resultCommit',
                    'resultDigest', a.result->'resultDigest')
                FROM jdda.task_record t JOIN jdda.attempt a USING (project_id, task_id, revision)
                WHERE t.project_id=? AND t.task_id=? AND t.revision=? AND t.snapshot->>'state'='SUCCEEDED'
                    AND t.snapshot->>'currentAttemptId'=a.attempt_id
                    AND a.body->>'state'='ACCEPTED' AND a.result->>'outcome'='PASSED'
                """, (rs, row) -> store.parse(rs.getString(1)), project, taskId, revision);
        if (results.isEmpty()) throw new ApiFailure(409, "RESULT_NOT_ACCEPTED", "No accepted attempt for the task revision.");
        return results.getFirst();
    }

    ControlStore.Reply register(String project, JsonNode request, String key) {
        String repo = request.path("repoId").asText();
        ContractValidation.identifier(repo);
        String candidate = request.path("candidateSha").asText();
        String base = request.path("baseSha").asText();
        JsonNode sources = request.path("sources");
        if (!request.isObject() || request.size() != 5 || !isSha(candidate) || !isSha(base) || candidate.equals(base)
                || !request.path("targetRef").asText().matches("refs/heads/[A-Za-z0-9][A-Za-z0-9._/-]{0,120}")
                || !sources.isArray() || sources.isEmpty() || sources.size() > 32)
            throw new ApiFailure(400, "INVALID_PAYLOAD", "Expected target ref, distinct base and candidate SHAs and ordered sources.");
        return store.mutate(project, "register-integration", key, request, () -> {
            var taskIds = new HashSet<String>();
            for (JsonNode source : sources) {
                String taskId = source.path("taskId").asText();
                ContractValidation.identifier(taskId);
                if (!source.isObject() || source.size() != 4 || !source.path("revision").isIntegralNumber()
                        || !source.path("revision").canConvertToLong() || source.path("revision").asLong() < 1
                        || !isSha(source.path("resultCommit").asText())
                        || !source.path("resultDigest").asText().matches("sha256:[0-9a-f]{64}") || !taskIds.add(taskId))
                    throw new ApiFailure(400, "INVALID_PAYLOAD", "Each source must identify a distinct task and its accepted commit and digest.");
                JsonNode accepted = acceptedResult(project, taskId, source.get("revision").asLong());
                if (!repo.equals(accepted.get("repoId").asText()) || !source.get("resultCommit").equals(accepted.get("resultCommit"))
                        || !source.get("resultDigest").equals(accepted.get("resultDigest")))
                    throw new ApiFailure(409, "RESULT_MISMATCH", "Candidate source differs from the accepted repository or result.");
            }
            ObjectNode record = request.deepCopy();
            record.put("projectId", project).put("createdAt", Instant.now().toString());
            int count = jdbc.update("INSERT INTO jdda.integration_candidate VALUES (?,?,?::jsonb) ON CONFLICT DO NOTHING",
                    project, candidate, record.toString());
            if (count == 0) throw new ApiFailure(409, "CANDIDATE_EXISTS", "The candidate is already registered.");
            return new ControlStore.Reply(201, record, null);
        });
    }

    JsonNode candidate(String project, String sha) {
        store.topologyVersion(project);
        if (!isSha(sha)) throw new ApiFailure(400, "INVALID_PAYLOAD", "Expected full Git SHA-1.");
        var rows = jdbc.query("""
                SELECT c.body || jsonb_build_object('ciEvidence', e.body)
                FROM jdda.integration_candidate c LEFT JOIN jdda.ci_evidence e
                    ON e.project_id=c.project_id AND e.repo_id=c.body->>'repoId' AND e.candidate_sha=c.candidate_sha
                WHERE c.project_id=? AND c.candidate_sha=?
                """, (rs, row) -> store.parse(rs.getString(1)), project, sha);
        if (rows.isEmpty()) throw new ApiFailure(404, "NOT_FOUND", "Integration candidate not found.");
        return rows.getFirst();
    }

    private static boolean isSha(String value) { return value.matches("[0-9a-f]{40}"); }
}
