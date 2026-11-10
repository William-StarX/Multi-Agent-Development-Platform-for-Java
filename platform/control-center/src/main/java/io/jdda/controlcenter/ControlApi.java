package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ControlApi {
    private final ControlStore store;
    private final GovernancePolicy governance;

    ControlApi(ControlStore store, GovernancePolicy governance) {
        this.store = store;
        this.governance = governance;
    }

    @GetMapping("/governance")
    ResponseEntity<GovernancePolicy.Snapshot> governance() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(governance.snapshot());
    }

    private ResponseEntity<JsonNode> response(ControlStore.Reply reply) {
        var builder = ResponseEntity.status(reply.status());
        if (reply.etag() != null) builder.eTag(reply.etag());
        return builder.body(reply.body());
    }

    @PostMapping("/projects")
    ResponseEntity<JsonNode> createProject(@RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return response(store.createProject(body, key));
    }

    @GetMapping("/projects/{p}")
    JsonNode project(@PathVariable String p) { return store.project(p); }

    @PostMapping("/projects/{p}/agents")
    ResponseEntity<JsonNode> agent(@PathVariable String p, @RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return response(store.registerAgent(p, body, key));
    }

    @GetMapping("/projects/{p}/agents/{agent}")
    ResponseEntity<JsonNode> agent(@PathVariable String p, @PathVariable String agent) {
        JsonNode node = store.agent(p, agent);
        return ResponseEntity.ok().eTag(ControlStore.etag(node)).body(node);
    }

    @PutMapping("/projects/{p}/agents/{agent}/parent")
    ResponseEntity<JsonNode> parent(@PathVariable String p, @PathVariable String agent,
            @RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return response(store.reparent(p, agent, body, key, ifMatch));
    }

    @PostMapping("/projects/{p}/tasks")
    ResponseEntity<JsonNode> task(@PathVariable String p, @RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return response(store.createTask(p, body, key));
    }

    @GetMapping("/projects/{p}/tasks/{t}/revisions/{r}")
    ResponseEntity<JsonNode> task(@PathVariable String p, @PathVariable String t, @PathVariable long r) {
        JsonNode view = store.task(p, t, r);
        return ResponseEntity.ok().eTag(ControlStore.etag(view.get("snapshot"))).body(view);
    }

    @GetMapping("/projects/{p}/library/tasks")
    JsonNode search(@PathVariable String p, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "8192") int maxBytes) {
        return store.library(p, q, after, limit, maxBytes);
    }
}
