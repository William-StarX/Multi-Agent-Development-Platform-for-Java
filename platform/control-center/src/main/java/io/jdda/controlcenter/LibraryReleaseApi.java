package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{project}/library/integration-candidates/{sha}/release")
class LibraryReleaseApi {
    private final LibraryRelease releases;

    LibraryReleaseApi(LibraryRelease releases) { this.releases = releases; }

    @GetMapping
    JsonNode status(@PathVariable String project, @PathVariable String sha) { return releases.release(project, sha); }

    @PostMapping("/approve")
    ResponseEntity<JsonNode> approve(@PathVariable String project, @PathVariable String sha, @RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var reply = releases.approve(project, sha, body, key);
        return ResponseEntity.status(reply.status()).body(reply.body());
    }

    @PostMapping("/begin")
    JsonNode begin(@PathVariable String project, @PathVariable String sha, @RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return releases.begin(project, sha, body, key).body();
    }

    @PostMapping("/confirm")
    JsonNode confirm(@PathVariable String project, @PathVariable String sha, @RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return releases.confirm(project, sha, body, key).body();
    }
}
