package com.scivicslab.predict.ja;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MozcDictWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writeDictionary() throws IOException {
        Path dictPath = tempDir.resolve("user_dict.txt");
        MozcDictWriter writer = new MozcDictWriter(dictPath.toString());

        List<KnowledgeBase.DictEntry> entries = List.of(
                new KnowledgeBase.DictEntry("きょう", "今日", 5.0, "general"),
                new KnowledgeBase.DictEntry("てんき", "天気", 3.0, "general")
        );

        writer.writeDictionary(entries);

        String content = Files.readString(dictPath);
        assertTrue(content.contains("きょう\t今日\t名詞\tpredict"));
        assertTrue(content.contains("てんき\t天気\t名詞\tpredict"));
        assertTrue(content.contains("# fcitx5-predict-ja managed entries"));
        assertTrue(content.contains("# end fcitx5-predict-ja"));
    }

    @Test
    void preservesUserEntries() throws IOException {
        Path dictPath = tempDir.resolve("user_dict.txt");

        // Write some user entries first
        Files.writeString(dictPath, "ユーザー\tユーザー辞書\t名詞\tmanual\n");

        MozcDictWriter writer = new MozcDictWriter(dictPath.toString());
        List<KnowledgeBase.DictEntry> entries = List.of(
                new KnowledgeBase.DictEntry("きょう", "今日", 5.0, "general")
        );

        writer.writeDictionary(entries);

        String content = Files.readString(dictPath);
        // Both user entry and managed entry should exist
        assertTrue(content.contains("ユーザー\tユーザー辞書\t名詞\tmanual"));
        assertTrue(content.contains("きょう\t今日\t名詞\tpredict"));
    }

    @Test
    void replacesOnlyManagedSection() throws IOException {
        Path dictPath = tempDir.resolve("user_dict.txt");
        MozcDictWriter writer = new MozcDictWriter(dictPath.toString());

        // First write
        writer.writeDictionary(List.of(
                new KnowledgeBase.DictEntry("きょう", "今日", 5.0, "general")
        ));

        // Second write with different entries
        writer.writeDictionary(List.of(
                new KnowledgeBase.DictEntry("あした", "明日", 3.0, "general")
        ));

        String content = Files.readString(dictPath);
        assertFalse(content.contains("きょう"), "Old managed entry should be replaced");
        assertTrue(content.contains("あした\t明日\t名詞\tpredict"));
    }
}
