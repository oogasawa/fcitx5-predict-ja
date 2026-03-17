package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
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
                new Curator(kbActor, dictWriter, config.maxDictEntries()));
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

        // MCP Gateway data source (optional, only if URL configured)
        if (config.gatewayUrl() != null && !config.gatewayUrl().isBlank()) {
            LlmConsoleSource consoleSource = new LlmConsoleSource(
                    analyzer, config.gatewayUrl());
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
