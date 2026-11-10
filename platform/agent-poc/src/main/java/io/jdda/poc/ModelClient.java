package io.jdda.poc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResponseErrorHandler;

final class ModelClient {
    private static final String DEEPSEEK = "https://api.deepseek.com";
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]");
    private static final ResponseErrorHandler HTTP_ERRORS = new ResponseErrorHandler() {
        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return !response.getStatusCode().is2xxSuccessful();
        }

        @Override
        public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
            throw new IllegalStateException("Model endpoint returned HTTP " + response.getStatusCode().value() + ".");
        }
    };
    private final Map<String, String> settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    ModelClient(Map<String, String> settings) {
        this.settings = Map.copyOf(settings);
    }

    ChatModel connect(AgentRoleProfile role, boolean jsonOutput) {
        return connect(role, jsonOutput, 512, Duration.ofSeconds(45));
    }

    ChatModel connect(AgentRoleProfile role, boolean jsonOutput, int maxTokens, Duration timeout) {
        String prefix = "JDDA_" + role.name() + "_MODEL";
        URI shared = endpoint(settings.getOrDefault("JDDA_MODEL_BASE_URL", DEEPSEEK), "JDDA_MODEL_BASE_URL");
        URI base = endpoint(settings.getOrDefault(prefix + "_BASE_URL", shared.toString()), prefix + "_BASE_URL");
        String host = base.getHost().toLowerCase(Locale.ROOT);
        boolean deepSeek = host.equals("api.deepseek.com") && (base.getPort() == -1 || base.getPort() == 443);
        boolean sharedEndpoint = base.equals(shared);
        if (!present(prefix) && (!sharedEndpoint || (!deepSeek && !present("JDDA_MODEL"))))
            throw new IllegalStateException("Set " + prefix + " or JDDA_MODEL for the configured model address.");
        String key = settings.get(prefix + "_API_KEY");
        if ((key == null || key.isBlank()) && sharedEndpoint) key = settings.get("JDDA_MODEL_API_KEY");
        if ((key == null || key.isBlank()) && deepSeek) key = settings.get("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank())
            throw new IllegalStateException("Configure a model credential for " + role.name() + ".");

        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(timeout);
        var api = OpenAiApi.builder().baseUrl(base.toString())
                .completionsPath("/chat/completions").apiKey(key)
                .responseErrorHandler(HTTP_ERRORS)
                .restClientBuilder(RestClient.builder().requestFactory(requests)).build();
        var options = OpenAiChatOptions.builder().model(role.modelName(settings)).internalToolExecutionEnabled(false);
        if (host.equals("api.openai.com")) options.maxCompletionTokens(maxTokens);
        else options.maxTokens(maxTokens);
        if (deepSeek) options.temperature(0.0).extraBody(Map.of("thinking", Map.of("type", "disabled")));
        if (jsonOutput) options.responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, (String) null));
        return OpenAiChatModel.builder().openAiApi(api)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .defaultOptions(options.build()).build();
    }

    private boolean present(String key) {
        return !settings.getOrDefault(key, "").isBlank();
    }

    private static URI endpoint(String value, String setting) {
        URI uri;
        try {
            uri = URI.create(value.strip().replaceFirst("/+$", ""));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid model address in " + setting + ".");
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() > 65535 || !uri.normalize().equals(uri) || uri.getPath().endsWith("/chat/completions")
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                    && LOOPBACK.contains(uri.getHost().toLowerCase(Locale.ROOT)))))
            throw new IllegalArgumentException("Invalid model base address in " + setting + ". Use HTTPS or loopback HTTP.");
        return uri;
    }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Expected an Agent role.");
        var role = AgentRoleProfile.valueOf(args[0]);
        var client = new ModelClient(System.getenv());
        String reply = client.connect(role, true, 96, Duration.ofSeconds(30))
                .call("Return only a JSON object with the property status set to JDDA_OK.");
        if (reply == null || reply.isBlank()) throw new IllegalStateException("Model returned an empty response.");
        try {
            if (!"JDDA_OK".equals(DelegationPlanner.JSON.readTree(reply).path("status").asText()))
                throw new IllegalStateException("Model did not return the expected JSON status.");
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Model did not return valid JSON.");
        }
        System.out.println("JDDA_OK " + role.name());
    }
}
