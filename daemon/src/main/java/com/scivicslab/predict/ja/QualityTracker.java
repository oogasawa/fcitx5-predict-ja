package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;

import java.util.logging.Logger;

/**
 * Tracks whether dictionary candidates are being used or ignored,
 * and updates scores in the knowledge base accordingly.
 */
public class QualityTracker {

    private static final Logger LOG = Logger.getLogger(QualityTracker.class.getName());

    private final ActorRef<KnowledgeBase> kbActor;

    public QualityTracker(ActorRef<KnowledgeBase> kbActor) {
        this.kbActor = kbActor;
    }

    /**
     * Called when user commits a conversion.
     * Records usage to boost the score of the selected candidate.
     */
    public void onCommit(String reading, String output) {
        kbActor.tell(kb -> kb.recordUse(reading, output));
    }
}
