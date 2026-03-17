package com.scivicslab.predict.ja;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseTest {

    private KnowledgeBase kb;
    private Path tempDb;

    @BeforeEach
    void setUp() throws IOException {
        tempDb = Files.createTempDirectory("kb-test-");
        kb = new KnowledgeBase(tempDb.resolve("knowledge").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        kb.close();
        // H2 creates .mv.db and .trace.db files
        try (var files = Files.list(tempDb)) {
            files.forEach(f -> { try { Files.delete(f); } catch (IOException e) {} });
        }
        Files.deleteIfExists(tempDb);
    }

    @Test
    void addAndRetrieve() {
        kb.addEntry("きょう", "今日", "ime");
        kb.addEntry("てんき", "天気", "ime");

        List<KnowledgeBase.DictEntry> entries = kb.getTopEntries(10);
        assertEquals(2, entries.size());
    }

    @Test
    void duplicateEntryBumpsScore() {
        kb.addEntry("きょう", "今日", "ime");
        kb.addEntry("きょう", "今日", "ime"); // duplicate

        List<KnowledgeBase.DictEntry> entries = kb.getTopEntries(10);
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).score() > 1.0, "Score should be bumped");
    }

    @Test
    void recordUseIncreasesScore() {
        kb.addEntry("きょう", "今日", "ime");
        double initialScore = kb.getTopEntries(10).get(0).score();

        kb.recordUse("きょう", "今日");
        double afterUse = kb.getTopEntries(10).get(0).score();

        assertTrue(afterUse > initialScore);
    }

    @Test
    void recordIgnoreDecreasesScore() {
        kb.addEntry("きょう", "今日", "ime");
        // Bump score high enough that ignore won't drop below threshold
        for (int i = 0; i < 5; i++) kb.recordUse("きょう", "今日");
        double highScore = kb.getTopEntries(10).get(0).score();

        kb.recordIgnore("きょう");
        double afterIgnore = kb.getTopEntries(10).get(0).score();

        assertTrue(afterIgnore < highScore);
    }

    @Test
    void entryCount() {
        assertEquals(0, kb.getEntryCount());
        kb.addEntry("きょう", "今日", "ime");
        kb.addEntry("てんき", "天気", "llm");
        assertEquals(2, kb.getEntryCount());
    }

    @Test
    void topEntriesRespectLimit() {
        for (int i = 0; i < 20; i++) {
            kb.addEntry("reading" + i, "candidate" + i, "ime");
        }
        List<KnowledgeBase.DictEntry> top5 = kb.getTopEntries(5);
        assertEquals(5, top5.size());
    }
}
