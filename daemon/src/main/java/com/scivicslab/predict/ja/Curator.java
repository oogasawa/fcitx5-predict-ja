package com.scivicslab.predict.ja;

import com.scivicslab.pojoactor.core.ActorRef;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.logging.Logger;

/**
 * Periodically prunes old entries from the knowledge base
 * and writes current entries to the Mozc user dictionary.
 */
public class Curator {

    private static final Logger LOG = Logger.getLogger(Curator.class.getName());

    private final ActorRef<KnowledgeBase> kbActor;
    private final ActorRef<MozcDictWriter> dictWriter;
    private final int maxEntries;
    private final int retentionDays;

    public Curator(ActorRef<KnowledgeBase> kbActor,
                   ActorRef<MozcDictWriter> dictWriter,
                   int maxEntries,
                   int retentionDays) {
        this.kbActor = kbActor;
        this.dictWriter = dictWriter;
        this.maxEntries = maxEntries;
        this.retentionDays = retentionDays;
    }

    /**
     * Perform curation: delete old entries, then write current entries to Mozc dict.
     */
    public void curate() {
        LOG.info("Starting curation cycle");

        // Delete entries older than retention period
        String cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toString();
        kbActor.tell(kb -> kb.deleteOlderThan(cutoff));

        // Write current entries to Mozc dictionary
        kbActor.ask(kb -> kb.getAllEntries(maxEntries))
                .thenAccept(entries -> {
                    LOG.info("Writing " + entries.size() + " entries to active dictionary");
                    dictWriter.tell(w -> w.writeDictionary(entries));
                })
                .exceptionally(e -> {
                    LOG.warning("Curation failed: " + e.getMessage());
                    return null;
                });
    }
}
