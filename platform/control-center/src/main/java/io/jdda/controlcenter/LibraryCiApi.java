package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{p}/library/repositories/{repo}")
class LibraryCiApi {
    private final LibraryCi library;
    LibraryCiApi(LibraryCi library) { this.library=library; }
    @GetMapping
    JsonNode binding(@PathVariable String p, @PathVariable String repo) { return library.binding(p, repo); }
    @PostMapping
    ResponseEntity<JsonNode> bind(@PathVariable String p,@PathVariable String repo,@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        var result=library.bind(p,repo,body,key);return ResponseEntity.status(result.status()).body(result.body());
    }
    @PostMapping("/candidates/{sha}/verify-ci")
    JsonNode verify(@PathVariable String p,@PathVariable String repo,@PathVariable String sha,@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) { return library.verify(p,repo,sha,body,key).body(); }
    @GetMapping("/candidates/{sha}/ci")
    JsonNode evidence(@PathVariable String p,@PathVariable String repo,@PathVariable String sha) { return library.evidence(p,repo,sha); }
}
