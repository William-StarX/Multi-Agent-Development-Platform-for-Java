package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LibraryCiTest {
    final ObjectMapper json=new ObjectMapper();
    final String sha="a".repeat(40), blob="b".repeat(40);
    ObjectNode binding() { return json.createObjectNode().put("slug","Owner/Repo").put("workflowId",42)
            .put("workflowPath",".github/workflows/verify.yml").put("workflowBlobSha",blob); }
    ObjectNode run() {
        var run=json.createObjectNode().put("head_sha",sha).put("workflow_id",42).put("path",".github/workflows/verify.yml")
                .put("event","push").put("status","completed").put("conclusion","success").put("run_attempt",1);
        run.putObject("repository").put("full_name","Owner/Repo");
        run.putObject("head_repository").put("full_name","Owner/Repo");return run;
    }
    @Test void acceptsOnlyExactSuccessfulCandidate() {
        assertDoesNotThrow(()->LibraryCi.validateRun(binding(),sha,run(),json.createObjectNode().put("sha",blob)));
        for(String field:new String[]{"head_sha","workflow_id","path","event","status","conclusion","run_attempt"}) {
            var changed=run();changed.put(field,"wrong");
            assertThrows(ApiFailure.class,()->LibraryCi.validateRun(binding(),sha,changed,json.createObjectNode().put("sha",blob)),field);
        }
    }
    @Test void rejectsForkAndModifiedAcceptanceWorkflow() {
        var fork=run();fork.withObject("head_repository").put("full_name","Attacker/Repo");
        assertThrows(ApiFailure.class,()->LibraryCi.validateRun(binding(),sha,fork,json.createObjectNode().put("sha",blob)));
        assertThrows(ApiFailure.class,()->LibraryCi.validateRun(binding(),sha,run(),json.createObjectNode().put("sha",sha)));
    }

    @Test void generatedCandidatesMayOnlyAddScopedApplicationSources() {
        String source = "order-service/src/main/java/demo/controller/Order.java";
        assertTrue(LibraryRelease.allowedGeneratedAddition(source, "added"));
        assertFalse(LibraryRelease.allowedGeneratedAddition(source, "modified"));
        assertFalse(LibraryRelease.allowedGeneratedAddition("order-service/src/main/java/demo/runtime/Policy.java", "added"));
        assertFalse(LibraryRelease.allowedGeneratedAddition("order-service/pom.xml", "added"));
    }
}
