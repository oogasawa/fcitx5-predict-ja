package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;

import java.util.List;
import java.util.logging.Logger;

/**
 * Periodically selects top-scoring entries from the knowledge base
 * and writes them to the active Mozc user dictionary.
 */
public class Curator {

    private static final Logger LOG = Logger.getLogger(Curator.class.getName());

    private final ActorRef<KnowledgeBase> kbActor;
    private final ActorRef<MozcDictWriter> dictWriter;
    private final int maxEntries;

    public Curator(ActorRef<KnowledgeBase> kbActor,
                   ActorRef<MozcDictWriter> dictWriter,
                   int maxEntries) {
        this.kbActor = kbActor;
        this.dictWriter = dictWriter;
        this.maxEntries = maxEntries;
    }

    /**
     * Perform curation: read top entries from knowledge base, write to active dict.
     */
    public void curate() {
        LOG.info("Starting curation cycle");

        kbActor.ask(kb -> kb.getTopEntries(maxEntries))
                .thenAccept(entries -> {
                    LOG.info("Curated " + entries.size() + " entries for active dictionary");
                    dictWriter.tell(w -> w.writeDictionary(entries));
                })
                .exceptionally(e -> {
                    LOG.warning("Curation failed: " + e.getMessage());
                    return null;
                });
    }
}
