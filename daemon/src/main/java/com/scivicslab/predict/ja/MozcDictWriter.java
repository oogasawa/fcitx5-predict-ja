package com.scivicslab.predict.ja;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Writes entries to the Mozc user dictionary in protobuf format.
 * Manages a dedicated dictionary named "fcitx5-predict" inside
 * ~/.config/mozc/user_dictionary.db, preserving all other dictionaries.
 */
public class MozcDictWriter {

    private static final Logger LOG = Logger.getLogger(MozcDictWriter.class.getName());

    // Name of the managed dictionary inside Mozc's DB
    static final String MANAGED_DICT_NAME = "fcitx5-predict";

    // Fixed dictionary ID for the managed dictionary
    static final long MANAGED_DICT_ID = 0x6663697478357072L; // "fcitx5pr" as long

    private final String dictPath;

    public MozcDictWriter(String dictPath) {
        this.dictPath = dictPath;
    }

    /**
     * Write the curated entries to the Mozc user dictionary.
     * Preserves user's other dictionaries and only replaces
     * the "fcitx5-predict" dictionary.
     */
    public void writeDictionary(List<KnowledgeBase.DictEntry> entries) {
        Path path = Path.of(dictPath);

        try {
            // Read existing dictionaries
            List<MozcProtobufCodec.Dictionary> existing = MozcProtobufCodec.readFile(path);

            // Build new managed dictionary entries
            var mozcEntries = new ArrayList<MozcProtobufCodec.Entry>();
            for (var entry : entries) {
                int pos = MozcProtobufCodec.categoryToPos(entry.category());
                mozcEntries.add(new MozcProtobufCodec.Entry(
                        entry.reading(), entry.candidate(), "predict", pos));
            }

            // Replace managed dictionary, keep others
            var updated = new ArrayList<MozcProtobufCodec.Dictionary>();
            boolean found = false;
            for (var dict : existing) {
                if (MANAGED_DICT_NAME.equals(dict.name())) {
                    updated.add(new MozcProtobufCodec.Dictionary(
                            dict.id(), MANAGED_DICT_NAME, mozcEntries));
                    found = true;
                } else {
                    updated.add(dict);
                }
            }
            if (!found) {
                updated.add(new MozcProtobufCodec.Dictionary(
                        MANAGED_DICT_ID, MANAGED_DICT_NAME, mozcEntries));
            }

            MozcProtobufCodec.writeFile(path, updated);
            LOG.info("Wrote " + entries.size() + " entries to " + dictPath);

        } catch (IOException e) {
            LOG.warning("Failed to write dictionary: " + e.getMessage());
        }
    }
}
