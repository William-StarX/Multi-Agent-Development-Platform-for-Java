package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class LibraryRelease {
    private final ControlStore store;
    private final LibraryIntegration integration;
    private final LibraryCi ci;
    private final GitHubEvidenceClient github;
    private final JdbcTemplate jdbc;

    LibraryRelease(ControlStore store, LibraryIntegration integration, LibraryCi ci, GitHubEvidenceClient github,
            JdbcTemplate jdbc) {
        this.store = store;
        this.integration = integration;
        this.ci = ci;
        this.github = github;
        this.jdbc = jdbc;
    }

    JsonNode release(String project, String candidate) {
        integration.candidate(project, candidate);
        JsonNode record = find(project, candidate);
        if (record == null) throw new ApiFailure(404, "NOT_FOUND", "No approval for this candidate.");
        return record;
    }

    ControlStore.Reply approve(String project, String candidate, JsonNode body, String key) {
        JsonNode runId = body.path("runId");
        if (body.size() != 1 || !runId.isIntegralNumber() || !runId.canConvertToLong() || runId.asLong() < 1)
            throw new ApiFailure(400, "INVALID_PAYLOAD", "Expected a positive runId.");
        JsonNode record = integration.candidate(project, candidate);
        JsonNode binding = ci.binding(project, record.get("repoId").asText());
        JsonNode run = ci.verifiedRun(binding, candidate, runId.asLong());
        String repository = binding.get("slug").asText();
        requireHead(repository, record, record.get("baseSha").asText());
        requireAllowedChanges(project, repository, record);
        return store.mutate(project, "approve-release:" + candidate, key, body, () -> {
            JsonNode previous = find(project, candidate);
            if (previous != null && ("INTEGRATED".equals(previous.get("status").asText())
                    || ("MERGING".equals(previous.get("status").asText()) && live(previous))))
                throw new ApiFailure(409, "RELEASE_IN_PROGRESS", "Reconcile the existing merge before approving again.");
            ObjectNode approval = ((ObjectNode) record).deepCopy();
            approval.remove("ciEvidence");
            approval.put("approvalId", "approval-" + UUID.randomUUID()).put("repository", repository)
                    .put("runId", runId.asLong()).put("runAttempt", run.get("run_attempt").asLong())
                    .put("approvedBy", "local-admin").put("approvedAt", Instant.now().toString())
                    .put("expiresAt", Instant.now().plusSeconds(900).toString()).put("status", "APPROVED");
            save(project, candidate, approval);
            return new ControlStore.Reply(201, approval, null);
        });
    }

    ControlStore.Reply begin(String project, String candidate, JsonNode body, String key) {
        JsonNode approval = requireApproval(project, candidate, body);
        requireLive(approval);
        JsonNode binding = ci.binding(project, approval.get("repoId").asText());
        JsonNode run = ci.verifiedRun(binding, candidate, approval.get("runId").asLong());
        if (run.get("run_attempt").asLong() != approval.get("runAttempt").asLong())
            throw new ApiFailure(409, "CI_RUN_CHANGED", "A different CI attempt requires a new approval.");
        requireHead(approval.get("repository").asText(), approval, approval.get("baseSha").asText());
        return store.mutate(project, "begin-release:" + candidate, key, body, () -> {
            ObjectNode current = (ObjectNode) requireApproval(project, candidate, body);
            requireLive(current);
            if ("APPROVED".equals(current.get("status").asText())) {
                current.put("status", "MERGING").put("mergeRequestedAt", Instant.now().toString());
                save(project, candidate, current);
            }
            return new ControlStore.Reply(200, current, null);
        });
    }

    ControlStore.Reply confirm(String project, String candidate, JsonNode body, String key) {
        JsonNode approval = requireApproval(project, candidate, body);
        requireMergeStarted(approval);
        if (!"INTEGRATED".equals(approval.get("status").asText()))
            requireHead(approval.get("repository").asText(), approval, candidate);
        return store.mutate(project, "confirm-release:" + candidate, key, body, () -> {
            ObjectNode current = (ObjectNode) requireApproval(project, candidate, body);
            requireMergeStarted(current);
            if (!"INTEGRATED".equals(current.get("status").asText())) {
                current.put("status", "INTEGRATED").put("integratedAt", Instant.now().toString());
                save(project, candidate, current);
            }
            return new ControlStore.Reply(200, current, null);
        });
    }

    private void requireHead(String repository, JsonNode candidate, String expected) {
        String ref = candidate.get("targetRef").asText();
        JsonNode remote = github.get(repository + "/git/ref/" + ref.substring("refs/".length()));
        if (!ref.equals(remote.path("ref").asText()) || !expected.equals(remote.at("/object/sha").asText()))
            throw new ApiFailure(409, "TARGET_CHANGED", "GitHub target ref does not match the expected commit.");
    }

    private void requireAllowedChanges(String project, String repository, JsonNode candidate) {
        String base = candidate.get("baseSha").asText();
        String sha = candidate.get("candidateSha").asText();
        JsonNode diff = github.get(repository + "/compare/" + base + "..." + sha + "?per_page=1");
        JsonNode files = diff.path("files");
        // GitHub returns at most 300 changed files on the first page. Refuse truncated comparisons.
        if (!"ahead".equals(diff.path("status").asText()) || !base.equals(diff.at("/merge_base_commit/sha").asText())
                || !files.isArray() || files.isEmpty() || files.size() >= 300)
            throw new ApiFailure(409, "CANDIDATE_DIFF_INVALID", "Expected a complete fast-forward candidate diff.");

        var allowed = new HashSet<String>();
        var modules = new HashSet<String>();
        var generated = new HashSet<String>();
        for (JsonNode source : candidate.get("sources")) {
            JsonNode result = integration.acceptedResult(project, source.get("taskId").asText(), source.get("revision").asLong());
            var scope = switch (result.path("toolPolicyId").asText()) {
                case "repository-files-v1" -> modules;
                case "generated-java-v1" -> generated;
                default -> allowed;
            };
            result.get("ownedPaths").forEach(path -> scope.add(path.asText()));
        }
        for (JsonNode file : files) {
            String path = file.path("filename").asText();
            String change = file.path("status").asText();
            boolean javaEdit = allowed.contains(path) && "modified".equals(change)
                    && path.matches("src/main/java/(?:[A-Za-z0-9_$]+/)*[A-Za-z0-9_$]+\\.java");
            String previous = file.path("previous_filename").asText();
            boolean moduleEdit = modules.contains(path) && (allowedModuleChange(path, change)
                    || ("renamed".equals(change) && modules.contains(previous)
                    && allowedModuleChange(previous, "removed") && allowedModuleChange(path, "added")));
            if (!javaEdit && !moduleEdit && !(generated.contains(path) && allowedGeneratedAddition(path, change)))
                throw new ApiFailure(409, "UNAUTHORIZED_CANDIDATE_PATH", "Candidate changes files outside the accepted source policy.");
        }
    }

    static boolean allowedGeneratedAddition(String path, String change) {
        return "added".equals(change) && !path.contains("/runtime/") && path.matches(
                "[a-z][a-z0-9]*(?:-[a-z0-9]+)*-service/src/main/java/(?:[A-Za-z0-9_]+/)+[A-Za-z][A-Za-z0-9_]*\\.java");
    }

    static boolean allowedModuleChange(String path, String change) {
        return switch (change) {
            case "removed" -> path.matches("(?:[a-z]+-service/)?src/main/java/(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_]+\\.java")
                    || path.matches("src/main/resources/(?:application\\.yml|db/migration/[A-Za-z0-9_.-]+\\.sql)");
            case "added", "modified" -> path.matches("(?:[a-z]+-service/(?:pom\\.xml|src/main/(?:java/(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_]+\\.java|resources/(?:application\\.yml|db/migration/[A-Za-z0-9_.-]+\\.sql)))|web-ui/(?:index\\.html|styles\\.css|app\\.js|Dockerfile|nginx\\.conf))")
                    || ("modified".equals(change) && Set.of("pom.xml", "Dockerfile", "compose.yaml", "README.md").contains(path));
            default -> false;
        };
    }

    private JsonNode requireApproval(String project, String candidate, JsonNode body) {
        if (body.size() != 1 || !body.path("approvalId").isTextual())
            throw new ApiFailure(400, "INVALID_PAYLOAD", "Expected approvalId.");
        JsonNode record = release(project, candidate);
        if (!body.get("approvalId").equals(record.get("approvalId")))
            throw new ApiFailure(409, "APPROVAL_MISMATCH", "Approval does not match this candidate.");
        return record;
    }

    private static void requireMergeStarted(JsonNode approval) {
        String status = approval.get("status").asText();
        if (!"MERGING".equals(status) && !"INTEGRATED".equals(status))
            throw new ApiFailure(409, "MERGE_NOT_STARTED", "The approved merge must be started before reconciliation.");
    }

    private static boolean live(JsonNode approval) {
        return Instant.parse(approval.get("expiresAt").asText()).isAfter(Instant.now());
    }

    private static void requireLive(JsonNode approval) {
        if (!live(approval) || "INTEGRATED".equals(approval.get("status").asText()))
            throw new ApiFailure(409, "APPROVAL_INACTIVE", "The approval has expired or the merge is already complete.");
    }

    private JsonNode find(String project, String candidate) {
        var rows = jdbc.query("SELECT body FROM jdda.integration_release WHERE project_id=? AND candidate_sha=?",
                (rs, row) -> store.parse(rs.getString(1)), project, candidate);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void save(String project, String candidate, JsonNode record) {
        jdbc.update("""
                INSERT INTO jdda.integration_release VALUES (?,?,?::jsonb)
                ON CONFLICT (project_id, candidate_sha) DO UPDATE SET body=excluded.body
                """, project, candidate, record.toString());
    }
}
