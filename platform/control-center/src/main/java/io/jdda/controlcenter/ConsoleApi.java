package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/_console")
class ConsoleApi {
    private static final int PROJECT_PAGE_SIZE = 50;
    private final ConsoleAccess access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TaskGuidance guidance;

    ConsoleApi(ConsoleAccess access, JdbcTemplate jdbc, ObjectMapper json, TaskGuidance guidance) {
        this.access = access;
        this.jdbc = jdbc;
        this.json = json;
        this.guidance = guidance;
    }

    @GetMapping("/projects/{p}/tasks/{t}/revisions/{r}/trace")
    ResponseEntity<JsonNode> trace(@PathVariable String p, @PathVariable String t, @PathVariable long r) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(guidance.trace(p, t, r));
    }

    @PostMapping("/projects/{p}/tasks/{t}/revisions/{r}/guidance")
    ResponseEntity<JsonNode> guidance(@PathVariable String p, @PathVariable String t, @PathVariable long r,
            @RequestBody JsonNode body, Authentication actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var reply = guidance.append(p, t, r, actor.getName(), body, key);
        return ResponseEntity.status(reply.status()).cacheControl(CacheControl.noStore()).body(reply.body());
    }

    @PostMapping("/pairings")
    ResponseEntity<ConsoleAccess.Pairing> pairing() {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(access.issue());
    }

    @PostMapping("/session")
    ResponseEntity<ConsoleAccess.Session> session(@RequestBody JsonNode body) {
        String code = body.path("code").asText();
        if (!body.isObject() || body.size() != 1 || !body.path("code").isTextual() || !code.matches("[A-Za-z0-9_-]{43}"))
            throw new ApiFailure(400, "INVALID_PAYLOAD", "Expected a console pairing code.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.redeem(code));
    }

    @GetMapping("/projects")
    JsonNode projects(@RequestParam(defaultValue = "") String after) {
        if (!after.isEmpty()) ContractValidation.identifier(after);
        var ids = jdbc.queryForList("SELECT project_id FROM jdda.project WHERE project_id>? ORDER BY project_id LIMIT ?",
                String.class, after, PROJECT_PAGE_SIZE + 1);
        var response = json.createObjectNode();
        var projects = response.putArray("projects");
        ids.stream().limit(PROJECT_PAGE_SIZE).forEach(projects::add);
        response.put("nextCursor", ids.size() > PROJECT_PAGE_SIZE ? ids.get(PROJECT_PAGE_SIZE - 1) : null);
        return response;
    }
}
