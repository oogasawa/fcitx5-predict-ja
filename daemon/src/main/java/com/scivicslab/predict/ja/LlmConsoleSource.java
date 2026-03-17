package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Periodically polls the MCP Gateway's GET /api/history endpoint
 * and feeds new conversation text into the predict pipeline.
 *
 * The gateway aggregates /api/history from all registered LLM Console
 * instances (claude, codex, vllm). Each entry is tagged with a server name.
 * This class tracks per-server message counts to detect new messages.
 */
public class LlmConsoleSource {

    private static final Logger LOG = Logger.getLogger(LlmConsoleSource.class.getName());

    private final ActorRef<Analyzer> analyzer;
    private final String historyUrl;
    private final HttpClient httpClient;

    /**
     * Per-server count of messages seen on the last successful poll.
     * Used to detect new messages (history is append-only between clears).
     */
    private final Map<String, Integer> lastSeenCounts = new HashMap<>();

    public LlmConsoleSource(ActorRef<Analyzer> analyzer, String gatewayUrl) {
        this.analyzer = analyzer;
        this.historyUrl = gatewayUrl + "/api/history?limit=200";
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Poll the MCP Gateway history and feed new messages into the predict pipeline.
     * Called periodically by the scheduler.
     */
    public void poll() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(historyUrl))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warning("Gateway poll failed: HTTP " + response.statusCode());
                return;
            }

            List<GatewayHistoryEntry> entries = parseGatewayResponse(response.body());

            // Group by server
            Map<String, List<GatewayHistoryEntry>> byServer = new HashMap<>();
            for (GatewayHistoryEntry entry : entries) {
                byServer.computeIfAbsent(entry.server(), k -> new ArrayList<>()).add(entry);
            }

            int totalNew = 0;
            for (Map.Entry<String, List<GatewayHistoryEntry>> e : byServer.entrySet()) {
                String server = e.getKey();
                List<GatewayHistoryEntry> serverEntries = e.getValue();
                int currentCount = serverEntries.size();
                int lastSeen = lastSeenCounts.getOrDefault(server, 0);

                if (currentCount < lastSeen) {
                    // History was cleared, reset
                    lastSeen = 0;
                }

                if (currentCount > lastSeen) {
                    for (int i = lastSeen; i < currentCount; i++) {
                        String text = serverEntries.get(i).content();
                        if (text != null && !text.isBlank()) {
                            analyzer.tell(a -> a.analyzeRawText(text));
                            totalNew++;
                        }
                    }
                }

                lastSeenCounts.put(server, currentCount);
            }

            if (totalNew > 0) {
                LOG.info("Harvested " + totalNew + " new messages from "
                        + byServer.size() + " console(s) via gateway");
            }

        } catch (Exception e) {
            LOG.warning("Gateway poll error: " + e.getMessage());
        }
    }

    /**
     * Parse the gateway /api/history JSON response:
     * [{"server":"...","role":"user","content":"..."},...]
     */
    static List<GatewayHistoryEntry> parseGatewayResponse(String json) {
        List<GatewayHistoryEntry> result = new ArrayList<>();
        if (json == null || json.isBlank()) return result;

        int idx = 0;
        while (idx < json.length()) {
            int objStart = json.indexOf('{', idx);
            if (objStart < 0) break;

            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;

            String obj = json.substring(objStart, objEnd + 1);
            String server = extractField(obj, "server");
            String role = extractField(obj, "role");
            String content = extractField(obj, "content");

            if (server != null && role != null && content != null) {
                result.add(new GatewayHistoryEntry(server, role, content));
            }

            idx = objEnd + 1;
        }

        return result;
    }

    /**
     * Extract a string field from a simple JSON object.
     * Handles escaped characters in the value.
     */
    static String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(":", keyIdx + key.length());
        if (colonIdx < 0) return null;

        int startQuote = json.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return null;

        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    record GatewayHistoryEntry(String server, String role, String content) {}
}
