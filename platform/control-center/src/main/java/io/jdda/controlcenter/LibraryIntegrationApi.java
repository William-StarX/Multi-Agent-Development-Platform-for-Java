package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{project}/library")
class LibraryIntegrationApi {
    private final LibraryIntegration integration;

    LibraryIntegrationApi(LibraryIntegration integration) { this.integration = integration; }

    @GetMapping("/tasks/{task}/revisions/{revision}/accepted-result")
    JsonNode accepted(@PathVariable String project, @PathVariable String task, @PathVariable long revision) {
        return integration.acceptedResult(project, task, revision);
    }

    @PostMapping("/integration-candidates")
    ResponseEntity<JsonNode> register(@PathVariable String project, @RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var reply = integration.register(project, body, key);
        return ResponseEntity.status(reply.status()).body(reply.body());
    }

    @GetMapping("/integration-candidates/{sha}")
    JsonNode candidate(@PathVariable String project, @PathVariable String sha) {
        return integration.candidate(project, sha);
    }
}
