package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;

import java.util.logging.Logger;

/**
 * Tracks IME commit events for logging/diagnostics.
 */
public class QualityTracker {

    private static final Logger LOG = Logger.getLogger(QualityTracker.class.getName());

    private final ActorRef<KnowledgeBase> kbActor;

    public QualityTracker(ActorRef<KnowledgeBase> kbActor) {
        this.kbActor = kbActor;
    }

    /**
     * Called when user commits a conversion.
     */
    public void onCommit(String reading, String output) {
        LOG.fine("Commit: " + reading + " -> " + output);
    }
}
