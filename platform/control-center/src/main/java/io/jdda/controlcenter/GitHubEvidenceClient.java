package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class GitHubEvidenceClient {
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    GitHubEvidenceClient(ObjectMapper json) { this.json=json; }
    JsonNode get(String path) {
        try {
            var request=HttpRequest.newBuilder(URI.create("https://api.github.com/repos/"+path))
                    .timeout(Duration.ofSeconds(20)).header("Accept","application/vnd.github+json")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent","JDDA-Control-Center").GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new ApiFailure(503,"CI_UNAVAILABLE","GitHub returned HTTP "+response.statusCode()+".");
            return json.readTree(response.body());
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new ApiFailure(503,"CI_UNAVAILABLE","GitHub request interrupted.");
        } catch(java.io.IOException e) { throw new ApiFailure(503,"CI_UNAVAILABLE","Could not read GitHub evidence."); }
    }
}
