package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Main entry point for the fcitx5-predict-ja daemon.
 * Builds the actor system, starts the HTTP API server, and schedules periodic tasks.
 */
public class PredictDaemon {

    private static final Logger LOG = Logger.getLogger(PredictDaemon.class.getName());

    private final DaemonConfig config;
    private ActorSystem system;
    private Scheduler scheduler;
    private HttpServer httpServer;

    public PredictDaemon(DaemonConfig config) {
        this.config = config;
    }

    public void start() throws IOException {
        LOG.info("Starting fcitx5-predict-ja daemon");
        LOG.info("IME learning: " + (config.imeLearning() ? "ON" : "OFF"));

        // Ensure data directory exists
        Files.createDirectories(Path.of(config.dataDir()));

        // Create actor system
        system = new ActorSystem("predict-ja");

        // Create knowledge base (H2)
        KnowledgeBase kb = new KnowledgeBase(config.dataDir() + "/knowledge");

        // Create actors
        ActorRef<KnowledgeBase> kbActor = system.actorOf("knowledgeBase", kb);
        ActorRef<MozcDictWriter> dictWriter = system.actorOf("dictWriter",
                new MozcDictWriter(config.mozcUserDictPath()));
        ActorRef<QualityTracker> quality = system.actorOf("quality",
                new QualityTracker(kbActor));
        ActorRef<Curator> curator = system.actorOf("curator",
                new Curator(kbActor, dictWriter, config.maxDictEntries(), 30));
        ActorRef<LlmEnricher> enricher = system.actorOf("enricher",
                new LlmEnricher(kbActor, config.vllmUrl(), config.vllmModel()));
        ActorRef<Analyzer> analyzer = system.actorOf("analyzer",
                new Analyzer(enricher));
        ActorRef<InputMonitor> monitor = system.actorOf("inputMonitor",
                new InputMonitor(analyzer, quality));

        // Schedule periodic curation
        scheduler = new Scheduler();
        scheduler.scheduleWithFixedDelay("curate", curator,
                c -> c.curate(),
                config.curateIntervalMinutes(), config.curateIntervalMinutes(),
                TimeUnit.MINUTES);

        // Continuation service (Smart Compose) — created early so Gateway can feed it
        ContinuationService continuationService = new ContinuationService(
                config.vllmUrl(), config.vllmModel());

        // MCP Gateway data source (optional, only if URL configured)
        if (config.gatewayUrl() != null && !config.gatewayUrl().isBlank()) {
            LlmConsoleSource consoleSource = new LlmConsoleSource(
                    analyzer, config.gatewayUrl(), continuationService);
            ActorRef<LlmConsoleSource> consoleActor = system.actorOf("llmConsole", consoleSource);
            scheduler.scheduleWithFixedDelay("gatewayPoll", consoleActor,
                    c -> c.poll(),
                    30, config.gatewayPollSeconds(),
                    TimeUnit.SECONDS);
            LOG.info("MCP Gateway polling enabled: " + config.gatewayUrl());
        }

        // Start HTTP server
        httpServer = HttpServer.create(new InetSocketAddress(config.port()), 0);

        // /api/record — IME commit text (conditional on --ime-learning)
        if (config.imeLearning()) {
            httpServer.createContext("/api/record", new RecordHandler(monitor));
            LOG.info("IME learning endpoint active: POST /api/record");
        } else {
            // Accept but discard — prevents plugin errors when IME learning is off
            httpServer.createContext("/api/record", exchange -> {
                byte[] resp = "{\"status\":\"ime-learning-disabled\"}".getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                exchange.getResponseBody().write(resp);
                exchange.getResponseBody().close();
            });
            LOG.info("IME learning disabled: POST /api/record will be ignored");
        }

        httpServer.createContext("/api/flush", exchange -> {
            analyzer.tell(a -> a.flush());
            byte[] resp = "{\"status\":\"flushed\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();
        });
        httpServer.createContext("/api/curate", exchange -> {
            curator.tell(c -> c.curate());
            byte[] resp = "{\"status\":\"curating\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();
        });
        // /api/predict?prefix=<hiragana>&limit=<n> — prefix search for candidates
        httpServer.createContext("/api/predict", exchange -> {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            String prefix = params.getOrDefault("prefix", "");
            int limit = 10;
            try { limit = Integer.parseInt(params.getOrDefault("limit", "10")); }
            catch (NumberFormatException ignored) {}

            List<KnowledgeBase.DictEntry> entries = kb.findByPrefix(prefix, limit);

            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) json.append(",");
                var e = entries.get(i);
                json.append("{\"reading\":\"").append(escapeJson(e.reading()))
                    .append("\",\"candidate\":\"").append(escapeJson(e.candidate()))
                    .append("\"}");
            }
            json.append("]");

            byte[] resp = json.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        // /api/continue — LLM continuation (Smart Compose)
        httpServer.createContext("/api/continue", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String context = extractJsonString(body, "context");
            int n = extractJsonInt(body, "n", 5);
            LOG.info("/api/continue called: context=[" + (context != null ? context.substring(0, Math.min(context.length(), 50)) : "null") + "] n=" + n);

            List<String> candidates = continuationService.generate(context, n);
            LOG.info("/api/continue returning " + candidates.size() + " candidates");

            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) json.append(",");
                json.append("{\"text\":\"").append(escapeJson(candidates.get(i))).append("\"}");
            }
            json.append("]");

