package com.hwyhaul.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hwyhaul.mcp.tools.GetOrdersTool;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class McpServer {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

        GetOrdersTool tool = new GetOrdersTool();

        while (true) {
            String line = reader.readLine();
            if (line == null) break;
            line = stripLeadingBom(line);
            if (line.isBlank()) continue;

            JsonNode request = mapper.readTree(line);
            String id = request.get("id").asText();
            String method = request.get("method").asText();

            if ("tools.call".equals(method)) {
                String response;
                try {
                    String resultJson = tool.execute();
                    response = """
                        {
                          "jsonrpc": "2.0",
                          "id": "%s",
                          "result": {
                            "content": %s
                          }
                        }
                        """.formatted(id, resultJson);
                } catch (Exception e) {
                    response = mapper.writeValueAsString(mapper.createObjectNode()
                            .put("jsonrpc", "2.0")
                            .put("id", id)
                            .set("error", mapper.createObjectNode()
                                    .put("code", -32000)
                                    .put("message", e.getMessage())));
                }

                writer.write(response);
                writer.write("\n");
                writer.flush();
            }
        }
    }

    private static String stripLeadingBom(String line) {
        return !line.isEmpty() && line.charAt(0) == '\uFEFF' ? line.substring(1) : line;
    }
}
