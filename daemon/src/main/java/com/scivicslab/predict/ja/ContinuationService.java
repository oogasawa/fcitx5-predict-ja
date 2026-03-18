package com.scivicslab.predict.ja;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Generates continuation candidates for committed text using vLLM.
 * Maintains a rolling buffer of recent conversation history (from Gateway)
 * to provide LLM with full context for better predictions.
 */
public class ContinuationService {

    private static final Logger LOG = Logger.getLogger(ContinuationService.class.getName());
    private static final int MAX_CONVERSATION_MESSAGES = 20;

    private final String vllmUrl;
    private final String model;
    private final HttpClient httpClient;

    // Rolling buffer of recent conversation messages (role + text pairs)
    private final LinkedList<String[]> conversationMessages = new LinkedList<>();

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
     * Role should be "user" or "assistant".
     */
    public synchronized void appendConversation(String role, String text) {
        if (text == null || text.isBlank()) return;
        // Normalize role to OpenAI API roles
        String apiRole = "assistant".equalsIgnoreCase(role) ? "assistant" : "user";
        conversationMessages.add(new String[]{apiRole, text});
        while (conversationMessages.size() > MAX_CONVERSATION_MESSAGES) {
            conversationMessages.removeFirst();
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

        List<String[]> history;
        synchronized (this) {
            history = new ArrayList<>(conversationMessages);
        }

        LOG.info("generate: conversation messages=" + history.size()
                + ", input length=" + currentInput.length());

        // Build system message with conversation history embedded as context.
        // This avoids the user/assistant role alternation which causes LLM
        // to generate "replies" instead of "continuations".
        StringBuilder systemMsg = new StringBuilder();
        systemMsg.append(String.format("""
                You are a Japanese text prediction engine for an IME (input method).
                Your task is to predict what a person would type NEXT as a \
                continuation of their own sentence.
                You are NOT answering, NOT replying, NOT responding.
                You are completing what the person is in the middle of writing.

                Rules:
                - Output exactly %d continuation phrases
                - Each continuation on its own line, no numbering or bullet points
                - Never repeat what was already typed
                - Keep each continuation under 100 characters
                - Never output meta-commentary or category labels
                - Do NOT answer or respond to the text

                Diversity rule: each candidate must add a DIFFERENT kind of \
                supplementary information. Vary what aspect of the situation \
                the person elaborates on. Never mention what kind of information \
                it is — just write the continuation naturally.""", n));

        if (!history.isEmpty()) {
            systemMsg.append("\n\nRecent conversation context for reference:\n");
            for (String[] msg : history) {
                String speaker = "assistant".equals(msg[0]) ? "AI" : "Person";
                systemMsg.append(speaker).append(": ")
                        .append(msg[1]).append("\n");
            }
        }

        try {
            String response = callVllmSimple(systemMsg.toString(), currentInput, n);
            List<String> results = parseResponse(response);
            // Strip candidates that repeat the input text
            results = stripInputRepetition(results, currentInput);
            // LLM may return more than requested; truncate to n
            return results.size() > n ? results.subList(0, n) : results;
        } catch (Exception e) {
            LOG.warning("Continuation generation failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Call vLLM with system + user messages only.
     * System contains instructions + conversation history as context.
     * User contains the text to continue, with explicit instruction.
     */
    private String callVllmSimple(String systemMsg,
                                   String currentInput,
                                   int n) throws Exception {
        String escapedModel = escapeJsonValue(model);

        String userMsg = String.format(
                "以下のテキストの続きを%d個予測してください。"
                + "返事や回答ではなく、この人が次に書こうとしているフレーズです:\n\n%s",
                n, currentInput);

        StringBuilder messages = new StringBuilder();
        messages.append("{\"role\":\"system\",\"content\":\"")
                .append(escapeJsonValue(systemMsg)).append("\"}");
        messages.append(",{\"role\":\"user\",\"content\":\"")
                .append(escapeJsonValue(userMsg)).append("\"}");

        String body = "{\"model\":\"" + escapedModel
                + "\",\"messages\":[" + messages + "]"
                + ",\"max_tokens\":256,\"temperature\":0.7"
                + ",\"chat_template_kwargs\":{\"enable_thinking\":false}}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(vllmUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
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

    /**
     * Strip input text repetition from the beginning of each candidate.
     * LLM sometimes echoes part of the user's committed context before the
     * actual continuation. Two strategies are used:
     *
     * 1. Overlap detection: find the longest suffix of the context that
     *    matches a prefix of the candidate, and remove it.
     *    e.g. context="...技があるんですね！" candidate="技があるんですね！次は..."
     *         -> "次は..."
     *
     * 2. Sentence-boundary suffix: if the candidate starts with any
     *    substring of the context that begins after a sentence boundary
     *    (。！？\n), strip it.
     *    e.g. context="今日は天気が良い。散歩に行こう" candidate="散歩に行こうと思って"
     *         -> "と思って"
     */
    static List<String> stripInputRepetition(List<String> candidates, String input) {
        if (input == null || input.isBlank()) return candidates;
        String ctx = input.trim();

        List<String> cleaned = new ArrayList<>();
        for (String candidate : candidates) {
            String c = stripOneCandidate(candidate, ctx);
            if (!c.isEmpty()) {
                cleaned.add(c);
            }
        }
        return cleaned;
    }

    /**
     * Strip overlapping repetition from a single candidate.
     */
    static String stripOneCandidate(String candidate, String ctx) {
        String c = candidate;

        // Strategy 1: find longest suffix of ctx that matches prefix of candidate
        int maxOverlap = Math.min(ctx.length(), c.length());
        int overlapLen = 0;
        for (int len = maxOverlap; len > 0; len--) {
            if (ctx.endsWith(c.substring(0, len))) {
                overlapLen = len;
                break;
            }
        }
        if (overlapLen > 0) {
            c = c.substring(overlapLen).trim();
            if (!c.isEmpty()) return c;
            return "";
        }

        // Strategy 2: check if candidate starts with a sentence-internal fragment
        // Collect substrings of ctx starting after each sentence boundary
        for (int i = 0; i < ctx.length(); i++) {
            char ch = ctx.charAt(i);
            if (ch == '\u3002' || ch == '\uff01' || ch == '\uff1f'
                    || ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                if (i + 1 < ctx.length()) {
                    String fragment = ctx.substring(i + 1).trim();
                    if (!fragment.isEmpty() && c.startsWith(fragment)) {
                        c = c.substring(fragment.length()).trim();
                        if (!c.isEmpty()) return c;
                        return "";
                    }
                }
            }
        }

        return c;
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
