package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;

import java.util.logging.Logger;

/**
 * Receives committed text from the fcitx5 plugin and dispatches
 * to Analyzer (for enrichment) and QualityTracker (for scoring).
 */
public class InputMonitor {

    private static final Logger LOG = Logger.getLogger(InputMonitor.class.getName());

    private final ActorRef<Analyzer> analyzer;
    private final ActorRef<QualityTracker> quality;

    public InputMonitor(ActorRef<Analyzer> analyzer, ActorRef<QualityTracker> quality) {
        this.analyzer = analyzer;
        this.quality = quality;
    }

    /**
     * Called when user commits a conversion via IME.
     *
     * @param reading  the original input (hiragana)
     * @param output   the committed text (kanji/katakana)
     * @param context  surrounding text for context (may be empty)
     */
    public void onCommit(String reading, String output, String context) {
        LOG.fine("Commit received: " + reading + " -> " + output);

        // Track quality (was a dictionary candidate used?)
        quality.tell(q -> q.onCommit(reading, output));

        // Send to analyzer for potential enrichment
        analyzer.tell(a -> a.analyze(reading, output, context));
    }
}
