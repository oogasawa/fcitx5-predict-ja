package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Analyzes raw conversation text from Gateway.
 * Extracts candidate phrases by splitting on sentence boundaries,
 * then delegates to LlmEnricher for filtering and consolidation.
 */
public class Analyzer {

    private static final Logger LOG = Logger.getLogger(Analyzer.class.getName());

    private final ActorRef<LlmEnricher> enricher;
    private final List<String> candidateBuffer = new ArrayList<>();

    public Analyzer(ActorRef<LlmEnricher> enricher) {
        this.enricher = enricher;
    }

    /**
     * Called when user commits a conversion via IME (ime-learning mode).
     * Stores the raw text as a candidate for LLM filtering.
     */
    public void analyze(String reading, String output, String context) {
        if (output == null || output.isBlank()) return;
        candidateBuffer.add(output);
        if (candidateBuffer.size() >= 10) {
            flush();
        }
    }

    /**
     * Accept raw text (e.g., from LLM Console) and extract candidate phrases.
     * Candidates are buffered and sent to LLM for filtering in batches.
     */
    public void analyzeRawText(String text) {
        if (text == null || text.isBlank()) return;

        // Split by sentence boundaries
        String[] segments = text.split("[\\n。！？!?]+");

        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty() || trimmed.length() < 3) continue;
            candidateBuffer.add(trimmed);
        }

        LOG.info("Buffered " + candidateBuffer.size() + " candidates");

        // Send to LLM for filtering when we have enough
        if (candidateBuffer.size() >= 10) {
            flush();
        }
    }

    /**
     * Flush buffered candidates to LLM for filtering and storage.
     */
    public void flush() {
        if (candidateBuffer.isEmpty()) return;

        List<String> batch = new ArrayList<>(candidateBuffer);
        candidateBuffer.clear();

        LOG.info("Sending " + batch.size() + " candidates to LLM for filtering");
        enricher.tell(e -> e.filterAndStore(batch));
    }

    public int getBufferSize() {
        return candidateBuffer.size();
    }
}
