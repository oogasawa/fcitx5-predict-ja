package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * HTTP handler for POST /api/record.
 * Receives committed text from the fcitx5 plugin.
 *
 * Expected JSON:
 * { "input": "こんかいのばぐは", "output": "今回のバグは", "context": "..." }
 */
public class RecordHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(RecordHandler.class.getName());

    private final ActorRef<InputMonitor> monitor;

    public RecordHandler(ActorRef<InputMonitor> monitor) {
        this.monitor = monitor;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try (InputStream is = exchange.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            // Simple JSON parsing (no dependency on external JSON lib)
            String input = extractJsonField(body, "input");
            String output = extractJsonField(body, "output");
            String context = extractJsonField(body, "context");

            if (input == null || output == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing input or output\"}");
                return;
            }

            monitor.tell(m -> m.onCommit(input, output, context != null ? context : ""));

            sendResponse(exchange, 200, "{\"status\":\"ok\"}");

        } catch (Exception e) {
            LOG.warning("Record handler error: " + e.getMessage());
            sendResponse(exchange, 500, "{\"error\":\"Internal error\"}");
        }
    }

    private void sendResponse(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    /**
     * Extract a string field from a simple JSON object.
     */
    static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(":", keyIdx + key.length());
        if (colonIdx < 0) return null;

        // Find the opening quote of the value
        int startQuote = json.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return null;

        // Find the closing quote (handle escaped quotes)
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(c);
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
}
