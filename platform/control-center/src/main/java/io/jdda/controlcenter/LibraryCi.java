package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class LibraryCi {
    private final ControlStore store;
    private final JdbcTemplate jdbc;
    private final GitHubEvidenceClient github;
    LibraryCi(ControlStore store,JdbcTemplate jdbc,GitHubEvidenceClient github) {
        this.store=store;this.jdbc=jdbc;this.github=github;
    }
    private void sha(String value) {
        if(value==null || !value.matches("[0-9a-f]{40}")) throw new ApiFailure(400,"INVALID_PAYLOAD","Expected full Git SHA-1.");
    }
    ControlStore.Reply bind(String p,String repo,JsonNode request,String key) {
        ContractValidation.identifier(repo);
        if(!request.isObject() || request.size()!=4
                || !request.path("slug").asText().matches("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+")
                || !request.path("workflowId").isIntegralNumber() || request.path("workflowId").asLong()<1
                || !request.path("workflowPath").asText().matches("\\.github/workflows/[A-Za-z0-9_-]+\\.ya?ml"))
            throw new ApiFailure(400,"INVALID_PAYLOAD","Expected repository slug and pinned workflow identity.");
        sha(request.path("workflowBlobSha").asText());
        return store.mutate(p,"bind-repo:"+repo,key,request,()->{
            if(jdbc.queryForObject("SELECT count(*) FROM jdda.repository_binding WHERE project_id=? AND repo_id=?",Integer.class,p,repo)>0)
                throw new ApiFailure(409,"REPOSITORY_EXISTS","Binding is immutable; create a new repoId for changed trust settings.");
            jdbc.update("INSERT INTO jdda.repository_binding VALUES(?,?,?::jsonb)",p,repo,request.toString());
            return new ControlStore.Reply(201,request,null);
        });
    }
    JsonNode binding(String p,String repo) {
        store.topologyVersion(p);ContractValidation.identifier(repo);
        var rows=jdbc.query("SELECT body FROM jdda.repository_binding WHERE project_id=? AND repo_id=?",(rs,n)->store.parse(rs.getString(1)),p,repo);
        if(rows.isEmpty()) throw new ApiFailure(404,"NOT_FOUND","Repository binding not found.");
        return rows.getFirst();
    }
    static void validateRun(JsonNode binding,String candidate,JsonNode run,JsonNode workflow) {
        String slug=binding.get("slug").asText();
        if(!slug.equalsIgnoreCase(run.path("repository").path("full_name").asText())
                || !slug.equalsIgnoreCase(run.path("head_repository").path("full_name").asText())
                || !candidate.equals(run.path("head_sha").asText())
                || binding.get("workflowId").asLong()!=run.path("workflow_id").asLong()
                || !binding.get("workflowPath").asText().equals(run.path("path").asText())
                || !Set.of("push","workflow_dispatch").contains(run.path("event").asText())
                || !"completed".equals(run.path("status").asText()) || !"success".equals(run.path("conclusion").asText())
                || run.path("run_attempt").asLong()<1
                || !binding.get("workflowBlobSha").asText().equals(workflow.path("sha").asText()))
            throw new ApiFailure(409,"CI_EVIDENCE_MISMATCH","CI must match repository, exact candidate, pinned workflow and successful run.");
    }
    ControlStore.Reply verify(String p,String repo,String candidate,JsonNode request,String key) {
        sha(candidate);
        if(!request.isObject() || request.size()!=1 || !request.path("runId").isIntegralNumber() || request.path("runId").asLong()<1)
            throw new ApiFailure(400,"INVALID_PAYLOAD","Expected positive runId.");
        JsonNode binding=binding(p,repo);
        String slug=binding.get("slug").asText();long id=request.get("runId").asLong();
        // Remote I/O occurs before the project lock. The binding and candidate are immutable.
        JsonNode run=verifiedRun(binding,candidate,id);
        return store.mutate(p,"ci:"+repo+":"+candidate,key,request,()->{
            ObjectNode evidence=((ObjectNode)request).deepCopy();
            evidence.put("projectId",p).put("repoId",repo).put("repository",slug).put("candidateSha",candidate)
                    .put("workflowId",binding.get("workflowId").asLong()).put("workflowBlobSha",binding.get("workflowBlobSha").asText())
                    .put("runAttempt",run.get("run_attempt").asLong()).put("status","CI_PASSED")
                    .put("verifiedAt",Instant.now().toString()).put("runUrl","https://github.com/"+slug+"/actions/runs/"+id);
            jdbc.update("INSERT INTO jdda.ci_evidence VALUES(?,?,?,?::jsonb) ON CONFLICT(project_id,repo_id,candidate_sha) DO NOTHING",
                    p,repo,candidate,evidence.toString());
            return new ControlStore.Reply(200,evidence(p,repo,candidate),null);
        });
    }
    JsonNode verifiedRun(JsonNode binding, String candidate, long id) {
        String slug = binding.get("slug").asText();
        JsonNode run = github.get(slug + "/actions/runs/" + id);
        JsonNode workflow = github.get(slug + "/contents/" + binding.get("workflowPath").asText() + "?ref=" + candidate);
        validateRun(binding, candidate, run, workflow);
        if (run.path("id").asLong() != id) throw new ApiFailure(409, "CI_EVIDENCE_MISMATCH", "Run ID mismatch.");
        return run;
    }
    JsonNode evidence(String p,String repo,String candidate) {
        binding(p,repo);sha(candidate);
        var rows=jdbc.query("SELECT body FROM jdda.ci_evidence WHERE project_id=? AND repo_id=? AND candidate_sha=?",
                (rs,n)->store.parse(rs.getString(1)),p,repo,candidate);
        if(rows.isEmpty()) throw new ApiFailure(404,"NOT_FOUND","No verified CI evidence for this candidate.");
        return rows.getFirst();
    }
}
