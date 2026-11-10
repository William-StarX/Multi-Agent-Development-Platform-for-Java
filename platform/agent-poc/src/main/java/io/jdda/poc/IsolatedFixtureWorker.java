package io.jdda.poc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;

/** Pulls one approved fixture job from the local control service or an SSH loopback forward. */
public final class IsolatedFixtureWorker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> INPUTS = List.of("pom.xml", "src/main/java/demo/PriceCalculator.java",
            "src/test/java/demo/PriceCalculatorTest.java");
    private static final String REPORT = "TEST-demo.PriceCalculatorTest.xml";
    private static final String CHECK = """
            set -e
            test "$(id -u)" -eq 65532
            test ! -S /var/run/docker.sock
            if grep -q '^NoNewPrivs:' /proc/self/status; then
                grep -Eq '^NoNewPrivs:[[:space:]]+1$' /proc/self/status
            else
                test "$(uname -m)" = x86_64
                perl -e 'exit(syscall(157, 39, 0, 0, 0, 0) == 1 ? 0 : 1)'
            fi
            test "$(wc -l < /proc/net/route)" -eq 1
            cp -R /input/. /work
            cp -R /opt/jdda-m2 /work/repository
            chmod -R u+rwX /work/repository
            cd /work
            status=0
            timeout 120s mvn -B -ntp -o -Duser.home=/tmp -Dmaven.repo.local=/work/repository test || status=$?
            if test -f target/surefire-reports/TEST-demo.PriceCalculatorTest.xml; then
                cp target/surefire-reports/TEST-demo.PriceCalculatorTest.xml /reports/
            fi
            exit "$status"
            """;

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Expected loopback URL, project, worker, host and profile path.");
        URI url = URI.create(args[0]);
        if (!"http".equals(url.getScheme()) || !"127.0.0.1".equals(url.getHost()) || url.getPort() < 1
                || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null || !url.getPath().isEmpty())
            throw new IllegalArgumentException("Use a loopback control URL with an explicit port.");
        String project = identifier(args[1]), worker = identifier(args[2]), host = identifier(args[3]);
        Path profilePath = Path.of(args[4]).toRealPath();
        JsonNode profile = JSON.readTree(profilePath.toFile());
        if (!profile.path("image").asText().matches("sha256:[0-9a-f]{64}")
                || !profile.path("baseCommit").asText().matches("[0-9a-f]{40}") || profile.path("inputDigests").size() != INPUTS.size())
            throw new IllegalArgumentException("Profile must pin an image, commit and input digests.");
        String bearer = System.getenv("JDDA_WORKER_TOKEN");
        if (bearer == null || !bearer.matches("[A-Za-z0-9_-]{32,128}")) throw new IllegalArgumentException("Set JDDA_WORKER_TOKEN.");
        var api = new Control(url, project, bearer);
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode claim = api.post("/worker/claims", JSON.createObjectNode());
            if (claim != null) {
                run(api, claim, project, worker, host, profile, profilePath.getParent());
                return;
            }
            Thread.sleep(1000);
        }
        throw new IOException("No eligible fixture task was available within 90 seconds.");
    }

    private static void run(Control api, JsonNode claim, String project, String worker, String host,
            JsonNode profile, Path root) throws Exception {
        JsonNode task = claim.path("task"), attempt = claim.path("attempt");
        String id = identifier(attempt.path("attemptId").asText());
        if (!project.equals(task.path("projectId").asText()) || !worker.equals(attempt.path("workerId").asText())
                || !host.equals(attempt.path("hostId").asText())) throw new IOException("Unexpected claim identity.");
        String route = "/attempts/" + id;
        var token = JSON.createObjectNode().put("revision", attempt.path("revision").asLong())
                .put("leaseEpoch", attempt.path("leaseEpoch").asLong());
        Path work = Files.createDirectories(root.resolve("attempts")).resolve(id);
        Files.createDirectory(work);
        try {
            var paths = task.path("ownedPaths");
            if (!"fixture-offline-runner".equals(task.path("toolPolicyId").asText())
                    || !"fixture-six-tests".equals(task.path("acceptanceProfile").asText())
                    || !profile.path("repoId").equals(task.path("repoId")) || !profile.path("baseCommit").equals(task.path("baseCommit"))
                    || paths.size() != 1 || !paths.path(0).equals(profile.path("ownedPath"))
                    || task.at("/resources/cpu").asDouble() != 2 || task.at("/resources/memoryMiB").asLong() != 1024)
                throw new IOException("Claim does not match the approved fixture profile.");
            Path input = stageInputs(root.resolve("input"), work.resolve("input"), profile.path("inputDigests"));
            Path reports = Files.createDirectory(work.resolve("reports"));
            if (reports.getFileSystem().supportedFileAttributeViews().contains("posix"))
                Files.setPosixFilePermissions(reports, PosixFilePermissions.fromString("rwxrwxrwx"));
            api.post(route + "/started", token);
            api.post(route + "/heartbeat", token);
            Path log = work.resolve("container.log");
            int exit = testInContainer(api, route, token, id, profile.path("image").asText(), input, reports, log,
                    Math.min(150, task.path("timeoutSeconds").asLong()));
            Path xml = reports.resolve(REPORT);
            boolean passed = exit == 0 && sixPassingTests(xml);
            var manifest = JSON.createObjectNode().put("projectId", project).put("workerId", worker).put("hostId", host)
                    .put("attemptId", id).put("taskId", task.path("taskId").asText()).put("revision", token.path("revision").asLong())
                    .put("leaseEpoch", token.path("leaseEpoch").asLong()).put("commit", task.path("baseCommit").asText())
                    .put("image", profile.path("image").asText()).put("exitCode", exit).put("passed", passed);
            var evidence = manifest.putArray("evidence").add(artifact("container-log", log));
            if (Files.isRegularFile(xml)) evidence.add(artifact("maven-tests", xml));
            Path manifestPath = work.resolve("manifest.json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
            ObjectNode result = token.deepCopy().put("schemaVersion", "1").put("projectId", project)
                    .put("taskId", task.path("taskId").asText()).put("attemptId", id).put("outcome", passed ? "PASSED" : "FAILED")
                    .put("manifestUri", manifestPath.toUri().toASCIIString()).put("resultDigest", digest(manifestPath));
            if (passed) result.put("resultCommit", task.path("baseCommit").asText());
            else result.putNull("resultCommit");
            result.set("evidence", evidence);
            api.post(route + "/results", result);
            System.out.println(JSON.createObjectNode().put("manifest", manifestPath.toString())
                    .put("resultDigest", result.path("resultDigest").asText()).put("passed", passed));
        } catch (Exception failure) {
            try {
                confirmRemoved("jdda-fixture-" + id, work);
                Path stopped = Files.writeString(work.resolve("stopped.txt"), "Container absent after stopping attempt " + id + "\n");
                ObjectNode notice = token.deepCopy();
                notice.putArray("evidence").add(artifact("stop-proof", stopped));
                api.post(route + "/stopped", notice);
            } catch (Exception stopFailure) {
                failure.addSuppressed(stopFailure);
            }
            throw failure;
        }
    }

    private static Path stageInputs(Path source, Path target, JsonNode digests) throws Exception {
        Path approvedRoot = source.toRealPath();
        for (String file : INPUTS) {
            Path from = approvedRoot.resolve(file).toRealPath();
            if (!from.startsWith(approvedRoot) || !Files.isRegularFile(from) || Files.size(from) > 1024 * 1024)
                throw new IOException("Invalid fixture input " + file);
            Path to = target.resolve(file);
            Files.createDirectories(to.getParent());
            Files.copy(from, to);
            if (!digest(to).equals(digests.path(file).asText())) throw new IOException("Fixture input digest changed: " + file);
        }
        Files.writeString(target.resolve("run-fixture.sh"), CHECK);
        if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try (var paths = Files.walk(target)) {
                for (Path path : paths.toList()) Files.setPosixFilePermissions(path,
                        PosixFilePermissions.fromString(Files.isDirectory(path) ? "r-xr-xr-x" : "r--r--r--"));
            }
        }
        return target;
    }

    private static int testInContainer(Control api, String route, ObjectNode token, String id, String image, Path input,
            Path reports, Path log, long timeout) throws Exception {
        String name = "jdda-fixture-" + id;
        var arguments = List.of("run", "--rm", "--name", name, "--label", "io.jdda.fixture-test=true", "--pull=never",
                "--network", "none", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--user", "65532:65532", "--cpus", "2", "--memory", "1g", "--memory-swap", "1g", "--pids-limit", "128",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=128m,mode=1777", "--tmpfs", "/work:rw,nosuid,nodev,size=512m,mode=1777",
                "--mount", "type=bind,source=" + input + ",target=/input,readonly",
                "--mount", "type=bind,source=" + reports + ",target=/reports", "--env", "HOME=/tmp",
                "--entrypoint", "/bin/sh", image, "/input/run-fixture.sh");
        Process container = docker(arguments, log);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(timeout).toNanos();
            while (!container.waitFor(5, TimeUnit.SECONDS)) {
                if (System.nanoTime() >= deadline) throw new IOException("Fixture execution timed out.");
                api.post(route + "/heartbeat", token);
            }
            return container.exitValue();
        } finally {
            try { confirmRemoved(name, log.getParent()); }
            finally { if (container.isAlive()) container.destroyForcibly(); }
        }
    }

    private static void confirmRemoved(String name, Path work) throws Exception {
        Path output = work.resolve("docker-cleanup.log");
        dockerCommand(List.of("rm", "--force", name), output);
        if (dockerCommand(List.of("ps", "-aq", "--filter", "name=^/" + name + "$"), output) != 0
                || !Files.readString(output).isBlank()) throw new IOException("Could not confirm container removal.");
    }

    private static int dockerCommand(List<String> arguments, Path output) throws Exception {
        Process process = docker(arguments, output);
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Docker did not respond.");
        }
        return process.exitValue();
    }

    private static Process docker(List<String> arguments, Path output) throws IOException {
        var command = new ArrayList<>(List.of("docker"));
        if (System.getProperty("os.name").startsWith("Linux")) command.addAll(List.of("--host", "unix:///var/run/docker.sock"));
        command.addAll(arguments);
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        for (String secret : List.of("JDDA_WORKER_TOKEN", "JDDA_ADMIN_TOKEN", "JDDA_DB_PASSWORD", "DEEPSEEK_API_KEY",
                "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")) process.environment().remove(secret);
        return process.start();
    }

    private static boolean sixPassingTests(Path xml) throws Exception {
        if (!Files.isRegularFile(xml) || Files.size(xml) > 1024 * 1024) return false;
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var suite = factory.newDocumentBuilder().parse(xml.toFile()).getDocumentElement();
        return "testsuite".equals(suite.getTagName()) && "demo.PriceCalculatorTest".equals(suite.getAttribute("name"))
                && "6".equals(suite.getAttribute("tests")) && "0".equals(suite.getAttribute("errors"))
                && "0".equals(suite.getAttribute("failures")) && "0".equals(suite.getAttribute("skipped"));
    }

    private static ObjectNode artifact(String id, Path path) throws Exception {
        return JSON.createObjectNode().put("artifactId", id).put("uri", path.toUri().toASCIIString()).put("digest", digest(path));
    }

    private static String digest(Path path) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = stream.read(buffer)) != -1;) hash.update(buffer, 0, count);
        }
        return "sha256:" + HexFormat.of().formatHex(hash.digest());
    }

    private static String identifier(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IllegalArgumentException("Invalid identifier.");
        return value;
    }

    private static final class Control {
        private final HttpClient client = HttpClient.newBuilder().proxy(ProxySelector.of(null))
                .connectTimeout(Duration.ofSeconds(3)).build();
        private final URI url;
        private final String bearer;

        Control(URI root, String project, String bearer) {
            this.url = root.resolve("/api/v1/projects/" + project);
            this.bearer = bearer;
        }

        JsonNode post(String path, ObjectNode body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> response;
            try { response = client.send(request, HttpResponse.BodyHandlers.ofString()); }
            catch (IOException firstFailure) { response = client.send(request, HttpResponse.BodyHandlers.ofString()); }
            int status = response.statusCode();
            if (status == 204 || (status == 429 && "/worker/claims".equals(path))) return null;
            if (status != 200 && status != 202) throw new IOException("Control rejected " + path + " with HTTP " + status);
            return JSON.readTree(response.body());
        }
    }
}
