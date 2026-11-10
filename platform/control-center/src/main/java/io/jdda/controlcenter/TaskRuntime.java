package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class TaskRuntime {
    private static final int CLAIM_PAGE_SIZE = 32;
    private static final Pattern PATH_WILDCARD = Pattern.compile("[\\*?\\[]");
    private static final String CLAIMABLE = """
            (snapshot->>'state' IN ('READY','WAITING_DEPENDENCIES')
                OR (snapshot->>'state'='BLOCKED' AND snapshot->>'currentAttemptId' IS NULL))
            AND (repair_source_attempt_id IS NOT NULL)=?
            AND (? OR (package->>'managerId'=? AND coalesce(package#>>'{delegation,steps,2,to}',?)=?))
            """;
    private record QueuedTask(long index, JsonNode task, ObjectNode snapshot) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ControlStore store;
    private final ContractValidation contracts;
    private final TaskGuidance guidance;
    private final GovernancePolicy governance;
    TaskRuntime(JdbcTemplate jdbc, ObjectMapper json, ControlStore store, ContractValidation contracts, TaskGuidance guidance,
            GovernancePolicy governance) {
        this.jdbc=jdbc; this.json=json; this.store=store; this.contracts=contracts; this.guidance=guidance;
        this.governance=governance;
    }
    private ApiFailure conflict(String message) { return new ApiFailure(409,"INVALID_TRANSITION",message); }
    private JsonNode find(String sql, Object... args) {
        var rows=jdbc.query(sql,(rs,n)->store.parse(rs.getString(1)),args);
        if(rows.isEmpty()) throw new ApiFailure(404,"NOT_FOUND","Record not found.");
        return rows.getFirst();
    }
    private ObjectNode snapshot(String p,String t,long r) {
        return (ObjectNode) store.task(p,t,r).get("snapshot");
    }
    private ControlStore.Reply change(String p,ObjectNode state,String next) {
        state.put("state",next).put("rowVersion",state.get("rowVersion").asLong()+1)
                .put("updatedAt",Instant.now().toString());
        jdbc.update("UPDATE jdda.task_record SET snapshot=?::jsonb WHERE project_id=? AND task_id=? AND revision=?",
                state.toString(),p,state.get("taskId").asText(),state.get("revision").asLong());
        jdbc.update("INSERT INTO jdda.event(project_id,task_id,revision,body) VALUES(?,?,?,?::jsonb)",
                p,state.get("taskId").asText(),state.get("revision").asLong(),state.toString());
        return new ControlStore.Reply(200,state,ControlStore.etag(state));
    }
    private void version(JsonNode state,String match) {
        if(match==null) throw new ApiFailure(428,"PRECONDITION_REQUIRED","If-Match is required.");
        if(!ControlStore.etag(state).equals(match)) throw new ApiFailure(409,"VERSION_CONFLICT","Stale task version.");
    }
    ControlStore.Reply dispatch(String p) {
        ContractValidation.identifier(p);
        JsonNode state=find("""
                SELECT jsonb_build_object('paused',dispatch_paused,'rowVersion',dispatch_version)
                FROM jdda.project WHERE project_id=?
                """,p);
        return new ControlStore.Reply(200,state,ControlStore.etag(state));
    }
    ControlStore.Reply setDispatch(String p,JsonNode request,String key,String match) {
        if(!request.isObject() || request.size()!=1 || !request.path("paused").isBoolean())
            throw new ApiFailure(400,"INVALID_PAYLOAD","Expected paused boolean.");
        ObjectNode fingerprint=request.deepCopy();
        fingerprint.put("ifMatch",match);
        return store.mutate(p,"set-dispatch",key,fingerprint,()->{
            var current=dispatch(p);
            version(current.body(),match);
            boolean paused=request.get("paused").asBoolean();
            if(current.body().get("paused").asBoolean()==paused) return current;
            jdbc.update("UPDATE jdda.project SET dispatch_paused=?,dispatch_version=dispatch_version+1 WHERE project_id=?",paused,p);
            return dispatch(p);
        });
    }
    ControlStore.Reply credential(String p,String worker,JsonNode request,String key,String createOnly) {
        if (createOnly != null && !"*".equals(createOnly))
            throw new ApiFailure(400,"INVALID_PAYLOAD","If-None-Match must be * when creating a Worker credential.");
        String token=request.path("token").asText();
        if(!request.isObject() || !request.path("token").isTextual() || !token.matches("[A-Za-z0-9_-]{32,128}"))
            throw new ApiFailure(400,"INVALID_PAYLOAD","Expected a random token of 32 to 128 characters.");
        ObjectNode profile = null;
        if(request.size()!=1) {
            JsonNode host=request.path("hostId"), capabilities=request.path("capabilities");
            if(request.size()!=3 || !host.isTextual() || !capabilities.isArray() || capabilities.size()>1000)
                throw new ApiFailure(400,"INVALID_PAYLOAD","Expected Bugfix hostId and capabilities.");
            ContractValidation.identifier(host.asText());
            var unique=new HashSet<String>();
            for(JsonNode capability:capabilities) {
                ContractValidation.identifier(capability.textValue());
                if(!unique.add(capability.asText())) throw new ApiFailure(400,"INVALID_PAYLOAD","Duplicate capability.");
            }
            profile=json.createObjectNode().put("hostId",host.asText());
            profile.set("capabilities",capabilities);
        }
        String hash=AgentIdentity.hash(token);
        ObjectNode fingerprint=json.createObjectNode().put("tokenHash",hash);
        if(profile!=null) fingerprint.set("executionProfile",profile);
        if(createOnly!=null) fingerprint.put("createOnly",true);
        String storedProfile=profile==null?null:profile.toString();
        return store.mutate(p,"credential:"+worker,key,fingerprint,()->{
            JsonNode node=store.agent(p,worker);
            if(!(storedProfile==null?ControlStore.role(node,"WORKER"):ControlStore.role(node,"BUGFIX")))
                throw new ApiFailure(422,"INVALID_WORKER","Worker credentials require only a token. Bugfix also requires a host and capabilities.");
            if (createOnly != null && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM jdda.agent_credential WHERE project_id=? AND agent_id=?)
                    """, Boolean.class, p, worker)))
                throw new ApiFailure(409,"CREDENTIAL_EXISTS","This agent already has a credential.");
            if (createOnly == null) jdbc.update("DELETE FROM jdda.agent_credential WHERE project_id=? AND agent_id=?",p,worker);
            int inserted = jdbc.update("""
                    INSERT INTO jdda.agent_credential(token_hash,project_id,agent_id,execution_profile)
                    VALUES(?,?,?,?::jsonb) ON CONFLICT (token_hash) DO NOTHING
                    """, hash,p,worker,storedProfile);
            if (inserted == 0)
                throw conflict("Token already belongs to another identity.");
            return new ControlStore.Reply(200,json.createObjectNode().put("agentId",worker).put("issued",true),
                    createOnly == null ? null : "\"" + hash + "\"");
        });
    }
    ControlStore.Reply revoke(String p,String worker,String key,String expected) {
        ObjectNode fingerprint = json.createObjectNode();
        if (expected != null) {
            if (!expected.matches("\"[a-f0-9]{64}\""))
                throw new ApiFailure(400,"INVALID_PAYLOAD","If-Match must contain the credential tag.");
            fingerprint.put("ifMatch",expected);
        }
        return store.mutate(p,"revoke:"+worker,key,fingerprint,()->{
            if (expected == null) jdbc.update("DELETE FROM jdda.agent_credential WHERE project_id=? AND agent_id=?",p,worker);
            else if (jdbc.update("DELETE FROM jdda.agent_credential WHERE project_id=? AND agent_id=? AND token_hash=?",
                    p,worker,expected.substring(1,expected.length()-1)) == 0)
                throw new ApiFailure(409,"CREDENTIAL_CHANGED","The credential was changed or already revoked.");
            return new ControlStore.Reply(200,json.createObjectNode().put("revoked",true),null);
        });
    }
    private String dependencyState(String p,JsonNode task) {
        boolean wait=false;
        for(JsonNode dep:task.get("dependencies")) {
            JsonNode state=snapshot(p,dep.get("taskId").asText(),dep.get("revision").asLong());
            if(Set.of("FAILED","CANCELLED","SUPERSEDED","BLOCKED").contains(state.get("state").asText())) return "BLOCKED";
            if (!"SUCCEEDED".equals(state.get("state").asText())
                    || ("INTEGRATED".equals(dep.get("gate").asText()) && !integrated(p, dep))) wait = true;
        }
        return wait?"WAITING_DEPENDENCIES":"READY";
    }

    private boolean integrated(String project, JsonNode dependency) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM jdda.integration_release
                    WHERE project_id=? AND body->>'status'='INTEGRATED'
                    AND body->'sources' @> jsonb_build_array(jsonb_build_object('taskId',?::text,'revision',?::bigint)))
                """, Boolean.class, project, dependency.get("taskId").asText(), dependency.get("revision").asLong()));
    }
    ControlStore.Reply publish(String p,String t,long r,String key,String match) {
        return store.mutate(p,"publish:"+t+":"+r,key,json.createObjectNode().put("ifMatch",match),()->{
            ObjectNode state=snapshot(p,t,r);version(state,match);
            return publishDraft(p,store.task(p,t,r).get("task"),state);
        });
    }

    private ControlStore.Reply publishDraft(String p,JsonNode task,ObjectNode state) {
        if(!state.get("state").asText().equals("DRAFT")) throw conflict("Task is not DRAFT.");
        if(task.get("topologyVersion").asLong()!=store.topologyVersion(p)) throw conflict("Task topology is stale.");
        store.validateDelegation(p, task);
        JsonNode manager=store.agent(p,task.get("managerId").asText());
        if(!ControlStore.role(manager,"MANAGER"))
            throw new ApiFailure(422,"INVALID_TOPOLOGY","Expected scope manager.");
        JsonNode middle=store.agent(p,manager.path("parentId").asText());
        if(!ControlStore.role(middle,"MANAGER") || !ControlStore.role(store.agent(p,middle.path("parentId").asText()),"ROOT"))
            throw new ApiFailure(422,"INVALID_TOPOLOGY","Executable tasks require a scope manager below a middle manager.");
        String next=dependencyState(p,task);
        return change(p,state,next.equals("BLOCKED")?"WAITING_DEPENDENCIES":next);
    }

    ControlStore.Reply publishBatch(String project,JsonNode request,String key) {
        JsonNode tasks=request.path("tasks");
        if(!request.isObject() || request.size()!=1 || !tasks.isArray() || tasks.isEmpty() || tasks.size()>16)
            throw new ApiFailure(400,"INVALID_PAYLOAD","Expected 1 to 16 task packages.");
        var pending=new LinkedHashMap<String,JsonNode>();
        long planVersion=tasks.get(0).path("planVersion").asLong();
        for(JsonNode task:tasks) {
            store.validateTask(project,task);
            if(task.get("planVersion").asLong()!=planVersion || pending.putIfAbsent(task.get("taskId").asText(),task)!=null)
                throw new ApiFailure(422,"INVALID_BATCH","Tasks must share a plan version and have distinct task IDs.");
        }
        return store.mutate(project,"publish-task-batch",key,request,()->{
            ObjectNode body=json.createObjectNode();
            var published=body.putArray("tasks");
            while(!pending.isEmpty()) {
                int before=pending.size();
                var iterator=pending.values().iterator();
                while(iterator.hasNext()) {
                    JsonNode task=iterator.next();
                    if(hasPendingPredecessor(task,pending)) continue;
                    ObjectNode draft=store.insertDraft(project,task);
                    published.add(publishDraft(project,task,draft).body());
                    iterator.remove();
                }
                if(pending.size()==before) throw new ApiFailure(422,"INVALID_DEPENDENCY","Task batch contains a dependency or parent cycle.");
            }
            return new ControlStore.Reply(201,body,null);
        });
    }

    private boolean hasPendingPredecessor(JsonNode task,Map<String,JsonNode> pending) {
        if(!task.get("parentTaskId").isNull() && pending.containsKey(task.get("parentTaskId").asText())) return true;
        for(JsonNode dependency:task.get("dependencies")) {
            JsonNode predecessor=pending.get(dependency.get("taskId").asText());
            if(predecessor!=null && predecessor.get("revision").asLong()==dependency.get("revision").asLong()) return true;
        }
        return false;
    }
    private void expire(String p) {
        var expired=jdbc.query("""
                SELECT body,lease FROM jdda.attempt WHERE project_id=? AND lease->>'status'='ACTIVE'
                AND (lease->>'expiresAt')::timestamptz <= ?::timestamptz
                """,(rs,n)->List.of(store.parse(rs.getString(1)),store.parse(rs.getString(2))),p,Instant.now().toString());
        for(var row:expired) {
            ObjectNode attempt=(ObjectNode)row.get(0), lease=(ObjectNode)row.get(1);
            lease.put("status","EXPIRED");
            attempt.put("state","EXPIRED");
            saveAttempt(p,attempt,lease);
            ObjectNode state=snapshot(p,attempt.get("taskId").asText(),attempt.get("revision").asLong());
            if(Set.of("LEASED","RUNNING").contains(state.get("state").asText())) change(p,state,"BLOCKED");
        }
    }
    private void saveAttempt(String p,JsonNode attempt,JsonNode lease) {
        jdbc.update("UPDATE jdda.attempt SET body=?::jsonb,lease=?::jsonb WHERE project_id=? AND attempt_id=?",
                attempt.toString(),lease.toString(),p,attempt.get("attemptId").asText());
    }
    private List<String> activePrefixes(String p, String repo) {
        var prefixes = new ArrayList<String>();
        jdbc.query("""
                SELECT t.package->'ownedPaths' FROM jdda.task_record t JOIN jdda.attempt a
                USING(project_id,task_id,revision)
                WHERE a.project_id=? AND t.package->>'repoId'=?
                AND a.body->>'state' IN ('ALLOCATED','RUNNING','EXPIRED')
                """, rs -> { store.parse(rs.getString(1)).forEach(path -> prefixes.add(pathPrefix(path))); }, p, repo);
        return prefixes;
    }
    private static String pathPrefix(JsonNode path) {
        String value = path.asText();
        var wildcard = PATH_WILDCARD.matcher(value);
        return wildcard.find() ? value.substring(0, wildcard.start()) : value;
    }
    private boolean pathsBusy(JsonNode task,List<String> active) {
        for(JsonNode path:task.get("ownedPaths")) {
            String prefix=pathPrefix(path);
            for(String other:active)
                if(prefix.startsWith(other) || other.startsWith(prefix)) return true;
        }
        return false;
    }
    private boolean capable(JsonNode profile, JsonNode task) {
        var capabilities=new HashSet<String>();
        profile.get("capabilities").forEach(c->capabilities.add(c.asText()));
        for(JsonNode required:task.get("requiredCapabilities")) if(!capabilities.contains(required.asText())) return false;
        return true;
    }
    ControlStore.Reply claim(String p,String worker,String key,boolean repair,String taskId,Long revision) {
        if ((taskId == null) != (revision == null) || (revision != null && revision < 1))
            throw new ApiFailure(400,"INVALID_PAYLOAD","Expected taskId and a positive revision together.");
        var fingerprint = json.createObjectNode();
        if (taskId != null) {
            ContractValidation.identifier(taskId);
            fingerprint.put("taskId",taskId).put("revision",revision.toString());
        }
        return store.mutate(p,"claim:"+worker,key,fingerprint,()->{
            expire(p);
            if(dispatch(p).body().get("paused").asBoolean()) return new ControlStore.Reply(204,json.nullNode(),null);
            int maxActive = governance.maxActiveAttempts(p);
            if (maxActive > 0 && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM jdda.attempt WHERE project_id=?
                      AND body->>'state' IN ('ALLOCATED','RUNNING','EXPIRED') LIMIT 1 OFFSET ?)
                    """, Boolean.class, p, maxActive - 1)))
                return new ControlStore.Reply(204,json.nullNode(),null);
            JsonNode node=store.agent(p,worker);
            if(!ControlStore.role(node,repair?"BUGFIX":"WORKER") || !node.get("status").asText().equals("ACTIVE"))
                throw new ApiFailure(422,"INVALID_WORKER","Active agent of the requested role is required.");
            JsonNode profile=repair?find("SELECT execution_profile FROM jdda.agent_credential WHERE project_id=? AND agent_id=? AND execution_profile IS NOT NULL",p,worker):node;
            if(!profile.path("hostId").isTextual()) throw new ApiFailure(422,"INVALID_WORKER","Agent must have a host.");
            String host=profile.get("hostId").asText();
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},"jdda-host:"+host);
            // A blocked/expired attempt retains its host slot until explicit stop evidence is supplied.
            boolean busy=Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM jdda.attempt
                    WHERE (body->>'hostId'=? OR (project_id=? AND worker_id=?))
                    AND body->>'state' IN ('ALLOCATED','RUNNING','EXPIRED'))
                    """,Boolean.class,host,p,worker));
            if(busy) return new ControlStore.Reply(204,json.nullNode(),null);
            var occupiedPaths = new HashMap<String,List<String>>();
            String manager = node.get("parentId").asText();
            if (taskId != null) {
                var exact = jdbc.query("""
                        SELECT index_id,package,snapshot FROM jdda.task_record
                        WHERE project_id=? AND task_id=? AND revision=? AND
                        """ + CLAIMABLE, this::queuedTask, p, taskId, revision, repair, repair, manager, worker, worker);
                if (!exact.isEmpty()) {
                    var allocated = tryAllocate(p,worker,host,repair,profile,exact.getFirst(),occupiedPaths);
                    if (allocated != null) return allocated;
                }
                return new ControlStore.Reply(204,json.nullNode(),null);
            }
            long cursor=0;
            while(true) {
                var page=jdbc.query("""
                        SELECT index_id,package,snapshot FROM jdda.task_record WHERE project_id=? AND index_id>? AND
                        """ + CLAIMABLE + "ORDER BY index_id LIMIT ?", this::queuedTask,
                        p,cursor,repair,repair,manager,worker,worker,CLAIM_PAGE_SIZE);
                for(QueuedTask queued:page) {
                    var allocated = tryAllocate(p,worker,host,repair,profile,queued,occupiedPaths);
                    if (allocated != null) return allocated;
                }
                if(page.size()<CLAIM_PAGE_SIZE) return new ControlStore.Reply(204,json.nullNode(),null);
                cursor=page.getLast().index();
            }
        });
    }

    private QueuedTask queuedTask(java.sql.ResultSet row, int rowNumber) throws java.sql.SQLException {
        return new QueuedTask(row.getLong(1),store.parse(row.getString(2)),(ObjectNode)store.parse(row.getString(3)));
    }

    private ControlStore.Reply tryAllocate(String p,String worker,String host,boolean repair,JsonNode profile,
            QueuedTask queued,Map<String,List<String>> occupiedPaths) {
        JsonNode task=queued.task();
        if(!capable(profile,task)
                || pathsBusy(task,occupiedPaths.computeIfAbsent(task.get("repoId").asText(),repo->activePrefixes(p,repo)))) return null;
        ObjectNode state=queued.snapshot();
        String readiness=dependencyState(p,task);
        if(readiness.equals("READY")) return allocate(p,worker,host,repair,task,state);
        String current=state.get("state").asText();
        if(!current.equals("BLOCKED") && !current.equals(readiness)) change(p,state,readiness);
        return null;
    }

    private ControlStore.Reply allocate(String p,String worker,String host,boolean repair,JsonNode task,ObjectNode state) {
        if(!state.get("state").asText().equals("READY")) change(p,state,"READY");
        long epoch=state.get("leaseEpoch").asLong()+1;
        String taskId=task.get("taskId").asText();
        long revision=task.get("revision").asLong();
        String id="attempt-"+UUID.randomUUID(), now=Instant.now().toString();
        ObjectNode attempt=json.createObjectNode().put("schemaVersion","1").put("projectId",p)
                .put("taskId",taskId).put("revision",revision).put("attemptId",id)
                .put("attemptNumber",epoch).put("leaseEpoch",epoch).put("workerId",worker)
                .put("hostId",host).put("slotId","slot-1").put("state","ALLOCATED")
                .put("createdAt",now).putNull("finishedAt");
        ObjectNode lease=attempt.deepCopy();
        lease.remove(List.of("attemptNumber","hostId","slotId","state","createdAt","finishedAt"));
        lease.put("issuedAt",now).put("lastHeartbeatAt",now)
                .put("expiresAt",Instant.now().plusSeconds(Math.min(60,task.get("timeoutSeconds").asLong())).toString()).put("status","ACTIVE");
        jdbc.update("INSERT INTO jdda.attempt(project_id,attempt_id,task_id,revision,worker_id,body,lease) VALUES(?,?,?,?,?,?::jsonb,?::jsonb)",
                p,id,taskId,revision,worker,attempt.toString(),lease.toString());
        state.put("leaseEpoch",epoch).put("currentAttemptId",id);change(p,state,"LEASED");
        ObjectNode response=json.createObjectNode();response.set("task",task);response.set("attempt",attempt);response.set("lease",lease);
        if(repair) response.set("repair",failureEvidence(p,taskId,revision));
        return new ControlStore.Reply(200,response,null);
    }
    JsonNode failureEvidence(String p,String t,long r) {
        return find("""
                SELECT jsonb_build_object('attemptId',a.attempt_id,'resultDigest',a.result->'resultDigest',
                    'manifestUri',a.result->'manifestUri','evidence',a.result->'evidence')
                FROM jdda.task_record t JOIN jdda.attempt a USING (project_id,task_id,revision)
                WHERE t.project_id=? AND t.task_id=? AND t.revision=? AND a.result->>'outcome'='FAILED'
                    AND a.attempt_id=coalesce(t.repair_source_attempt_id,t.snapshot->>'currentAttemptId')
                """,p,t,r);
    }
    ControlStore.Reply attempt(String p,String id,String worker,String action,JsonNode body,String key,Integer instructionVersion) {
        if(action.equals("results")) contracts.validate("ResultSubmission",body);
        else if(action.equals("stopped")) {
            if(!body.isObject() || body.size()!=3 || !body.path("revision").isIntegralNumber()
                    || !body.path("leaseEpoch").isIntegralNumber() || !body.path("evidence").isArray()
                    || body.get("evidence").isEmpty() || body.get("evidence").size()>1000)
                throw new ApiFailure(400,"INVALID_PAYLOAD","Expected attempt token and stop evidence.");
            body.get("evidence").forEach(e->contracts.validate("Artifact",e));
        }
        else if(!body.isObject() || body.size()!=2 || !body.path("revision").isIntegralNumber() || !body.path("leaseEpoch").isIntegralNumber())
            throw new ApiFailure(400,"INVALID_PAYLOAD","Expected revision and leaseEpoch.");
        JsonNode fingerprint = action.equals("results") && instructionVersion != null
                ? json.createObjectNode().put("instructionVersion", instructionVersion).set("submission", body) : body;
        return store.mutate(p,"attempt:"+id+":"+action+":"+worker,key,fingerprint,()->{
            var record = find("""
                    SELECT jsonb_build_object('attempt',body,'lease',lease) FROM jdda.attempt
                    WHERE project_id=? AND attempt_id=?
                    """,p,id);
            ObjectNode attempt=(ObjectNode)record.get("attempt");
            ObjectNode lease=(ObjectNode)record.get("lease");
            if(!attempt.get("workerId").asText().equals(worker)) throw new ApiFailure(403,"FORBIDDEN","Attempt belongs to another Worker.");
            if(!body.get("revision").equals(attempt.get("revision")) || !body.get("leaseEpoch").equals(attempt.get("leaseEpoch")))
                throw new ApiFailure(409,"STALE_LEASE","Attempt token is stale.");
            String taskId = attempt.get("taskId").asText();
            long revision = attempt.get("revision").asLong();
            var view = store.task(p,taskId,revision);
            var task = view.get("task");
            ObjectNode state=(ObjectNode)view.get("snapshot");
            if(action.equals("stopped")) {
                if(!state.get("currentAttemptId").asText().equals(id)
                        || !Set.of("LEASED","RUNNING","BLOCKED","CANCEL_REQUESTED").contains(state.get("state").asText()))
                    throw conflict("Attempt cannot acknowledge stop in this state.");
                attempt.put("state","CANCELLED").put("finishedAt",Instant.now().toString());
                lease.put("status","RELEASED");saveAttempt(p,attempt,lease);
                jdbc.update("INSERT INTO jdda.event(project_id,task_id,revision,body) VALUES(?,?,?,?::jsonb)",
                        p,attempt.get("taskId").asText(),attempt.get("revision").asLong(),body.toString());
                String previousState = state.get("state").asText();
                if ("CANCEL_REQUESTED".equals(previousState)) return change(p, state, "CANCELLED");
                boolean expired = !Instant.parse(lease.get("expiresAt").asText()).isAfter(Instant.now());
                if (expired) {
                    if (attempt.get("attemptNumber").asLong() < task.get("maxAttempts").asLong()) {
                        if (!"BLOCKED".equals(previousState)) change(p, state, "BLOCKED");
                        state.putNull("currentAttemptId");
                        return change(p, state, "READY");
                    }
                }
                return change(p, state, "FAILED");
            }
            if(!lease.get("status").asText().equals("ACTIVE") || !Instant.parse(lease.get("expiresAt").asText()).isAfter(Instant.now())
                    || !state.get("currentAttemptId").asText().equals(id)) throw new ApiFailure(409,"STALE_LEASE","Lease expired or revoked.");
            if(state.get("state").asText().equals("CANCEL_REQUESTED")) throw conflict("Stop requested.");
            if(action.equals("started")) {
                if(!state.get("state").asText().equals("LEASED")) throw conflict("Expected LEASED.");
                attempt.put("state","RUNNING");saveAttempt(p,attempt,lease);return change(p,state,"RUNNING");
            }
            if(action.equals("heartbeat")) {
                if(!Set.of("LEASED","RUNNING").contains(state.get("state").asText())) throw conflict("Attempt is not running.");
                long timeout=task.get("timeoutSeconds").asLong();
                Instant deadline=Instant.parse(attempt.get("createdAt").asText()).plusSeconds(timeout);
                Instant expires=Instant.now().plusSeconds(60);if(expires.isAfter(deadline)) expires=deadline;
                lease.put("lastHeartbeatAt",Instant.now().toString()).put("expiresAt",expires.toString());saveAttempt(p,attempt,lease);
                return new ControlStore.Reply(200,lease,null);
            }
            if(!state.get("state").asText().equals("RUNNING")) throw conflict("Expected RUNNING.");
            for(String field:List.of("projectId","taskId","revision","attemptId","leaseEpoch"))
                if(!body.get(field).equals(attempt.get(field))) throw new ApiFailure(422,"RESULT_MISMATCH","Result identity mismatch.");
            if (TaskGuidance.enabled(task)) {
                guidance.requireCurrent(p, taskId, revision, instructionVersion);
                attempt.put("guidanceVersion", instructionVersion);
            }
            jdbc.update("UPDATE jdda.attempt SET result=?::jsonb WHERE project_id=? AND attempt_id=?",body.toString(),p,id);
            attempt.put("state","RESULT_SUBMITTED");lease.put("status","RELEASED");saveAttempt(p,attempt,lease);
            change(p,state,"VERIFYING");
            return new ControlStore.Reply(202,json.createObjectNode().put("submissionId",id).put("acceptedAt",Instant.now().toString())
                    .put("status","VERIFYING").put("statusUrl","/api/v1/projects/"+p+"/tasks/"+attempt.get("taskId").asText()+"/revisions/"+attempt.get("revision").asLong()),null);
        });
    }
    ControlStore.Reply verify(String p,String t,long r,JsonNode request,String key,String match) {
        boolean route=request.path("routeToBugfix").asBoolean(false);
        if(!request.isObject() || request.size()!=(request.has("routeToBugfix")?3:2)
                || !request.path("accepted").isBoolean() || !request.path("resultDigest").isTextual()
                || (request.has("routeToBugfix") && !request.get("routeToBugfix").isBoolean())
                || (route && request.get("accepted").asBoolean()))
            throw new ApiFailure(400,"INVALID_PAYLOAD","Expected accepted, resultDigest and optional routeToBugfix for a rejected result.");
        ObjectNode fingerprint=request.deepCopy();fingerprint.put("ifMatch",match);
        return store.mutate(p,"verify:"+t+":"+r,key,fingerprint,()->{
            ObjectNode state=snapshot(p,t,r);version(state,match);
            if(!state.get("state").asText().equals("VERIFYING")) throw conflict("Expected VERIFYING.");
            String id=state.get("currentAttemptId").asText();
            JsonNode result=find("SELECT result FROM jdda.attempt WHERE project_id=? AND attempt_id=?",p,id);
            if(!request.get("resultDigest").equals(result.get("resultDigest"))) throw conflict("Evidence digest changed.");
            boolean passed=request.get("accepted").asBoolean() && result.get("outcome").asText().equals("PASSED");
            if(route) {
                JsonNode task=store.task(p,t,r).get("task");
                if(!"FAILED".equals(result.get("outcome").asText()) || state.get("leaseEpoch").asLong()>=task.get("maxAttempts").asLong())
                    throw conflict("Bugfix needs failed evidence and an unused attempt.");
                var profiles=jdbc.query("""
                        SELECT c.execution_profile FROM jdda.agent_credential c JOIN jdda.agent a USING(project_id,agent_id)
                        WHERE c.project_id=? AND a.body->>'status'='ACTIVE' AND a.body->'roles' ?? 'BUGFIX'
                            AND c.execution_profile IS NOT NULL
                        """,(rs,n)->store.parse(rs.getString(1)),p);
                if(profiles.isEmpty() || !capable(profiles.getFirst(),task)) throw conflict("No capable Bugfix runtime is enrolled.");
                int updated=jdbc.update("""
                        UPDATE jdda.task_record SET repair_source_attempt_id=?
                        WHERE project_id=? AND task_id=? AND revision=? AND repair_source_attempt_id IS NULL
                        """,id,p,t,r);
                if(updated==0) throw conflict("Bugfix handoff already exists for this revision.");
            }
            jdbc.update("UPDATE jdda.attempt SET body=body || jsonb_build_object('state',?::text,'finishedAt',?::text) WHERE project_id=? AND attempt_id=?",
                    passed?"ACCEPTED":"REJECTED",Instant.now().toString(),p,id);
            if(route) {
                change(p,state,"RETRY_WAIT");
                state.putNull("currentAttemptId");
                return change(p,state,"READY");
            }
            return change(p,state,passed?"SUCCEEDED":"FAILED");
        });
    }
    JsonNode events(String p,long after) {
        store.topologyVersion(p);if(after<0) throw new ApiFailure(400,"INVALID_PAYLOAD","Invalid cursor.");
        var rows=jdbc.query("SELECT event_id,body FROM jdda.event WHERE project_id=? AND event_id>? ORDER BY event_id LIMIT 100",
                (rs,n)->json.createObjectNode().put("eventId",rs.getLong(1)).set("snapshot",store.parse(rs.getString(2))),p,after);
        return json.valueToTree(rows);
    }

    ControlStore.Reply cancel(String p,String t,long r,JsonNode request,String key,String match) {
        if(!request.isObject() || request.size()!=1 || !request.path("reason").isTextual()
                || request.get("reason").asText().isBlank() || request.get("reason").asText().length()>4096)
            throw new ApiFailure(400,"INVALID_PAYLOAD","Expected cancellation reason.");
        ObjectNode fingerprint=request.deepCopy();fingerprint.put("ifMatch",match);
        return store.mutate(p,"cancel:"+t+":"+r,key,fingerprint,()->{
            ObjectNode state=snapshot(p,t,r);version(state,match);
            if(Set.of("SUCCEEDED","FAILED","CANCELLED","SUPERSEDED","CANCEL_REQUESTED").contains(state.get("state").asText()))
                throw conflict("Task cannot be cancelled in this state.");
            change(p,state,"CANCEL_REQUESTED");
            if(state.get("currentAttemptId").isNull()) change(p,state,"CANCELLED");
            else jdbc.update("UPDATE jdda.attempt SET lease=jsonb_set(lease,'{status}','\"REVOKED\"'::jsonb) WHERE project_id=? AND attempt_id=?",
                    p,state.get("currentAttemptId").asText());
            return new ControlStore.Reply(202,state,ControlStore.etag(state));
        });
    }
}
