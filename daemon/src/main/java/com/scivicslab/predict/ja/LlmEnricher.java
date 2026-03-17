package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.logging.Logger;

/**
 * Queries vLLM to generate related phrases from committed text,
 * then adds them to the knowledge base.
 */
public class LlmEnricher {

    private static final Logger LOG = Logger.getLogger(LlmEnricher.class.getName());

    private final ActorRef<KnowledgeBase> kbActor;
    private final String vllmUrl;
    private final String model;
    private final HttpClient httpClient;

    public LlmEnricher(ActorRef<KnowledgeBase> kbActor, String vllmUrl, String model) {
        this.kbActor = kbActor;
        this.vllmUrl = vllmUrl;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Take a batch of committed text and ask vLLM to generate related phrases.
     */
    public void enrich(List<Analyzer.CommitRecord> batch) {
        // First, add the committed text directly to knowledge base (if valid)
        for (var record : batch) {
            String normalized = EntryFilter.filter(record.reading(), record.output());
            if (normalized != null) {
                final String nr = normalized;
                kbActor.tell(kb -> kb.addEntry(nr, record.output(), "ime"));
            }
        }

        // Build a prompt to generate related phrases
        StringBuilder contextBuilder = new StringBuilder();
        for (var record : batch) {
            contextBuilder.append(record.output()).append(" ");
        }
        String context = contextBuilder.toString().trim();

        if (context.length() < 5) return; // Too short to be useful

        String prompt = buildPrompt(context);
        try {
            String response = callVllm(prompt);
            parseAndStore(response);
        } catch (Exception e) {
            LOG.warning("LLM enrichment failed: " + e.getMessage());
        }
    }

    private String buildPrompt(String context) {
        return String.format("""
                Given the following Japanese text that a user recently typed:
                "%s"

                Generate 10 related Japanese phrases that the user might type next.
                Each line should be in the format: reading[TAB]phrase
                Where reading is the hiragana reading and phrase is the kanji/katakana form.
                Output ONLY the tab-separated list. No explanations, no numbering.
                Example:
                でぷろいめんと\tデプロイメント
                ぽっど\tポッド
                """, context);
    }

    String callVllm(String prompt) throws Exception {
        String escapedPrompt = escapeJson(prompt);
        String body = "{\"model\":\"" + model
                + "\",\"messages\":[{\"role\":\"user\",\"content\":" + escapedPrompt
                + "}],\"max_tokens\":512,\"temperature\":0.7"
                + ",\"chat_template_kwargs\":{\"enable_thinking\":false}}";

        LOG.fine("Request body: " + body.substring(0, Math.min(body.length(), 200)));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(vllmUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();

        LOG.fine("Sending request to: " + vllmUrl + "/v1/chat/completions");

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOG.fine("vLLM response body: " + response.body());
            throw new RuntimeException("vLLM returned " + response.statusCode());
        }

        return extractContent(response.body());
    }

    private void parseAndStore(String response) {
        LOG.fine("LLM response: " + response.substring(0, Math.min(response.length(), 500)));
        String[] lines = response.split("\n");
        int count = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            String[] parts = trimmed.split("\t", 2);
            if (parts.length == 2) {
                String reading = parts[0].trim();
                String candidate = parts[1].trim();
                String normalized = EntryFilter.filter(reading, candidate);
                if (normalized != null) {
                    kbActor.tell(kb -> kb.addEntry(normalized, candidate, "llm"));
                    count++;
                }
            }
        }
        LOG.info("Stored " + count + " entries from LLM enrichment");
    }

    /**
     * Extract the assistant message content from OpenAI-compatible JSON response.
     */
    private String extractContent(String json) {
        // Simple extraction without full JSON parsing
        int contentIdx = json.indexOf("\"content\"");
        if (contentIdx < 0) return "";
        int colonIdx = json.indexOf(":", contentIdx);
        int startQuote = json.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return "";

        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                if (c == 'n') sb.append('\n');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String escapeJson(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\t", "\\t") + "\"";
    }
}
