package io.jdda.poc;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelClientTest {
    @Test void roleUsesConfiguredAddressAndItsOwnKey() throws Exception {
        var authorization = new AtomicReference<String>();
        var request = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                request.set(DelegationPlanner.JSON.readTree(exchange.getRequestBody()));
                byte[] reply = """
                        {"id":"fixture","model":"worker-fixture","object":"chat.completion",
                         "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"JDDA_OK"}}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply);
            }
        });
        server.start();
        try {
            var client = new ModelClient(Map.of(
                    "JDDA_MODEL", "default-fixture",
                    "JDDA_MODEL_API_KEY", "unrelated-fixture-key",
                    "JDDA_WORKER_MODEL_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                    "JDDA_WORKER_MODEL", "worker-fixture",
                    "JDDA_WORKER_MODEL_API_KEY", "worker-fixture-key"));
            assertEquals("JDDA_OK", client.connect(AgentRoleProfile.WORKER, true).call("Return a JSON response."));
            assertEquals("Bearer worker-fixture-key", authorization.get());
            assertEquals("worker-fixture", request.get().path("model").asText());
            assertEquals("json_object", request.get().at("/response_format/type").asText());
            assertFalse(request.get().has("thinking"));
        } finally { server.stop(0); }
    }

    @Test void modelCredentialIsNeverInheritedAcrossAddresses() {
        assertThrows(IllegalStateException.class, () -> new ModelClient(Map.of(
                "JDDA_MODEL_API_KEY", "shared-fixture",
                "DEEPSEEK_API_KEY", "legacy-fixture",
                "JDDA_WORKER_MODEL", "worker-fixture",
                "JDDA_WORKER_MODEL_BASE_URL", "https://example.invalid/v1"))
                .connect(AgentRoleProfile.WORKER, true));
        assertThrows(IllegalStateException.class, () -> new ModelClient(Map.of(
                "DEEPSEEK_API_KEY", "legacy-fixture",
                "JDDA_MODEL", "default-fixture",
                "JDDA_MODEL_BASE_URL", "https://example.invalid/v1"))
                .connect(AgentRoleProfile.ROOT, true));
        assertThrows(IllegalArgumentException.class, () -> new ModelClient(Map.of(
                "JDDA_MODEL_BASE_URL", "http://example.invalid/v1")).connect(AgentRoleProfile.ROOT, true));
    }
}
