package com.scivicslab.predict.ja;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        try (var files = Files.list(tempDb)) {
            files.forEach(f -> { try { Files.delete(f); } catch (IOException e) {} });
        }
        Files.deleteIfExists(tempDb);
    }

    @Test
    void addAndRetrieve() {
        kb.addEntry("きょう", "今日", "ime");
        kb.addEntry("てんき", "天気", "ime");

        List<KnowledgeBase.DictEntry> entries = kb.getAllEntries(10);
        assertEquals(2, entries.size());
    }

    @Test
    void duplicateEntryIsIdempotent() {
        kb.addEntry("きょう", "今日", "ime");
        kb.addEntry("きょう", "今日", "ime"); // duplicate

        List<KnowledgeBase.DictEntry> entries = kb.getAllEntries(10);
        assertEquals(1, entries.size());
    }

    @Test
    void deleteOlderThan() {
        kb.addEntry("きょう", "今日", "ime");
        kb.addEntry("てんき", "天気", "ime");

        // Delete entries older than far future — should delete all
        String farFuture = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        int deleted = kb.deleteOlderThan(farFuture);
        assertEquals(2, deleted);
        assertEquals(0, kb.getEntryCount());
    }

    @Test
    void deleteOlderThanPreservesRecent() {
        kb.addEntry("きょう", "今日", "ime");
        kb.addEntry("てんき", "天気", "ime");

        // Delete entries older than far past — should delete none
        String farPast = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        int deleted = kb.deleteOlderThan(farPast);
        assertEquals(0, deleted);
        assertEquals(2, kb.getEntryCount());
    }

    @Test
    void entryCount() {
        assertEquals(0, kb.getEntryCount());
        kb.addEntry("きょう", "今日", "ime");
        kb.addEntry("てんき", "天気", "llm");
        assertEquals(2, kb.getEntryCount());
    }

    @Test
    void getAllEntriesRespectLimit() {
        for (int i = 0; i < 20; i++) {
            kb.addEntry("reading" + i, "candidate" + i, "ime");
        }
        List<KnowledgeBase.DictEntry> top5 = kb.getAllEntries(5);
        assertEquals(5, top5.size());
    }

    @Test
    void findByPrefix() {
        kb.addEntry("きょう", "今日", "ime");
        kb.addEntry("きのう", "昨日", "ime");
        kb.addEntry("てんき", "天気", "ime");

        List<KnowledgeBase.DictEntry> results = kb.findByPrefix("き", 10);
        assertEquals(2, results.size());
    }
}
