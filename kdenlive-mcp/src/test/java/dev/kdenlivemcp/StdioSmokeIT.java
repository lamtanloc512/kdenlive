package dev.kdenlivemcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Starts the packaged server (native binary if built, else the JVM jar), speaks MCP over stdio and checks
 * that every line on stdout is JSON-RPC: nothing else may leak there.
 */
class StdioSmokeIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void stdoutCarriesOnlyJsonRpc() throws Exception {
        Path home = Files.createDirectories(Path.of("target/it-home").toAbsolutePath());
        List<String> cmd = new ArrayList<>();
        String nativePath = System.getProperty("native.image.path");
        if (nativePath != null && Files.isExecutable(Path.of(nativePath))) {
            cmd.add(nativePath);
        } else {
            cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            cmd.add("-Duser.home=" + home);
            cmd.add("-jar");
            cmd.add(Path.of("target/quarkus-app/quarkus-run.jar").toAbsolutePath().toString());
        }
        if (cmd.size() == 1) {
            cmd.add("-Duser.home=" + home);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(home.resolve("stderr.log").toFile());
        Process p = pb.start();
        BlockingQueue<String> lines = new ArrayBlockingQueue<>(1000);
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Exception ignored) {
                // process ended
            }
        });
        try (OutputStream in = p.getOutputStream()) {
            send(in, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
                    "capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}""");
            JsonNode init = await(lines, 1);
            assertThat(init.path("result").path("serverInfo").path("name").asText()).isEqualTo("kdenlive-mcp");

            send(in, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(in, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            JsonNode list = await(lines, 2);
            List<String> names = new ArrayList<>();
            list.path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
            assertThat(names).contains("server_check");

            send(in, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"server_check\",\"arguments\":{}}}");
            JsonNode call = await(lines, 3);
            assertThat(call.path("result").path("content").get(0).path("text").asText()).contains("melt:", "ffprobe:");
        } finally {
            p.destroy();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            reader.join(5000);
        }
        // anything left over must also be JSON
        for (String l : lines) {
            parseJsonRpc(l);
        }
    }

    private static void send(OutputStream in, String json) throws Exception {
        in.write((json.replace("\n", "") + "\n").getBytes(StandardCharsets.UTF_8));
        in.flush();
    }

    private static JsonNode await(BlockingQueue<String> lines, int id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            String line = lines.poll(1, TimeUnit.SECONDS);
            if (line == null) {
                continue;
            }
            JsonNode node = parseJsonRpc(line);
            if (node.path("id").asInt(-1) == id) {
                return node;
            }
        }
        return fail("no response for id " + id + " within 60s (see target/it-home/stderr.log)");
    }

    private static JsonNode parseJsonRpc(String line) {
        try {
            JsonNode node = JSON.readTree(line);
            assertThat(node.path("jsonrpc").asText()).as("stdout line: %s", line).isEqualTo("2.0");
            return node;
        } catch (Exception e) {
            return fail("non-JSON on stdout: " + line);
        }
    }
}
