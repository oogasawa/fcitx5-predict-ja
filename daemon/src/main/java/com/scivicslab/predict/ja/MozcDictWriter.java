package com.scivicslab.predict.ja;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.logging.Logger;

/**
 * Writes entries to a Mozc user dictionary file.
 * Mozc user dictionary format: reading\tcandidate\tcategory\tcomment
 */
public class MozcDictWriter {

    private static final Logger LOG = Logger.getLogger(MozcDictWriter.class.getName());

    // Marker comment to identify entries managed by fcitx5-predict
    private static final String MARKER = "# fcitx5-predict-ja managed entries";
    private static final String END_MARKER = "# end fcitx5-predict-ja";

    private final String dictPath;

    public MozcDictWriter(String dictPath) {
        this.dictPath = dictPath;
    }

    /**
     * Write the curated entries to the Mozc user dictionary.
     * Preserves user's manually added entries and only replaces
     * the section between markers.
     */
    public void writeDictionary(List<KnowledgeBase.DictEntry> entries) {
        Path path = Path.of(dictPath);

        try {
            // Read existing content
            String existing = "";
            if (Files.exists(path)) {
                existing = Files.readString(path);
            }

            // Separate user entries from managed entries
            String userEntries = removeManaged(existing);

            // Build new managed section
            StringBuilder managed = new StringBuilder();
            managed.append(MARKER).append("\n");
            for (var entry : entries) {
                // Mozc format: reading\tcandidate\tcategory\tcomment
                String category = mapCategory(entry.category());
                managed.append(entry.reading())
                       .append("\t")
                       .append(entry.candidate())
                       .append("\t")
                       .append(category)
                       .append("\t")
                       .append("predict")
                       .append("\n");
            }
            managed.append(END_MARKER).append("\n");

            // Write combined content
            String content = userEntries + managed;
            Files.writeString(path, content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            LOG.info("Wrote " + entries.size() + " entries to " + dictPath);

        } catch (IOException e) {
            LOG.warning("Failed to write dictionary: " + e.getMessage());
        }
    }

    /**
     * Remove the managed section from existing dictionary content.
     */
    private String removeManaged(String content) {
        int startIdx = content.indexOf(MARKER);
        if (startIdx < 0) return content;

        int endIdx = content.indexOf(END_MARKER);
        if (endIdx < 0) return content.substring(0, startIdx);

        int endLineIdx = content.indexOf('\n', endIdx);
        if (endLineIdx < 0) endLineIdx = content.length();

        return content.substring(0, startIdx) + content.substring(endLineIdx + 1);
    }

    /**
     * Map internal category to Mozc part-of-speech category.
     */
    private String mapCategory(String category) {
        // Mozc uses specific POS categories
        // Default to "noun" for general entries
        return switch (category) {
            case "verb" -> "動詞";
            case "adjective" -> "形容詞";
            case "phrase" -> "名詞";
            default -> "名詞";
        };
    }
}
