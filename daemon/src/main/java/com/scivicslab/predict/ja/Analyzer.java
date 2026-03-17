package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Accumulates committed text and periodically triggers LLM enrichment.
 * Batches inputs to avoid calling the LLM on every keystroke.
 */
public class Analyzer {

    private static final Logger LOG = Logger.getLogger(Analyzer.class.getName());
    private static final int BATCH_SIZE = 10;

    private final ActorRef<LlmEnricher> enricher;
    private final List<CommitRecord> buffer = new ArrayList<>();

    public Analyzer(ActorRef<LlmEnricher> enricher) {
        this.enricher = enricher;
    }

    public void analyze(String reading, String output, String context) {
        buffer.add(new CommitRecord(reading, output, context));

        if (buffer.size() >= BATCH_SIZE) {
            flush();
        }
    }

    /**
     * Flush the buffer and send accumulated text to LlmEnricher.
     */
    public void flush() {
        if (buffer.isEmpty()) return;

        List<CommitRecord> batch = new ArrayList<>(buffer);
        buffer.clear();

        LOG.info("Flushing " + batch.size() + " records to enricher");
        enricher.tell(e -> e.enrich(batch));
    }

    public int getBufferSize() {
        return buffer.size();
    }

    public record CommitRecord(String reading, String output, String context) {}
}
