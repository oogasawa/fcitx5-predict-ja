package com.scivicslab.predict.ja;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Generates continuation candidates for committed text using vLLM.
 * Maintains a rolling buffer of recent conversation history (from Gateway)
 * to provide LLM with full context for better predictions.
 */
public class ContinuationService {

    private static final Logger LOG = Logger.getLogger(ContinuationService.class.getName());
    private static final int MAX_CONVERSATION_LENGTH = 2000;

    private final String vllmUrl;
    private final String model;
    private final HttpClient httpClient;

    // Rolling buffer of recent conversation from Gateway
    private final StringBuilder conversationHistory = new StringBuilder();

    public ContinuationService(String vllmUrl, String model) {
        this.vllmUrl = vllmUrl;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * Append conversation text from Gateway polling.
     * Called by LlmConsoleSource when new messages arrive.
     */
    public synchronized void appendConversation(String role, String text) {
        if (text == null || text.isBlank()) return;
        conversationHistory.append(role).append(": ").append(text).append("\n");
        // Trim to keep last MAX_CONVERSATION_LENGTH chars
        if (conversationHistory.length() > MAX_CONVERSATION_LENGTH) {
            conversationHistory.delete(0,
                    conversationHistory.length() - MAX_CONVERSATION_LENGTH);
        }
    }

    /**
     * Generate continuation candidates for the given context.
     *
     * @param currentInput text the user is currently typing/has committed
     * @param n number of candidates to generate
     * @return list of continuation strings
     */
    public List<String> generate(String currentInput, int n) {
        if (currentInput == null || currentInput.isBlank()) return List.of();

        String conversation;
        synchronized (this) {
            conversation = conversationHistory.toString().trim();
        }

        String prompt;
        if (conversation.isEmpty()) {
            prompt = String.format("""
                    以下の文の続きを%d通り書いてください。

                    %s

                    ルール:
                    - 上の文に直接つながる続きだけを書く
                    - 上の文を繰り返さない
                    - 各候補は異なる意図・方向性にする（例: 肯定/否定/質問/条件付き/話題転換）
                    - 1行に1つずつ
                    - 番号や説明は不要
                    """, n, currentInput);
        } else {
            prompt = String.format("""
                    以下はこれまでの会話です:
                    ---
                    %s
                    ---

                    ユーザーは今、以下の文を入力中です:
                    %s

                    この文の続きを%d通り書いてください。
                    ルール:
                    - 上の文に直接つながる続きだけを書く
                    - 上の文を繰り返さない
                    - 各候補は異なる意図・方向性にする（例: 肯定/否定/質問/条件付き/話題転換）
                    - 1行に1つずつ
                    - 番号や説明は不要
                    """, conversation, currentInput, n);
        }

        try {
            String response = callVllm(prompt);
            List<String> results = parseResponse(response);
            // LLM may return more than requested; truncate to n
            return results.size() > n ? results.subList(0, n) : results;
        } catch (Exception e) {
            LOG.warning("Continuation generation failed: " + e.getMessage());
            return List.of();
        }
    }

    private String callVllm(String prompt) throws Exception {
        // Use proper JSON construction to avoid escaping issues
        // Build JSON manually with careful escaping
        String escapedModel = escapeJsonValue(model);
        String escapedPrompt = escapeJsonValue(prompt);
        String body = "{\"model\":\"" + escapedModel
                + "\",\"chat_template_kwargs\":{\"enable_thinking\":false}"
                + ",\"messages\":[{\"role\":\"user\",\"content\":\"" + escapedPrompt
                + "\"}],\"max_tokens\":256,\"temperature\":0.7}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(vllmUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOG.warning("vLLM response: " + response.body().substring(0,
                    Math.min(response.body().length(), 300)));
            throw new RuntimeException("vLLM returned " + response.statusCode());
        }

        return extractContent(response.body());
    }

    private List<String> parseResponse(String response) {
        List<String> results = new ArrayList<>();
        for (String line : response.split("\\n+")) {
            String trimmed = line.trim()
                    .replaceFirst("^[\\d]+[.)\\s]+", "")
                    .replaceFirst("^[-・]\\s*", "")
                    .trim();
            if (!trimmed.isEmpty() && trimmed.length() <= 100) {
                results.add(trimmed);
            }
        }
        return results;
    }

    private String extractContent(String json) {
        int contentIdx = json.indexOf("\"content\"");
        if (contentIdx < 0) return "";
        int colonIdx = json.indexOf(":", contentIdx);
        int startQuote = json.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return "";
        int endQuote = startQuote + 1;
        while (endQuote < json.length()) {
            char c = json.charAt(endQuote);
            if (c == '\\') { endQuote += 2; continue; }
            if (c == '"') break;
            endQuote++;
        }
        return json.substring(startQuote + 1, endQuote)
                .replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * Escape a string value for JSON (without surrounding quotes).
     */
    private static String escapeJsonValue(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
