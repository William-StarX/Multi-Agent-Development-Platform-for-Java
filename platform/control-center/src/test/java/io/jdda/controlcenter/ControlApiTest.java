package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "JDDA_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${JDDA_TEST_DB_URL}",
        "jdda.governance.nacos.enabled=false",
        "JDDA_ADMIN_TOKEN=integration-test-token-with-32-characters"
})
class ControlApiTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ContractValidation contracts;
    @Autowired GovernancePolicy governance;
    @org.springframework.test.context.bean.override.mockito.MockitoBean GitHubEvidenceClient github;
    String project;
    String base;

    @BeforeEach
    void setup() {
        project = "it-" + UUID.randomUUID();
        base = "/api/v1/projects/" + project;
        assertEquals(201, call(HttpMethod.POST, "/api/v1/projects",
                json.createObjectNode().put("projectId", project), "bootstrap", null).getStatusCode().value());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM jdda.request_receipt WHERE project_id=?", project);
        jdbc.update("DELETE FROM jdda.task_record WHERE project_id=?", project);
        jdbc.update("DELETE FROM jdda.agent WHERE project_id=?", project);
        jdbc.update("DELETE FROM jdda.project WHERE project_id=?", project);
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String path, JsonNode body, String key, String etag) {
        var headers = new HttpHeaders();
        headers.setBearerAuth("integration-test-token-with-32-characters");
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) headers.set("Idempotency-Key", key);
        if (etag != null) headers.set("If-Match", etag);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    ObjectNode node(String id, String parent, String role) {
        var node = json.createObjectNode().put("schemaVersion", "1").put("projectId", project)
                .put("agentId", id).put("parentId", parent).put("hostId", (String) null)
                .put("endpoint", (String) null);
        node.putArray("roles").add(role);
        node.putArray("capabilities");
        return node;
    }

    ObjectNode task(String id) throws Exception {
        try (var stream = getClass().getResourceAsStream("/examples.json")) {
            ObjectNode task = (ObjectNode) json.readTree(stream).get("TaskPackage");
            task.put("projectId", project).put("taskId", id).put("managerId", "root")
                    .put("parentTaskId", (String) null).put("topologyVersion", 1);
            task.putArray("dependencies");
            return task;
        }
    }

    @Test
    void protectsApiAndBootstrapsDedicatedRoles() {
        assertEquals(401, http.getForEntity(base, JsonNode.class).getStatusCode().value());
        assertEquals(200, http.getForEntity("/actuator/health", JsonNode.class).getStatusCode().value());
        assertEquals(3, call(HttpMethod.GET, base, null, null, null).getBody().get("agents").size());
        var replay = call(HttpMethod.POST, "/api/v1/projects",
                json.createObjectNode().put("projectId", project), "bootstrap", null);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(3, replay.getBody().get("agents").size());
    }

    @Test void consolePairsOnceAndCannotUseAdministrativeWriteEndpoints() {
        var page = http.getForEntity("/", String.class);
        assertEquals(200, page.getStatusCode().value());
        assertTrue(page.getBody().contains("id=\"project\""));
        assertEquals(401, http.getForEntity("/_console/projects", JsonNode.class).getStatusCode().value());
        assertEquals(401, http.postForEntity("/_console/pairings", null, JsonNode.class).getStatusCode().value());
        var pairing = call(HttpMethod.POST, "/_console/pairings", null, null, null);
        assertEquals(201, pairing.getStatusCode().value());
        assertTrue(pairing.getHeaders().getCacheControl().contains("no-store"));
        var code = json.createObjectNode().set("code", pairing.getBody().get("code"));
        var session = http.postForEntity("/_console/session", code, JsonNode.class);
        assertEquals(200, session.getStatusCode().value());
        assertEquals(401, http.postForEntity("/_console/session", code, JsonNode.class).getStatusCode().value());

        String token = session.getBody().get("token").asText();
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var projects = http.exchange("/_console/projects", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertEquals(200, projects.getStatusCode().value());
        assertTrue(java.util.stream.StreamSupport.stream(projects.getBody().get("projects").spliterator(), false)
                .anyMatch(value -> project.equals(value.asText())));
        assertEquals(200, http.exchange(base + "/library/tasks", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class)
                .getStatusCode().value());
        var dispatch = http.exchange(base + "/dispatch", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertEquals(200, dispatch.getStatusCode().value());
        headers.set("If-Match", dispatch.getHeaders().getETag());
        headers.set("Idempotency-Key", "console-pause");
        var paused = http.exchange(base + "/dispatch", HttpMethod.PUT,
                new HttpEntity<>(json.createObjectNode().put("paused", true), headers), JsonNode.class);
        assertEquals(200, paused.getStatusCode().value());
        assertTrue(paused.getBody().get("paused").asBoolean());
        assertEquals(403, workerCall(token, "/_console/pairings", null, "mint").getStatusCode().value());
        assertEquals(403, workerCall(token, base + "/agents/worker/credentials", null, "credential").getStatusCode().value());
        assertEquals(403, workerCall(token, base + "/worker/claims", null, "claim").getStatusCode().value());
        assertEquals(403, workerCall(token, base + "/tasks", null, "create").getStatusCode().value());
    }

    @Test
    void rejectsBugfixChildrenAndDedicatedRoleMisuse() {
        assertEquals(422, call(HttpMethod.POST, base + "/agents",
                node("child", "bugfix", "WORKER"), "child", null).getStatusCode().value());
        assertEquals(422, call(HttpMethod.POST, base + "/agents",
                node("extra-library", "root", "LIBRARY"), "extra", null).getStatusCode().value());
        var hybrid = node("hybrid", "root", "BUGFIX");
        hybrid.withArray("roles").add("MANAGER");
        assertEquals(400, call(HttpMethod.POST, base + "/agents", hybrid, "hybrid", null).getStatusCode().value());
        var forgedProject = node("cross", "root", "WORKER").put("projectId", "other-project");
        assertEquals(422, call(HttpMethod.POST, base + "/agents", forgedProject, "cross", null).getStatusCode().value());
    }

    @Test
    void rejectsCyclesStaleVersionsAndConflictingReplay() {
        var first = node("m1", "root", "MANAGER");
        assertEquals(201, call(HttpMethod.POST, base + "/agents", first, "m1", null).getStatusCode().value());
        first.put("agentId", "different");
        assertEquals(409, call(HttpMethod.POST, base + "/agents", first, "m1", null).getStatusCode().value());
        assertEquals(201, call(HttpMethod.POST, base + "/agents",
                node("m2", "m1", "MANAGER"), "m2", null).getStatusCode().value());
        var topology = call(HttpMethod.GET, base, null, null, null).getBody();
        assertEquals(3, topology.get("topologyVersion").asLong());
        for (var agent : topology.get("agents")) {
            assertEquals(3, agent.get("topologyVersion").asLong());
        }
        var move = json.createObjectNode().put("parentId", "m2").put("topologyVersion", 3);
        assertEquals(428, call(HttpMethod.PUT, base + "/agents/m1/parent", move, "move", null).getStatusCode().value());
        assertEquals(409, call(HttpMethod.PUT, base + "/agents/m1/parent", move, "stale", "\"9\"").getStatusCode().value());
        assertEquals(422, call(HttpMethod.PUT, base + "/agents/m1/parent", move, "cycle", "\"1\"").getStatusCode().value());
        assertEquals("root", call(HttpMethod.GET, base + "/agents/m1", null, null, null)
                .getBody().get("parentId").asText());
    }

    @Test
    void storesImmutableTasksAndReturnsBoundedSummaries() throws Exception {
        var task = task("TASK-1");
        assertEquals(201, call(HttpMethod.POST, base + "/tasks", task, "task1", null).getStatusCode().value());
        assertEquals(201, call(HttpMethod.POST, base + "/tasks", task, "task1", null).getStatusCode().value());
        assertEquals(409, call(HttpMethod.POST, base + "/tasks", task, "new-key", null).getStatusCode().value());
        assertEquals(task, call(HttpMethod.GET, base + "/tasks/TASK-1/revisions/1",
                null, null, null).getBody().get("task"));
        assertEquals(201, call(HttpMethod.POST, base + "/tasks", task("TASK-2"), "task2", null).getStatusCode().value());
        var page = call(HttpMethod.GET, base + "/library/tasks?limit=1&maxBytes=2048", null, null, null).getBody();
        assertEquals(1, page.get("items").size());
        assertTrue(page.get("truncated").asBoolean());
        assertTrue(json.writeValueAsBytes(page).length <= 2048);
        assertFalse(page.get("items").get(0).has("budget"));
        var next = call(HttpMethod.GET, base + "/library/tasks?limit=1&after="
                + page.get("nextCursor").asText(), null, null, null).getBody();
        assertEquals("TASK-2", next.get("items").get(0).get("taskId").asText());
        var invalid = task("BAD");
        invalid.withArray("dependencies").addObject().put("taskId", "MISSING").put("revision", 1).put("gate", "VERIFIED");
        assertEquals(422, call(HttpMethod.POST, base + "/tasks", invalid, "invalid", null).getStatusCode().value());
    }

    @Test
    void concurrentRetriesCreateExactlyOneTask() throws Exception {
        var task = task("CONCURRENT");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> call(HttpMethod.POST, base + "/tasks", task, "same-key", null));
            var second = pool.submit(() -> call(HttpMethod.POST, base + "/tasks", task, "same-key", null));
            assertEquals(201, first.get().getStatusCode().value());
            assertEquals(201, second.get().getStatusCode().value());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM jdda.task_record WHERE project_id=?",
                Integer.class, project));
    }

    String runtimeSetup() throws Exception {
        assertEquals(201, call(HttpMethod.POST, base+"/agents",node("middle","root","MANAGER"),"middle",null).getStatusCode().value());
        assertEquals(201, call(HttpMethod.POST, base+"/agents",node("scope","middle","MANAGER"),"scope",null).getStatusCode().value());
        var worker=node("worker","scope","WORKER").put("hostId",project);
        worker.withArray("capabilities").add("java").add("maven");
        assertEquals(201, call(HttpMethod.POST, base+"/agents",worker,"worker",null).getStatusCode().value());
        String token=project+"-worker-token-for-integration";
        assertEquals(200, call(HttpMethod.POST,base+"/agents/worker/credentials",
                json.createObjectNode().put("token",token),"token",null).getStatusCode().value());
        var task=task("RUN").put("managerId","scope").put("topologyVersion",4);
        task.withArray("ownedPaths").add("src/main/java/demo/PriceCalculator.java");
        assertEquals(201,call(HttpMethod.POST,base+"/tasks",task,"run",null).getStatusCode().value());
        assertEquals(200,call(HttpMethod.POST,base+"/tasks/RUN/revisions/1/publish",json.createObjectNode(),"publish","\"1\"").getStatusCode().value());
        return token;
    }

    ResponseEntity<JsonNode> workerCall(String token,String path,JsonNode body,String key) {
        var headers=new HttpHeaders();headers.setBearerAuth(token);headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key",key);
        return http.exchange(path,HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
    }

    @Test void governanceCapSerializesAdmissionAndChangesWithoutRestart() throws Exception {
        String priceToken = enrollBranch("price");
        String stockToken = enrollBranch("stock");
        var batch = json.createObjectNode();
        batch.putArray("tasks").add(batchTask("PRICE", "price")).add(batchTask("STOCK", "stock"));
        assertEquals(201, call(HttpMethod.POST, base + "/task-batches", batch, "governed-batch", null)
                .getStatusCode().value());
        governance.apply("{\"maxActiveAttempts\":1}", "fixture");
        try {
            var tokens = java.util.List.of(priceToken, stockToken);
            var claims = new java.util.ArrayList<ResponseEntity<JsonNode>>();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> workerCall(priceToken, base + "/worker/claims", null, "cap-price"));
                var second = pool.submit(() -> workerCall(stockToken, base + "/worker/claims", null, "cap-stock"));
                claims.add(first.get());
                claims.add(second.get());
            }
            assertEquals(java.util.Set.of(200, 204),
                    java.util.Set.of(claims.get(0).getStatusCode().value(), claims.get(1).getStatusCode().value()));
            int active = claims.get(0).getStatusCode().value() == 200 ? 0 : 1;
            var firstLease = claims.get(active).getBody().get("lease");
            assertThrows(IllegalArgumentException.class, () -> governance.apply("{\"maxActiveAttempts\":-1}", "fixture"));
            assertEquals(204, workerCall(tokens.get(1 - active), base + "/worker/claims", null, "still-capped")
                    .getStatusCode().value());
            var overrides = json.createObjectNode().put("maxActiveAttempts", 1);
            overrides.putObject("projectLimits").put(project, 2);
            governance.apply(overrides.toString(), "fixture");
            assertEquals(200, workerCall(tokens.get(1 - active), base + "/worker/claims", null, "after-cap-raise")
                    .getStatusCode().value());

            governance.apply("{\"maxActiveAttempts\":1}", "fixture");
            String heartbeatPath = base + "/attempts/" + firstLease.get("attemptId").asText() + "/heartbeat";
            var heartbeat = json.createObjectNode().set("revision", firstLease.get("revision"));
            ((ObjectNode) heartbeat).set("leaseEpoch", firstLease.get("leaseEpoch"));
            assertEquals(200, workerCall(tokens.get(active), heartbeatPath, heartbeat, "continue-heartbeat")
                    .getStatusCode().value());
            var status = call(HttpMethod.GET, "/api/v1/governance", null, null, null);
            assertEquals(1, status.getBody().at("/settings/maxActiveAttempts").asInt());
            assertEquals("fixture", status.getBody().path("source").asText());
            assertEquals(401, http.getForEntity("/api/v1/governance", JsonNode.class).getStatusCode().value());
        } finally {
            governance.apply("{}", "default");
        }
    }

    @Test void concurrentTemporaryCredentialsCannotReplaceOrRevokeEachOther() throws Exception {
        runtimeSetup();
        String route = base + "/agents/worker/credentials";
        assertEquals(200, call(HttpMethod.POST, route + "/revoke", null, "revoke-initial", null).getStatusCode().value());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> issueTemporaryCredential(route, project + "-first"));
            var two = pool.submit(() -> issueTemporaryCredential(route, project + "-second"));
            var first = one.get();
            var second = two.get();
            assertEquals(java.util.Set.of(200, 409),
                    java.util.Set.of(first.getStatusCode().value(), second.getStatusCode().value()));
            var winner = first.getStatusCode().value() == 200 ? first : second;
            String tag = winner.getHeaders().getETag();
            assertNotNull(tag);
            assertEquals(409, call(HttpMethod.POST, route + "/revoke", null, "wrong-tag",
                    "\"" + "0".repeat(64) + "\"").getStatusCode().value());
            String replacement = project + "-replacement";
            assertEquals(200, call(HttpMethod.POST, route, json.createObjectNode().put("token", replacement),
                    "rotate", null).getStatusCode().value());
            assertEquals(409, call(HttpMethod.POST, route + "/revoke", null, "old-tag", tag).getStatusCode().value());
            assertEquals(200, workerCall(replacement, base + "/worker/claims", null,
                    "replacement-claim").getStatusCode().value());
        }
    }

    private ResponseEntity<JsonNode> issueTemporaryCredential(String route, String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth("integration-test-token-with-32-characters");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", token);
        headers.setIfNoneMatch("*");
        return http.exchange(route, HttpMethod.POST,
                new HttpEntity<>(json.createObjectNode().put("token", token), headers), JsonNode.class);
    }

    @Test void delegatedTaskCannotSkipLevelsOrBeClaimedByAnotherWorker() throws Exception {
        String otherToken = runtimeSetup();
        assertEquals(202, call(HttpMethod.POST, base+"/tasks/RUN/revisions/1/cancel",
                json.createObjectNode().put("reason", "Use delegated task instead"), "cancel", "\"2\"").getStatusCode().value());
        var chosen = node("chosen", "scope", "WORKER").put("hostId", project);
        chosen.withArray("capabilities").add("java").add("maven");
        assertEquals(201, call(HttpMethod.POST, base+"/agents", chosen, "chosen", null).getStatusCode().value());
        String chosenToken = project+"-chosen-worker-token";
        assertEquals(200, call(HttpMethod.POST, base+"/agents/chosen/credentials",
                json.createObjectNode().put("token", chosenToken), "chosen-token", null).getStatusCode().value());

        var task = task("DELEGATED").put("managerId", "scope").put("topologyVersion", 5);
        var steps = task.putObject("delegation").put("goal", "Fix the discount calculation").putArray("steps");
        var first = steps.addObject().put("from", "root").put("to", "scope").put("instruction", "Coordinate the repair");
        steps.addObject().put("from", "middle").put("to", "scope").put("instruction", "Own payable calculation");
        steps.addObject().put("from", "scope").put("to", "chosen").put("instruction", "Clamp the result at zero");
        assertEquals(422, call(HttpMethod.POST, base+"/tasks", task, "skip-level", null).getStatusCode().value());
        first.put("to", "middle");
        assertEquals(201, call(HttpMethod.POST, base+"/tasks", task, "delegated", null).getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST, base+"/tasks/DELEGATED/revisions/1/publish",
                json.createObjectNode(), "publish-delegated", "\"1\"").getStatusCode().value());
        assertEquals(204, workerCall(otherToken, base+"/worker/claims", null, "not-assigned").getStatusCode().value());
        assertEquals(204, workerCall(otherToken, base+"/worker/claims?taskId=DELEGATED&revision=1", null, "target-other")
                .getStatusCode().value());
        var claim = workerCall(chosenToken, base+"/worker/claims", null, "assigned");
        assertEquals(200, claim.getStatusCode().value());
        assertEquals(task.get("delegation"), claim.getBody().at("/task/delegation"));
        assertEquals("chosen", claim.getBody().at("/attempt/workerId").asText());
    }

    @Test void targetedClaimSkipsOlderWorkAndReplaysTheExactSelection() throws Exception {
        String worker = runtimeSetup();
        var task = task("NEXT").put("managerId", "scope").put("topologyVersion", 4);
        task.putArray("ownedPaths").add("src/main/java/demo/Next.java");
        assertEquals(201, call(HttpMethod.POST, base + "/tasks", task, "next", null).getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST, base + "/tasks/NEXT/revisions/1/publish", json.createObjectNode(),
                "publish-next", "\"1\"").getStatusCode().value());

        String claims = base + "/worker/claims";
        assertEquals(400, workerCall(worker, claims + "?taskId=NEXT", null, "missing-revision").getStatusCode().value());
        assertEquals(400, workerCall(worker, claims + "?taskId=NEXT&revision=0", null, "invalid-revision").getStatusCode().value());
        assertEquals(204, workerCall(worker, claims + "?taskId=NEXT&revision=2", null, "missing-target").getStatusCode().value());

        String target = claims + "?taskId=NEXT&revision=1";
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> workerCall(worker, target, null, "target"));
            var retry = pool.submit(() -> workerCall(worker, target, null, "target"));
            var result = first.get();
            assertEquals(200, result.getStatusCode().value());
            assertEquals("NEXT", result.getBody().at("/task/taskId").asText());
            assertEquals(result.getBody(), retry.get().getBody());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM jdda.attempt WHERE project_id=?",Integer.class,project));
        var mismatch = workerCall(worker, claims + "?taskId=RUN&revision=1", null, "target");
        assertEquals(409, mismatch.getStatusCode().value());
        assertEquals("IDEMPOTENCY_CONFLICT", mismatch.getBody().get("code").asText());
        assertEquals("READY", call(HttpMethod.GET, base + "/tasks/RUN/revisions/1", null, null, null)
                .getBody().at("/snapshot/state").asText());
    }

    @Test void publishesUnorderedBatchAndRunsIndependentBranchesBeforeTheJoin() throws Exception {
        String priceToken = enrollBranch("price");
        String stockToken = enrollBranch("stock");
        var price = batchTask("PRICE", "price");
        var stock = batchTask("STOCK", "stock");
        var join = batchTask("JOIN", "price");
        for (String predecessor : java.util.List.of("PRICE", "STOCK")) {
            join.withArray("dependencies").addObject().put("taskId", predecessor).put("revision", 1).put("gate", "VERIFIED");
        }
        var batch = json.createObjectNode();
        batch.putArray("tasks").add(join).add(price).add(stock);
        var published = call(HttpMethod.POST, base + "/task-batches", batch, "publish-batch", null);
        assertEquals(201, published.getStatusCode().value());
        assertEquals("PRICE", published.getBody().at("/tasks/0/taskId").asText());
        assertEquals("READY", published.getBody().at("/tasks/0/state").asText());
        assertEquals("STOCK", published.getBody().at("/tasks/1/taskId").asText());
        assertEquals("WAITING_DEPENDENCIES", published.getBody().at("/tasks/2/state").asText());
        assertEquals(published.getBody(), call(HttpMethod.POST, base + "/task-batches", batch, "publish-batch", null).getBody());
        assertEquals(409, call(HttpMethod.POST, base + "/task-batches", batch, "new-key", null).getStatusCode().value());

        JsonNode priceClaim;
        JsonNode stockClaim;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var claimPrice = pool.submit(() -> workerCall(priceToken, base + "/worker/claims", null, "claim-price"));
            var claimStock = pool.submit(() -> workerCall(stockToken, base + "/worker/claims", null, "claim-stock"));
            var prices = claimPrice.get();
            var stocks = claimStock.get();
            assertEquals(200, prices.getStatusCode().value());
            assertEquals(200, stocks.getStatusCode().value());
            priceClaim = prices.getBody();
            stockClaim = stocks.getBody();
        }
        assertEquals("PRICE", priceClaim.at("/task/taskId").asText());
        assertEquals("STOCK", stockClaim.at("/task/taskId").asText());
        assertNotEquals(priceClaim.at("/attempt/hostId"), stockClaim.at("/attempt/hostId"));
        acceptClaim(priceToken, priceClaim);
        assertEquals(204, workerCall(priceToken, base + "/worker/claims", null, "join-too-early").getStatusCode().value());
        String stockId = stockClaim.at("/attempt/attemptId").asText();
        jdbc.update("UPDATE jdda.attempt SET lease=jsonb_set(lease,'{expiresAt}','\"2000-01-01T00:00:00Z\"'::jsonb) WHERE project_id=? AND attempt_id=?",
                project, stockId);
        assertEquals(204, workerCall(priceToken, base + "/worker/claims", null, "join-expired-upstream").getStatusCode().value());
        assertEquals("BLOCKED", call(HttpMethod.GET, base + "/tasks/JOIN/revisions/1", null, null, null)
                .getBody().at("/snapshot/state").asText());
        var stopped = json.createObjectNode().put("revision", 1).put("leaseEpoch", 1);
        stopped.set("evidence", sampleResult().get("evidence"));
        assertEquals(200, workerCall(stockToken, base + "/attempts/" + stockId + "/stopped", stopped, "stop-stock")
                .getStatusCode().value());
        var stockRetry = workerCall(stockToken, base + "/worker/claims", null, "retry-stock");
        assertEquals(200, stockRetry.getStatusCode().value());
        assertEquals(2, stockRetry.getBody().at("/lease/leaseEpoch").asLong());
        acceptClaim(stockToken, stockRetry.getBody());
        var joined = workerCall(priceToken, base + "/worker/claims", null, "claim-join");
        assertEquals(200, joined.getStatusCode().value());
        assertEquals("JOIN", joined.getBody().at("/task/taskId").asText());
    }

    @Test void invalidOrCyclicBatchDoesNotPublishAnyTask() throws Exception {
        enrollBranch("price");
        enrollBranch("stock");
        var price = batchTask("PRICE", "price");
        var stock = batchTask("STOCK", "stock");
        var review = batchTask("REVIEW", "price");
        price.withArray("dependencies").addObject().put("taskId", "STOCK").put("revision", 1).put("gate", "VERIFIED");
        stock.withArray("dependencies").addObject().put("taskId", "PRICE").put("revision", 1).put("gate", "VERIFIED");
        var batch = json.createObjectNode();
        batch.putArray("tasks").add(review).add(price).add(stock);
        var cycle = call(HttpMethod.POST, base + "/task-batches", batch, "cycle", null);
        assertEquals(422, cycle.getStatusCode().value());
        assertEquals("INVALID_DEPENDENCY", cycle.getBody().get("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM jdda.task_record WHERE project_id=?", Integer.class, project));
        stock.withArray("dependencies").removeAll();
        price.withArray("dependencies").removeAll();
        stock.put("topologyVersion", 6);
        assertEquals(409, call(HttpMethod.POST, base + "/task-batches", batch, "stale-batch", null).getStatusCode().value());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM jdda.task_record WHERE project_id=?", Integer.class, project));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM jdda.event WHERE project_id=?", Integer.class, project));
    }

    @Test void claimScansPastAnEntirePageOfIneligibleTasks() throws Exception {
        String worker = enrollBranch("price");
        for (int batch = 0; batch < 3; batch++) {
            var request = json.createObjectNode();
            var tasks = request.putArray("tasks");
            for (int offset = 0; offset < 11; offset++) {
                var task = task("INELIGIBLE-" + batch + "-" + offset).put("managerId", "scope-price").put("topologyVersion", 4);
                task.putArray("requiredCapabilities").add("database");
                tasks.add(task);
            }
            assertEquals(201, call(HttpMethod.POST, base + "/task-batches", request, "ineligible-" + batch, null).getStatusCode().value());
        }
        var request = json.createObjectNode();
        request.putArray("tasks").add(task("AFTER-PAGE").put("managerId", "scope-price").put("topologyVersion", 4));
        assertEquals(201, call(HttpMethod.POST, base + "/task-batches", request, "after-page", null).getStatusCode().value());
        var claim = workerCall(worker, base + "/worker/claims", null, "claim-after-page");
        assertEquals(200, claim.getStatusCode().value());
        assertEquals("AFTER-PAGE", claim.getBody().at("/task/taskId").asText());
    }

    private String enrollBranch(String branch) {
        for (var agent : java.util.List.of(node("middle-" + branch, "root", "MANAGER"),
                node("scope-" + branch, "middle-" + branch, "MANAGER"),
                node("worker-" + branch, "scope-" + branch, "WORKER").put("hostId", project + "-" + branch))) {
            if (agent.path("roles").path(0).asText().equals("WORKER")) agent.withArray("capabilities").add("java").add("maven");
            assertEquals(201, call(HttpMethod.POST, base + "/agents", agent, agent.get("agentId").asText(), null).getStatusCode().value());
        }
        String token = project + "-token-" + branch;
        assertEquals(200, call(HttpMethod.POST, base + "/agents/worker-" + branch + "/credentials",
                json.createObjectNode().put("token", token), "token-" + branch, null).getStatusCode().value());
        return token;
    }

    private ObjectNode batchTask(String id, String branch) throws Exception {
        var task = task(id).put("managerId", "scope-" + branch).put("topologyVersion", 7);
        task.putArray("ownedPaths").add("src/main/java/demo/" + id + ".java");
        var steps = task.putObject("delegation").put("goal", "Implement " + id).putArray("steps");
        steps.addObject().put("from", "root").put("to", "middle-" + branch).put("instruction", "Coordinate " + id);
        steps.addObject().put("from", "middle-" + branch).put("to", "scope-" + branch).put("instruction", "Own " + id);
        steps.addObject().put("from", "scope-" + branch).put("to", "worker-" + branch).put("instruction", "Implement " + id);
        return task;
    }

    private void acceptClaim(String token, JsonNode claim) throws Exception {
        String id = claim.at("/attempt/attemptId").asText();
        String taskId = claim.at("/task/taskId").asText();
        String attempt = base + "/attempts/" + id;
        var lease = json.createObjectNode().put("revision", 1).put("leaseEpoch", claim.at("/lease/leaseEpoch").asInt());
        assertEquals(200, workerCall(token, attempt + "/started", lease, "start-" + taskId).getStatusCode().value());
        var result = sampleResult().put("projectId", project).put("taskId", taskId).put("attemptId", id);
        result.set("leaseEpoch", lease.get("leaseEpoch"));
        assertEquals(202, workerCall(token, attempt + "/results", result, "result-" + taskId).getStatusCode().value());
        var verification = json.createObjectNode().put("accepted", true).set("resultDigest", result.get("resultDigest"));
        String path = base + "/tasks/" + taskId + "/revisions/1";
        var current = call(HttpMethod.GET, path, null, null, null);
        var verified = call(HttpMethod.POST, path + "/verification", verification, "verify-" + taskId, current.getHeaders().getETag());
        assertEquals("SUCCEEDED", verified.getBody().get("state").asText());
    }

    private ObjectNode sampleResult() throws Exception {
        try (var stream = getClass().getResourceAsStream("/examples.json")) {
            return (ObjectNode) json.readTree(stream).get("ResultSubmission");
        }
    }

    @Test void operatorGuidanceFencesRunningWorkerResults() throws Exception {
        String worker = runtimeSetup();
        assertEquals(202, call(HttpMethod.POST, base + "/tasks/RUN/revisions/1/cancel",
                json.createObjectNode().put("reason", "Use guided task"), "cancel", "\"2\"").getStatusCode().value());
        var task = task("GUIDED").put("managerId", "scope").put("topologyVersion", 4)
                .put("operatorGuidance", true).put("toolPolicyId", "repository-files-v1");
        var steps = task.putObject("delegation").put("goal", "Implement a fixture").putArray("steps");
        steps.addObject().put("from", "root").put("to", "middle").put("instruction", "Coordinate")
                .put("decisionSummary", "Matches the Java branch");
        steps.addObject().put("from", "middle").put("to", "scope").put("instruction", "Own the change");
        steps.addObject().put("from", "scope").put("to", "worker").put("instruction", "Implement the fixture");
        var path = base + "/tasks/GUIDED/revisions/1";
        assertEquals(201, call(HttpMethod.POST, base + "/tasks", task, "guided", null).getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST, path + "/publish", json.createObjectNode(),
                "publish-guided", "\"1\"").getStatusCode().value());

        var claimed = workerCall(worker, base + "/worker/claims", null, "claim-guided").getBody();
        var attemptId = claimed.at("/attempt/attemptId").asText();
        var attemptPath = base + "/attempts/" + attemptId;
        var lease = json.createObjectNode().put("revision", 1).put("leaseEpoch", 1);
        assertEquals(200, workerCall(worker, attemptPath + "/started", lease, "start-guided").getStatusCode().value());
        var workerHeaders = new HttpHeaders();
        workerHeaders.setBearerAuth(worker);
        String checkpoint = attemptPath + "/instructions?epoch=1";
        assertEquals(0, http.exchange(checkpoint, HttpMethod.GET, new HttpEntity<>(workerHeaders), JsonNode.class)
                .getBody().get("version").asInt());
        assertEquals(409, http.exchange(attemptPath + "/instructions?epoch=2", HttpMethod.GET,
                new HttpEntity<>(workerHeaders), JsonNode.class).getStatusCode().value());

        var pairing = call(HttpMethod.POST, "/_console/pairings", null, null, null);
        var code = json.createObjectNode().set("code", pairing.getBody().get("code"));
        var console = http.postForEntity("/_console/session", code, JsonNode.class).getBody().get("token").asText();
        var consolePath = "/_console/projects/" + project + "/tasks/GUIDED/revisions/1";
        assertEquals(401, http.getForEntity(consolePath + "/trace", JsonNode.class).getStatusCode().value());
        var instruction = json.createObjectNode().put("text", "Include the empty case").put("expectedVersion", 0);
        var saved = workerCall(console, consolePath + "/guidance", instruction, "guidance-one");
        assertEquals(201, saved.getStatusCode().value());
        assertEquals(saved.getBody(), workerCall(console, consolePath + "/guidance", instruction, "guidance-one").getBody());
        assertEquals("INSTRUCTION_UPDATED", workerCall(console, consolePath + "/guidance", instruction, "outdated")
                .getBody().get("code").asText());
        assertEquals(403, workerCall(worker, consolePath + "/guidance", instruction, "forged").getStatusCode().value());
        assertEquals("Include the empty case", http.exchange(checkpoint, HttpMethod.GET,
                new HttpEntity<>(workerHeaders), JsonNode.class).getBody().at("/items/0/text").asText());

        var result = sampleResult().put("projectId", project).put("taskId", "GUIDED").put("attemptId", attemptId);
        workerHeaders.setContentType(MediaType.APPLICATION_JSON);
        workerHeaders.set("Idempotency-Key", "guided-result");
        workerHeaders.set("X-JDDA-Instruction-Version", "0");
        var stale = http.exchange(attemptPath + "/results", HttpMethod.POST,
                new HttpEntity<>(result, workerHeaders), JsonNode.class);
        assertEquals(409, stale.getStatusCode().value());
        assertEquals("INSTRUCTION_UPDATED", stale.getBody().get("code").asText());
        workerHeaders.set("X-JDDA-Instruction-Version", "1");
        assertEquals(202, http.exchange(attemptPath + "/results", HttpMethod.POST,
                new HttpEntity<>(result, workerHeaders), JsonNode.class).getStatusCode().value());

        var consoleHeaders = new HttpHeaders();
        consoleHeaders.setBearerAuth(console);
        assertEquals(403, http.exchange(checkpoint, HttpMethod.GET, new HttpEntity<>(consoleHeaders), JsonNode.class)
                .getStatusCode().value());
        var trace = http.exchange(consolePath + "/trace", HttpMethod.GET, new HttpEntity<>(consoleHeaders), JsonNode.class);
        assertEquals(200, trace.getStatusCode().value());
        assertEquals("VERIFYING", trace.getBody().at("/snapshot/state").asText());
        assertEquals("Matches the Java branch", trace.getBody().at("/delegation/steps/0/decisionSummary").asText());
        assertEquals(1, trace.getBody().at("/attempts/0/guidanceVersion").asInt());
        assertEquals("VERIFYING", trace.getBody().at("/events/0/state").asText());
        assertFalse(trace.getBody().at("/guidance/available").asBoolean());
        assertEquals("GUIDANCE_UNAVAILABLE", workerCall(console, consolePath + "/guidance",
                instruction.put("expectedVersion", 1), "late").getBody().get("code").asText());
    }

    @Test void runtimeRequiresIdentityAndVerifiesExactResult() throws Exception {
        String token=runtimeSetup();
        assertEquals(403,call(HttpMethod.POST,base+"/worker/claims",null,"admin-claim",null).getStatusCode().value());
        assertEquals(403,workerCall(token,"/api/v1/projects/other/worker/claims",null,"cross").getStatusCode().value());
        var claimed=workerCall(token,base+"/worker/claims",null,"claim");
        assertEquals(200,claimed.getStatusCode().value());
        contracts.validate("TaskAttempt",claimed.getBody().get("attempt"));
        contracts.validate("TaskLease",claimed.getBody().get("lease"));
        assertEquals(claimed.getBody(),workerCall(token,base+"/worker/claims",null,"claim").getBody());
        assertEquals(204,workerCall(token,base+"/worker/claims",null,"again").getStatusCode().value());
        String attempt=claimed.getBody().get("attempt").get("attemptId").asText();
        var lease=json.createObjectNode().put("revision",1).put("leaseEpoch",1);
        assertEquals(200,workerCall(token,base+"/attempts/"+attempt+"/started",lease,"start").getStatusCode().value());
        assertEquals(200,workerCall(token,base+"/attempts/"+attempt+"/heartbeat",lease,"beat").getStatusCode().value());
        ObjectNode result;
        try(var stream=getClass().getResourceAsStream("/examples.json")) { result=(ObjectNode)json.readTree(stream).get("ResultSubmission"); }
        result.put("projectId",project).put("taskId","RUN").put("attemptId",attempt);
        var submitted = workerCall(token,base+"/attempts/"+attempt+"/results",result,"result");
        assertEquals(202, submitted.getStatusCode().value());
        try (var pool = Executors.newFixedThreadPool(4)) {
            var replays = new java.util.ArrayList<java.util.concurrent.Future<ResponseEntity<JsonNode>>>();
            for (int i = 0; i < 10; i++) {
                replays.add(pool.submit(() -> workerCall(token, base+"/attempts/"+attempt+"/results", result, "result")));
            }
            for (var replay : replays) {
                var response = replay.get();
                assertEquals(202, response.getStatusCode().value());
                assertEquals(submitted.getBody(), response.getBody());
            }
        }
        String acceptedPath=base+"/library/tasks/RUN/revisions/1/accepted-result";
        assertEquals(409,call(HttpMethod.GET,acceptedPath,null,null,null).getStatusCode().value());
        var verification=json.createObjectNode().put("accepted",true).put("resultDigest","wrong");
        assertEquals(409,call(HttpMethod.POST,base+"/tasks/RUN/revisions/1/verification",verification,"wrong","\"5\"").getStatusCode().value());
        verification.set("resultDigest",result.get("resultDigest"));
        var done=call(HttpMethod.POST,base+"/tasks/RUN/revisions/1/verification",verification,"verify","\"5\"");
        assertEquals(200,done.getStatusCode().value());assertEquals("SUCCEEDED",done.getBody().get("state").asText());
        var accepted=call(HttpMethod.GET,acceptedPath,null,null,null).getBody();
        assertEquals(result.get("resultCommit"),accepted.get("resultCommit"));
        String candidates=base+"/library/integration-candidates";
        String candidateSha="d".repeat(40);
        var candidate=json.createObjectNode().put("repoId",accepted.get("repoId").asText())
                .put("targetRef","refs/heads/main").put("baseSha",accepted.get("baseCommit").asText()).put("candidateSha",candidateSha);
        var source=candidate.putArray("sources").addObject().put("taskId","RUN").put("revision",1)
                .put("resultCommit",accepted.get("resultCommit").asText()).put("resultDigest","sha256:"+"f".repeat(64));
        assertEquals(409,call(HttpMethod.POST,candidates,candidate,"wrong-source",null).getStatusCode().value());
        source.set("resultDigest",accepted.get("resultDigest"));
        var registered=call(HttpMethod.POST,candidates,candidate,"register-candidate",null);
        assertEquals(201,registered.getStatusCode().value());
        assertEquals(registered.getBody(),call(HttpMethod.POST,candidates,candidate,"register-candidate",null).getBody());
        var stored=call(HttpMethod.GET,candidates+"/"+candidateSha,null,null,null).getBody();
        assertEquals(candidate.get("sources"),stored.get("sources"));
        assertTrue(stored.get("ciEvidence").isNull());
        assertEquals(5,call(HttpMethod.GET,base+"/audit-events",null,null,null).getBody().size());
        verifyReleaseGate(candidate, token);
        assertEquals(200,call(HttpMethod.POST,base+"/agents/worker/credentials/revoke",null,"revoke",null).getStatusCode().value());
        assertEquals(401,workerCall(token,base+"/worker/claims",null,"revoked").getStatusCode().value());
    }

    @Test void pausingDispatchBlocksClaimsAndKeepsTheActiveAttemptAlive() throws Exception {
        String token = runtimeSetup();
        String path = base + "/dispatch";
        var initial = call(HttpMethod.GET, path, null, null, null);
        assertEquals("\"1\"", initial.getHeaders().getETag());
        assertFalse(initial.getBody().get("paused").asBoolean());
        var pause = json.createObjectNode().put("paused", true);
        var paused = call(HttpMethod.PUT, path, pause, "pause", initial.getHeaders().getETag());
        assertEquals(200, paused.getStatusCode().value());
        assertTrue(paused.getBody().get("paused").asBoolean());
        assertEquals(paused.getBody(), call(HttpMethod.PUT, path, pause, "pause", initial.getHeaders().getETag()).getBody());
        assertEquals(204, workerCall(token, base + "/worker/claims", null, "while-paused").getStatusCode().value());
        var resume = json.createObjectNode().put("paused", false);
        assertEquals(409, call(HttpMethod.PUT, path, resume, "stale-resume", initial.getHeaders().getETag()).getStatusCode().value());
        var resumed = call(HttpMethod.PUT, path, resume, "resume", paused.getHeaders().getETag());
        assertFalse(resumed.getBody().get("paused").asBoolean());
        var active = workerCall(token, base + "/worker/claims", null, "after-resume");
        assertEquals(200, active.getStatusCode().value());
        String attemptPath = base + "/attempts/" + active.getBody().at("/attempt/attemptId").asText();
        var lease = json.createObjectNode().put("revision", 1).put("leaseEpoch", 1);
        assertEquals(200, workerCall(token, attemptPath + "/started", lease, "start-before-pause").getStatusCode().value());
        assertEquals(200, call(HttpMethod.PUT, path, pause, "pause-running", resumed.getHeaders().getETag()).getStatusCode().value());
        assertEquals(200, workerCall(token, attemptPath + "/heartbeat", lease, "heartbeat-while-paused").getStatusCode().value());
    }

    @Test void rootCanHandOffVerifiedFailureToBugfixAndLibraryAcceptsOnlyTheRepair() throws Exception {
        String worker = runtimeSetup();
        var followUp = task("FOLLOW-UP").put("managerId", "scope").put("topologyVersion", 4).put("maxAttempts", 1);
        followUp.withArray("dependencies").addObject().put("taskId", "RUN").put("revision", 1).put("gate", "VERIFIED");
        assertEquals(201, call(HttpMethod.POST, base + "/tasks", followUp, "create-follow-up", null).getStatusCode().value());
        String followUpPath = base + "/tasks/FOLLOW-UP/revisions/1";
        var waiting = call(HttpMethod.POST, followUpPath + "/publish", json.createObjectNode(), "publish-follow-up", "\"1\"");
        assertEquals("WAITING_DEPENDENCIES", waiting.getBody().get("state").asText());
        String bugfix = project + "-bugfix-credential";
        String taskPath = base + "/tasks/RUN/revisions/1";
        String failurePath = base + "/library/tasks/RUN/revisions/1/failure-evidence";
        String repairQueue = base + "/bugfix/claims";
        var credential = json.createObjectNode().put("token", bugfix).put("hostId", project);
        credential.putArray("capabilities").add("java").add("maven");
        assertEquals(200, call(HttpMethod.POST, base + "/agents/bugfix/credentials", credential, "bugfix-token", null)
                .getStatusCode().value());
        assertEquals(204, workerCall(bugfix, repairQueue, null, "empty-repair").getStatusCode().value());
        assertEquals(403, workerCall(bugfix, base + "/worker/claims", null, "ordinary-queue").getStatusCode().value());
        assertEquals(403, workerCall(worker, repairQueue, null, "repair-queue").getStatusCode().value());
        assertEquals(403, workerCall(bugfix, "/api/v1/projects/other/bugfix/claims", null, "other-project")
                .getStatusCode().value());

        JsonNode original = workerCall(worker, base + "/worker/claims", null, "ordinary").getBody();
        String originalId = original.at("/attempt/attemptId").asText();
        var firstLease = json.createObjectNode().put("revision", 1).put("leaseEpoch", 1);
        assertEquals(403, workerCall(bugfix, base + "/attempts/" + originalId + "/started", firstLease, "foreign-attempt")
                .getStatusCode().value());
        assertEquals(200, workerCall(worker, base + "/attempts/" + originalId + "/started", firstLease, "start-ordinary")
                .getStatusCode().value());
        ObjectNode failed;
        try (var stream = getClass().getResourceAsStream("/examples.json")) {
            failed = (ObjectNode) json.readTree(stream).get("ResultSubmission");
        }
        failed.put("projectId", project).put("taskId", "RUN").put("attemptId", originalId).put("outcome", "FAILED")
                .putNull("resultCommit");
        assertEquals(202, workerCall(worker, base + "/attempts/" + originalId + "/results", failed, "failed")
                .getStatusCode().value());
        var failure = call(HttpMethod.GET, failurePath, null, null, null);
        assertEquals(200, failure.getStatusCode().value());
        assertEquals(failed.get("evidence"), failure.getBody().get("evidence"));
        assertEquals(originalId, failure.getBody().get("attemptId").asText());

        var handoff = json.createObjectNode().put("accepted", false).put("routeToBugfix", true)
                .put("resultDigest", "sha256:" + "f".repeat(64));
        assertEquals(403, workerCall(worker, taskPath + "/verification", handoff, "forged-handoff").getStatusCode().value());
        assertEquals(409, call(HttpMethod.POST, taskPath + "/verification", handoff, "wrong-digest", "\"5\"")
                .getStatusCode().value());
        handoff.set("resultDigest", failed.get("resultDigest"));
        assertEquals(409, call(HttpMethod.POST, taskPath + "/verification", handoff, "stale", "\"4\"")
                .getStatusCode().value());
        var queued = call(HttpMethod.POST, taskPath + "/verification", handoff, "handoff", "\"5\"");
        assertEquals(200, queued.getStatusCode().value());
        assertEquals("READY", queued.getBody().get("state").asText());
        assertEquals(queued.getBody(), call(HttpMethod.POST, taskPath + "/verification", handoff, "handoff", "\"5\"").getBody());
        assertEquals(204, workerCall(worker, base + "/worker/claims", null, "repair-blocked").getStatusCode().value());

        var claimed = workerCall(bugfix, repairQueue, null, "repair");
        assertEquals(200, claimed.getStatusCode().value());
        var repair = claimed.getBody();
        assertEquals("bugfix", repair.at("/attempt/workerId").asText());
        assertEquals(2, repair.at("/lease/leaseEpoch").asInt());
        assertEquals(original.get("task"), repair.get("task"));
        assertEquals(failure.getBody(), repair.get("repair"));
        assertEquals(204, workerCall(bugfix, repairQueue, null, "repair-busy").getStatusCode().value());
        String repairAttempt = base + "/attempts/" + repair.at("/attempt/attemptId").asText();
        assertEquals(200, workerCall(bugfix, repairAttempt + "/started", firstLease.put("leaseEpoch", 2), "start-repair")
                .getStatusCode().value());
        var fixed = failed.deepCopy().put("attemptId", repair.at("/attempt/attemptId").asText()).put("leaseEpoch", 2)
                .put("outcome", "PASSED").put("resultCommit", "e".repeat(40)).put("resultDigest", "sha256:" + "e".repeat(64));
        assertEquals(202, workerCall(bugfix, repairAttempt + "/results", fixed, "fixed").getStatusCode().value());
        String acceptedPath = base + "/library/tasks/RUN/revisions/1/accepted-result";
        assertEquals(409, call(HttpMethod.GET, acceptedPath, null, null, null).getStatusCode().value());
        var verification = json.createObjectNode().put("accepted", true).set("resultDigest", fixed.get("resultDigest"));
        var current = call(HttpMethod.GET, taskPath, null, null, null);
        var verified = call(HttpMethod.POST, taskPath + "/verification", verification, "verify-repair", current.getHeaders().getETag());
        assertEquals("SUCCEEDED", verified.getBody().get("state").asText());
        assertEquals(fixed.get("resultCommit"), call(HttpMethod.GET, acceptedPath, null, null, null).getBody().get("resultCommit"));
        assertEquals(failure.getBody(), call(HttpMethod.GET, failurePath, null, null, null).getBody());

        var followUpClaim = workerCall(worker, base + "/worker/claims", null, "claim-follow-up");
        assertEquals(200, followUpClaim.getStatusCode().value());
        assertEquals("FOLLOW-UP", followUpClaim.getBody().at("/task/taskId").asText());
        String followUpId = followUpClaim.getBody().at("/attempt/attemptId").asText();
        String followUpAttempt = base + "/attempts/" + followUpId;
        assertEquals(200, workerCall(worker, followUpAttempt + "/started", firstLease.put("leaseEpoch", 1), "start-follow-up")
                .getStatusCode().value());
        var followUpFailure = failed.deepCopy().put("taskId", "FOLLOW-UP").put("attemptId", followUpId);
        assertEquals(202, workerCall(worker, followUpAttempt + "/results", followUpFailure, "fail-follow-up")
                .getStatusCode().value());
        String followUpVersion = call(HttpMethod.GET, followUpPath, null, null, null).getHeaders().getETag();
        var exhausted = call(HttpMethod.POST, followUpPath + "/verification", handoff, "budget-exhausted", followUpVersion);
        assertEquals(409, exhausted.getStatusCode().value());
        assertEquals("Bugfix needs failed evidence and an unused attempt.", exhausted.getBody().get("message").asText());
        handoff.remove("routeToBugfix");
        var terminal = call(HttpMethod.POST, followUpPath + "/verification", handoff, "ordinary-failure", followUpVersion);
        assertEquals("FAILED", terminal.getBody().get("state").asText());
    }

    @Test
    void repositoryReleasePermitsScopedModuleFilesAndProtectsGates() {
        assertTrue(LibraryRelease.allowedModuleChange("order-service/src/main/java/example/OrderApi.java", "added"));
        assertTrue(LibraryRelease.allowedModuleChange("order-service/src/main/java/example/OrderApi.java", "removed"));
        assertFalse(LibraryRelease.allowedModuleChange("order-service/src/main/resources/db/migration/V1__orders.sql", "removed"));
        assertTrue(LibraryRelease.allowedModuleChange("src/main/resources/db/migration/V1__orders.sql", "removed"));
        assertTrue(LibraryRelease.allowedModuleChange("web-ui/app.js", "modified"));
        assertFalse(LibraryRelease.allowedModuleChange("web-ui/app.js", "removed"));
        assertFalse(LibraryRelease.allowedModuleChange("order-service/src/test/java/example/Test.java", "modified"));
        assertFalse(LibraryRelease.allowedModuleChange(".github/workflows/acceptance.yml", "modified"));
        assertFalse(LibraryRelease.allowedModuleChange("scripts/acceptance.py", "modified"));
    }

    private void verifyReleaseGate(ObjectNode candidate, String workerToken) throws Exception {
        String sha = candidate.get("candidateSha").asText();
        String baseSha = candidate.get("baseSha").asText();
        String blob = "e".repeat(40);
        String workflow = ".github/workflows/verify.yml";
        var binding = json.createObjectNode().put("slug", "Owner/Repo").put("workflowId", 42)
                .put("workflowPath", workflow).put("workflowBlobSha", blob);
        assertEquals(201, call(HttpMethod.POST, base+"/library/repositories/"+candidate.get("repoId").asText(),
                binding, "release-binding", null).getStatusCode().value());
        var run = json.createObjectNode().put("id", 77).put("head_sha", sha).put("workflow_id", 42).put("path", workflow)
                .put("event", "push").put("status", "completed").put("conclusion", "success").put("run_attempt", 1);
        run.putObject("repository").put("full_name", "Owner/Repo");
        run.putObject("head_repository").put("full_name", "Owner/Repo");
        var ref = json.createObjectNode().put("ref", "refs/heads/main");
        var object = ref.putObject("object").put("sha", baseSha);
        org.mockito.Mockito.when(github.get("Owner/Repo/actions/runs/77")).thenReturn(run);
        org.mockito.Mockito.when(github.get("Owner/Repo/contents/"+workflow+"?ref="+sha))
                .thenReturn(json.createObjectNode().put("sha", blob));
        org.mockito.Mockito.when(github.get("Owner/Repo/git/ref/heads/main")).thenReturn(ref);
        var diff = json.createObjectNode().put("status", "ahead");
        diff.putObject("merge_base_commit").put("sha", baseSha);
        var changedFile = diff.putArray("files").addObject().put("status", "modified").put("filename", "pom.xml");
        org.mockito.Mockito.when(github.get("Owner/Repo/compare/"+baseSha+"..."+sha+"?per_page=1")).thenReturn(diff);

        var dependent = task("AFTER").put("managerId", "scope").put("topologyVersion", 4);
        dependent.putArray("dependencies").addObject().put("taskId", "RUN").put("revision", 1).put("gate", "INTEGRATED");
        assertEquals(201, call(HttpMethod.POST, base+"/tasks", dependent, "dependent", null).getStatusCode().value());
        var waiting = call(HttpMethod.POST, base+"/tasks/AFTER/revisions/1/publish", json.createObjectNode(), "publish-dependent", "\"1\"");
        assertEquals("WAITING_DEPENDENCIES", waiting.getBody().get("state").asText());

        String path = base+"/library/integration-candidates/"+sha+"/release";
        assertEquals(409, call(HttpMethod.POST, path+"/approve", json.createObjectNode().put("runId", 77), "changed-build", null).getStatusCode().value());
        changedFile.put("filename", "src/main/java/demo/PriceCalculator.java");
        var approved = call(HttpMethod.POST, path+"/approve", json.createObjectNode().put("runId", 77), "approve", null);
        assertEquals(201, approved.getStatusCode().value());
        var request = json.createObjectNode().set("approvalId", approved.getBody().get("approvalId"));
        assertEquals(409, call(HttpMethod.POST, path+"/confirm", request, "early-confirm", null).getStatusCode().value());
        run.put("run_attempt", 2);
        assertEquals(409, call(HttpMethod.POST, path+"/begin", request, "rerun", null).getStatusCode().value());
        run.put("run_attempt", 1);
        object.put("sha", "f".repeat(40));
        assertEquals(409, call(HttpMethod.POST, path+"/begin", request, "moved", null).getStatusCode().value());
        object.put("sha", baseSha);
        assertEquals(200, call(HttpMethod.POST, path+"/begin", request, "begin", null).getStatusCode().value());
        assertEquals(409, call(HttpMethod.POST, path+"/confirm", request, "not-pushed", null).getStatusCode().value());
        assertEquals(204, workerCall(workerToken, base+"/worker/claims", null, "before-merge").getStatusCode().value());
        object.put("sha", sha);
        var confirmed = call(HttpMethod.POST, path+"/confirm", request, "confirm", null);
        assertEquals(200, confirmed.getStatusCode().value());
        assertEquals("INTEGRATED", confirmed.getBody().get("status").asText());
        assertEquals(confirmed.getBody(), call(HttpMethod.POST, path+"/confirm", request, "confirm", null).getBody());
        assertEquals("AFTER", workerCall(workerToken, base+"/worker/claims", null, "after-merge").getBody().at("/task/taskId").asText());
    }

    @Test void concurrentClaimsAndExpiredLeaseAreFenced() throws Exception {
        String token=runtimeSetup();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->workerCall(token,base+"/worker/claims",null,"a"));
            var b=pool.submit(()->workerCall(token,base+"/worker/claims",null,"b"));
            assertEquals(java.util.Set.of(200,204),java.util.Set.of(a.get().getStatusCode().value(),b.get().getStatusCode().value()));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM jdda.attempt WHERE project_id=?",Integer.class,project));
        String attempt=jdbc.queryForObject("SELECT attempt_id FROM jdda.attempt WHERE project_id=?",String.class,project);
        jdbc.update("UPDATE jdda.attempt SET lease=jsonb_set(lease,'{expiresAt}','\"2000-01-01T00:00:00Z\"'::jsonb) WHERE project_id=?",project);
        assertEquals(409,workerCall(token,base+"/attempts/"+attempt+"/heartbeat",json.createObjectNode().put("revision",1).put("leaseEpoch",1),"late").getStatusCode().value());
        assertEquals(204,workerCall(token,base+"/worker/claims",null,"expire").getStatusCode().value());
        assertEquals("BLOCKED",call(HttpMethod.GET,base+"/tasks/RUN/revisions/1",null,null,null).getBody().get("snapshot").get("state").asText());
        var stopped = json.createObjectNode().put("revision", 1).put("leaseEpoch", 1);
        stopped.putArray("evidence").addObject().put("artifactId", "stop-proof")
                .put("uri", "file:///fixture/stopped.log").put("digest", "sha256:"+"a".repeat(64));
        var ready = workerCall(token, base+"/attempts/"+attempt+"/stopped", stopped, "expired-stopped");
        assertEquals(200, ready.getStatusCode().value());
        assertEquals("READY", ready.getBody().get("state").asText());
        var fresh = workerCall(token, base+"/worker/claims", null, "fresh-attempt");
        assertEquals(200, fresh.getStatusCode().value());
        assertEquals(2, fresh.getBody().at("/lease/leaseEpoch").asInt());
        assertNotEquals(attempt, fresh.getBody().at("/attempt/attemptId").asText());
        assertEquals(409, workerCall(token, base+"/attempts/"+attempt+"/heartbeat",
                json.createObjectNode().put("revision", 1).put("leaseEpoch", 1), "stale-after-recovery").getStatusCode().value());
    }

    @Test void cancellationWaitsForStopEvidence() throws Exception {
        String token=runtimeSetup();
        var claim=workerCall(token,base+"/worker/claims",null,"claim").getBody();
        String attempt=claim.get("attempt").get("attemptId").asText();
        var cancel=call(HttpMethod.POST,base+"/tasks/RUN/revisions/1/cancel",
                json.createObjectNode().put("reason","operator requested stop"),"cancel","\"3\"");
        assertEquals(202,cancel.getStatusCode().value());
        assertEquals("CANCEL_REQUESTED",cancel.getBody().get("state").asText());
        var tokenBody=json.createObjectNode().put("revision",1).put("leaseEpoch",1);
        assertEquals(409,workerCall(token,base+"/attempts/"+attempt+"/started",tokenBody,"late-start").getStatusCode().value());
        tokenBody.putArray("evidence").addObject().put("artifactId","stop-log")
                .put("uri","file:///fixture/stop.log").put("digest","sha256:"+"a".repeat(64));
        var stopped=workerCall(token,base+"/attempts/"+attempt+"/stopped",tokenBody,"stopped");
        assertEquals(200,stopped.getStatusCode().value());
        assertEquals("CANCELLED",stopped.getBody().get("state").asText());
    }

    @Test void overlappingPathsCannotRunOnDifferentHosts() throws Exception {
        String firstToken=runtimeSetup();
        var worker=node("worker2","scope","WORKER").put("hostId",project+"-host2");
        worker.withArray("capabilities").add("java").add("maven");
        assertEquals(201,call(HttpMethod.POST,base+"/agents",worker,"worker2",null).getStatusCode().value());
        String token=project+"-worker2-token-for-integration";
        assertEquals(200,call(HttpMethod.POST,base+"/agents/worker2/credentials",json.createObjectNode().put("token",token),"token2",null).getStatusCode().value());
        var second=task("RUN2").put("managerId","scope").put("topologyVersion",5);
        assertEquals(201,call(HttpMethod.POST,base+"/tasks",second,"run2",null).getStatusCode().value());
        assertEquals(200,call(HttpMethod.POST,base+"/tasks/RUN2/revisions/1/publish",json.createObjectNode(),"publish2","\"1\"").getStatusCode().value());
        assertEquals(200,workerCall(firstToken,base+"/worker/claims",null,"claim1").getStatusCode().value());
        assertEquals(204,workerCall(token,base+"/worker/claims",null,"claim2").getStatusCode().value());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM jdda.attempt WHERE project_id=?",Integer.class,project));
    }

    @Test void libraryStoresVerifiedCiWithoutMarkingCodeIntegrated() {
        String sha="a".repeat(40),blob="b".repeat(40);
        var binding=json.createObjectNode().put("slug","Owner/Repo").put("workflowId",42)
                .put("workflowPath",".github/workflows/verify.yml").put("workflowBlobSha",blob);
        assertEquals(201,call(HttpMethod.POST,base+"/library/repositories/repo",binding,"repo",null).getStatusCode().value());
        assertEquals(409,call(HttpMethod.POST,base+"/library/repositories/repo",binding,"repo-again",null).getStatusCode().value());
        var run=json.createObjectNode().put("id",123).put("head_sha",sha).put("workflow_id",42)
                .put("path",".github/workflows/verify.yml").put("event","push").put("status","completed")
                .put("conclusion","success").put("run_attempt",1);
        run.putObject("repository").put("full_name","Owner/Repo");
        run.putObject("head_repository").put("full_name","Owner/Repo");
        org.mockito.Mockito.when(github.get("Owner/Repo/actions/runs/123")).thenReturn(run);
        org.mockito.Mockito.when(github.get("Owner/Repo/contents/.github/workflows/verify.yml?ref="+sha))
                .thenReturn(json.createObjectNode().put("sha",blob));
        String path=base+"/library/repositories/repo/candidates/"+sha;
        var verified=call(HttpMethod.POST,path+"/verify-ci",json.createObjectNode().put("runId",123),"ci",null);
        assertEquals(200,verified.getStatusCode().value());
        assertEquals("CI_PASSED",verified.getBody().get("status").asText());
        assertEquals(verified.getBody(),call(HttpMethod.GET,path+"/ci",null,null,null).getBody());
        run.put("head_sha","c".repeat(40));
        assertEquals(409,call(HttpMethod.POST,path+"/verify-ci",json.createObjectNode().put("runId",123),"changed",null).getStatusCode().value());
        assertEquals(verified.getBody(),call(HttpMethod.GET,path+"/ci",null,null,null).getBody());
    }
}