            byte[] resp = json.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        // /api/segment-convert — Mozc segmentation
        MozcClient mozcClient = new MozcClient();
        httpServer.createContext("/api/segment-convert", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // Parse "input" field from JSON (simple extraction)
            String input = extractJsonString(body, "input");
            if (input == null || input.isBlank()) {
                byte[] resp = "{\"segments\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
                return;
            }

            List<MozcClient.SegmentResult> segments = mozcClient.getSegments(input);

            StringBuilder json = new StringBuilder("{\"segments\":[");
            for (int i = 0; i < segments.size(); i++) {
                if (i > 0) json.append(",");
                var seg = segments.get(i);
                json.append("{\"reading\":\"").append(escapeJson(seg.reading()))
                    .append("\",\"candidates\":[");
                for (int j = 0; j < seg.candidates().size(); j++) {
                    if (j > 0) json.append(",");
                    json.append("\"").append(escapeJson(seg.candidates().get(j))).append("\"");
                }
                json.append("]}");
            }
            json.append("]}");

            byte[] resp = json.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        httpServer.createContext("/api/health", exchange -> {
            byte[] resp = "{\"status\":\"ok\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();
        });
        httpServer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        httpServer.start();

        LOG.info("Daemon started on port " + config.port());
    }

    public void shutdown() {
        LOG.info("Shutting down daemon");
        if (httpServer != null) httpServer.stop(2);
        if (scheduler != null) scheduler.close();
        if (system != null) system.terminate();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, val);
            }
        }
        return params;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Extract a string value from a simple JSON object.
     * E.g. extractJsonString({"input":"abc","n":5}, "input") returns "abc".
     */
    /**
     * Extract an integer value from a simple JSON object.
     * Handles both {"n":5} (unquoted) and {"n":"5"} (quoted).
     */
    static int extractJsonInt(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return defaultValue;
        idx = json.indexOf(":", idx + pattern.length());
        if (idx < 0) return defaultValue;
        // Skip whitespace after colon
        int start = idx + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return defaultValue;
        // If quoted, strip quotes
        if (json.charAt(start) == '"') {
            int end = json.indexOf("\"", start + 1);
            if (end < 0) return defaultValue;
            try { return Integer.parseInt(json.substring(start + 1, end)); }
            catch (NumberFormatException e) { return defaultValue; }
        }
        // Unquoted number
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end == start) return defaultValue;
        try { return Integer.parseInt(json.substring(start, end)); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        idx = json.indexOf(":", idx + pattern.length());
        if (idx < 0) return null;
        idx = json.indexOf("\"", idx + 1);
        if (idx < 0) return null;
        int end = json.indexOf("\"", idx + 1);
        if (end < 0) return null;
        return json.substring(idx + 1, end);
    }

    public static void main(String[] args) {
        DaemonConfig config = DaemonConfig.fromArgsOrDefaults(args);
        PredictDaemon daemon = new PredictDaemon(config);

        Runtime.getRuntime().addShutdownHook(new Thread(daemon::shutdown));

        try {
            daemon.start();
            // Keep running
            Thread.currentThread().join();
        } catch (Exception e) {
            LOG.severe("Daemon failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
