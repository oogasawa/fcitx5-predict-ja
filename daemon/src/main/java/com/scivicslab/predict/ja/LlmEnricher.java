package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Uses LLM to filter and consolidate candidate phrases before storing
 * in the knowledge base. The LLM does NOT generate phrases — it selects
 * and deduplicates from phrases extracted by kuromoji.
 */
public class LlmEnricher {

    private static final Logger LOG = Logger.getLogger(LlmEnricher.class.getName());

    private final ActorRef<KnowledgeBase> kbActor;
    private final String vllmUrl;
    private final String model;
    private final HttpClient httpClient;
    private final ReadingResolver readingResolver;

    public LlmEnricher(ActorRef<KnowledgeBase> kbActor, String vllmUrl, String model) {
        this.kbActor = kbActor;
        this.vllmUrl = vllmUrl;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.readingResolver = new ReadingResolver();
    }

    /**
     * Filter and consolidate a list of raw candidate phrases using LLM.
     * Similar expressions are merged, useless fragments are dropped.
     * Only the survivors get stored in the knowledge base.
     */
    public void filterAndStore(List<String> rawCandidates) {
        if (rawCandidates.isEmpty()) return;

        LOG.info("Filtering " + rawCandidates.size() + " candidates via LLM");

        String prompt = buildFilterPrompt(rawCandidates);
        try {
            String response = callVllm(prompt);
            parseAndStore(response);
        } catch (Exception e) {
            LOG.warning("LLM filtering failed: " + e.getMessage());
            // Fallback: store all candidates without filtering
            storeWithoutFilter(rawCandidates);
        }
    }

    private String buildFilterPrompt(List<String> candidates) {
        StringBuilder list = new StringBuilder();
        for (String c : candidates) {
            list.append("- ").append(c).append("\n");
        }

        return String.format("""
                以下は日本語の予測変換候補として抽出されたフレーズのリストです:
                ---
                %s---

                このリストから、予測変換の候補として不要なものだけを除外してください。
                できるだけ多く残してください。除外するのは以下の場合だけです:
                - 表現がほぼ同一のもの（例:「ビルドします」と「ビルドする」）→ 片方だけ残す
                - 意味が似ているだけのものは両方残す（例:「ビルドします」と「ビルド成功です」は別の候補）
                - 意味が不完全な断片（文として成立しないもの）
                - 残したフレーズを1行に1つずつ出力してください
                - フレーズはそのまま出力し、変更しないでください
                - 番号や説明は不要です
                """, list);
    }

    String callVllm(String prompt) throws Exception {
        String escapedPrompt = escapeJson(prompt);
        String body = "{\"model\":\"" + model
                + "\",\"messages\":[{\"role\":\"user\",\"content\":" + escapedPrompt
                + "}],\"max_tokens\":512,\"temperature\":0.3"
                + ",\"chat_template_kwargs\":{\"enable_thinking\":false}}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(vllmUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("vLLM returned " + response.statusCode());
        }

        return extractContent(response.body());
    }

    private void parseAndStore(String response) {
        String[] lines = response.split("\\n+");
        int count = 0;
        for (String line : lines) {
            String candidate = line.trim().replaceFirst("^[\\d]+[.)\\s]+", "").replaceFirst("^[-・]\\s*", "").trim();
            if (candidate.isEmpty()) continue;

            String reading = readingResolver.resolve(candidate);
            if (reading == null) {
                LOG.fine("Could not resolve reading for: " + candidate);
                continue;
            }

            String normalized = EntryFilter.filter(reading, candidate);
            if (normalized != null) {
                kbActor.tell(kb -> kb.addEntry(normalized, candidate, "harvest"));
                count++;
            }
        }
        LOG.info("Stored " + count + " entries after LLM filtering");
    }

    /**
     * Fallback: store candidates without LLM filtering (when LLM is unavailable).
     */
    private void storeWithoutFilter(List<String> candidates) {
        int count = 0;
        for (String candidate : candidates) {
            String reading = readingResolver.resolve(candidate);
            if (reading == null) continue;

            String normalized = EntryFilter.filter(reading, candidate);
            if (normalized != null) {
                kbActor.tell(kb -> kb.addEntry(normalized, candidate, "harvest"));
                count++;
            }
        }
        LOG.info("Stored " + count + " entries without LLM filtering (fallback)");
    }

    private String extractContent(String json) {
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
