package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{p}")
class RuntimeApi {
    private final TaskRuntime runtime;
    private final TaskGuidance guidance;
    RuntimeApi(TaskRuntime runtime, TaskGuidance guidance) { this.runtime=runtime; this.guidance=guidance; }
    private ResponseEntity<JsonNode> response(ControlStore.Reply reply) {
        var result=ResponseEntity.status(reply.status());
        if(reply.etag()!=null) result.eTag(reply.etag());
        return result.body(reply.status()==204?null:reply.body());
    }
    private String agent(Authentication authentication,String project) {
        var principal=(OAuth2AuthenticatedPrincipal)authentication.getPrincipal();
        if(!project.equals(principal.getAttribute("projectId")))
            throw new ApiFailure(403,"FORBIDDEN","Credential belongs to another project.");
        return principal.getAttribute("agentId");
    }
    @PostMapping("/agents/{agent}/credentials")
    ResponseEntity<JsonNode> credential(@PathVariable String p,@PathVariable String agent,@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestHeader(value="If-None-Match",required=false) String createOnly) {
        return response(runtime.credential(p,agent,body,key,createOnly));
    }
    @PostMapping("/agents/{agent}/credentials/revoke")
    ResponseEntity<JsonNode> revoke(@PathVariable String p,@PathVariable String agent,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestHeader(value="If-Match",required=false) String expected) {
        return response(runtime.revoke(p,agent,key,expected));
    }
    @PostMapping("/tasks/{t}/revisions/{r}/publish")
    ResponseEntity<JsonNode> publish(@PathVariable String p,@PathVariable String t,@PathVariable long r,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestHeader(value="If-Match",required=false) String match) { return response(runtime.publish(p,t,r,key,match)); }
    @PostMapping("/task-batches")
    ResponseEntity<JsonNode> publishBatch(@PathVariable String p, @RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return response(runtime.publishBatch(p,body,key));
    }
    @GetMapping("/dispatch")
    ResponseEntity<JsonNode> dispatch(@PathVariable String p) { return response(runtime.dispatch(p)); }
    @PutMapping("/dispatch")
    ResponseEntity<JsonNode> dispatch(@PathVariable String p, @RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestHeader(value="If-Match",required=false) String match) {
        return response(runtime.setDispatch(p,body,key,match));
    }
    @PostMapping("/worker/claims")
    ResponseEntity<JsonNode> claim(@PathVariable String p,Authentication authentication,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestParam(required=false) String taskId, @RequestParam(required=false) Long revision) {
        return response(runtime.claim(p,agent(authentication,p),key,false,taskId,revision));
    }
    @PostMapping("/bugfix/claims")
    ResponseEntity<JsonNode> claimRepair(@PathVariable String p, Authentication authentication,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return response(runtime.claim(p,agent(authentication,p),key,true,null,null));
    }
    @GetMapping("/library/tasks/{t}/revisions/{r}/failure-evidence")
    JsonNode failureEvidence(@PathVariable String p, @PathVariable String t, @PathVariable long r) {
        return runtime.failureEvidence(p,t,r);
    }
    @GetMapping("/attempts/{attempt}/instructions")
    ResponseEntity<JsonNode> instructions(@PathVariable String p, @PathVariable String attempt,
            @RequestParam long epoch, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(guidance.checkpoint(p, attempt, agent(authentication,p), epoch));
    }
    @PostMapping("/attempts/{attempt}/{action:started|heartbeat|results|stopped}")
    ResponseEntity<JsonNode> attempt(@PathVariable String p,@PathVariable String attempt,@PathVariable String action,
            @RequestBody JsonNode body,Authentication authentication,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestHeader(value="X-JDDA-Instruction-Version",required=false) Integer instructionVersion) {
        return response(runtime.attempt(p,attempt,agent(authentication,p),action,body,key,instructionVersion));
    }
    @PostMapping("/tasks/{t}/revisions/{r}/verification")
    ResponseEntity<JsonNode> verify(@PathVariable String p,@PathVariable String t,@PathVariable long r,@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestHeader(value="If-Match",required=false) String match) { return response(runtime.verify(p,t,r,body,key,match)); }
    @GetMapping("/audit-events")
    JsonNode events(@PathVariable String p,@RequestParam(defaultValue="0") long after) { return runtime.events(p,after); }
    @PostMapping("/tasks/{t}/revisions/{r}/cancel")
    ResponseEntity<JsonNode> cancel(@PathVariable String p,@PathVariable String t,@PathVariable long r,@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestHeader(value="If-Match",required=false) String match) { return response(runtime.cancel(p,t,r,body,key,match)); }
}
