package com.hwyhaul.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@Service
public class McpClientService {

    private final McpProcessConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Duration MCP_RESPONSE_TIMEOUT = Duration.ofSeconds(120);

    public McpClientService(McpProcessConfig config) {
        this.config = config;
    }

    public CapturedOrdersPayload callGetOrdersTool() throws Exception {
        ProcessBuilder pb = config.buildProcessBuilder();
        Process process = pb.start();

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String id = UUID.randomUUID().toString();

            ObjectNode requestNode = mapper.createObjectNode();
            requestNode.put("jsonrpc", "2.0");
            requestNode.put("id", id);
            requestNode.put("method", "tools.call");

            ObjectNode paramsNode = requestNode.putObject("params");
            paramsNode.put("name", "get_orders_create_load_payload");
            paramsNode.putObject("arguments");

            String request = mapper.writeValueAsString(requestNode);

            writer.write(request);
            writer.write("\n");
            writer.flush();

            long deadline = System.nanoTime() + MCP_RESPONSE_TIMEOUT.toNanos();
            String line;
            StringBuilder sb = new StringBuilder();
            JsonNode root = null;
            while (System.nanoTime() < deadline) {
                if (!reader.ready()) {
                    if (!process.isAlive()) {
                        break;
                    }
                    Thread.sleep(100);
                    continue;
                }

                line = reader.readLine();
                if (line == null) {
                    break;
                }

                sb.append(line);
                try {
                    root = mapper.readTree(sb.toString());
                    if (root.has("result") || root.has("error")) break;
                } catch (IOException ignored) {
                    // The MCP server writes a multi-line JSON response; keep reading until it is complete.
                }
            }

            if (root == null && process.isAlive() && System.nanoTime() >= deadline) {
                process.destroyForcibly();
                throw new IllegalStateException("MCP server did not respond within " + MCP_RESPONSE_TIMEOUT.toSeconds() + " seconds.");
            }

            if (sb.isEmpty()) {
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                int exitCode = process.waitFor();
                throw new IllegalStateException(
                        "MCP server exited without a JSON response. Exit code: " + exitCode + ". Error: " + error);
            }

            if (root == null) {
                throw new IllegalStateException("MCP server returned an incomplete JSON response: " + sb);
            }

            if (root.has("error")) {
                throw new IllegalStateException("MCP server returned an error: "
                        + root.path("error").path("message").asText());
            }

            JsonNode content = root.path("result").path("content");
            CapturedOrdersPayload payload = readCapturedOrdersPayload(content);
            if (payload == null) {
                throw new IllegalStateException("MCP server returned an unexpected payload shape: " + content);
            }
            return payload;
        } finally {
            process.destroy();
        }
    }

    private CapturedOrdersPayload readCapturedOrdersPayload(JsonNode content) throws IOException {
        if (content == null || content.isNull()) {
            return null;
        }

        if (content.isObject()) {
            return mapper.treeToValue(content, CapturedOrdersPayload.class);
        }

        if (content.isArray()) {
            for (JsonNode item : content) {
                CapturedOrdersPayload payload = readCapturedOrdersPayload(item);
                if (payload != null && payload.loads != null) {
                    return payload;
                }

                JsonNode textNode = item == null ? null : item.get("text");
                if (textNode != null && textNode.isTextual() && !textNode.asText().isBlank()) {
                    String text = textNode.asText();
                    if (text.trim().startsWith("{") || text.trim().startsWith("[")) {
                        CapturedOrdersPayload textPayload = mapper.readValue(text, CapturedOrdersPayload.class);
                        if (textPayload != null && textPayload.loads != null) {
                            return textPayload;
                        }
                    }
                }
            }
        }

        return null;
    }
}
