package com.scivicslab.predict.ja;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MozcDictWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writeDictionary() throws IOException {
        Path dictPath = tempDir.resolve("user_dictionary.db");
        MozcDictWriter writer = new MozcDictWriter(dictPath.toString());

        List<KnowledgeBase.DictEntry> entries = List.of(
                new KnowledgeBase.DictEntry("きょう", "今日", "general"),
                new KnowledgeBase.DictEntry("てんき", "天気", "general")
        );

        writer.writeDictionary(entries);

        // Decode and verify
        var dicts = MozcProtobufCodec.readFile(dictPath);
        assertEquals(1, dicts.size());
        assertEquals(MozcDictWriter.MANAGED_DICT_NAME, dicts.get(0).name());
        assertEquals(2, dicts.get(0).entries().size());
        assertEquals("きょう", dicts.get(0).entries().get(0).key());
        assertEquals("今日", dicts.get(0).entries().get(0).value());
        assertEquals("てんき", dicts.get(0).entries().get(1).key());
        assertEquals("天気", dicts.get(0).entries().get(1).value());
    }

    @Test
    void preservesOtherDictionaries() throws IOException {
        Path dictPath = tempDir.resolve("user_dictionary.db");

        // Write a user dictionary first
        var userDict = new MozcProtobufCodec.Dictionary(12345L, "ユーザー辞書 1",
                List.of(new MozcProtobufCodec.Entry("てすと", "テスト", "", MozcProtobufCodec.POS_NOUN)));
        MozcProtobufCodec.writeFile(dictPath, List.of(userDict));

        // Now write managed entries
        MozcDictWriter writer = new MozcDictWriter(dictPath.toString());
        writer.writeDictionary(List.of(
                new KnowledgeBase.DictEntry("きょう", "今日", "general")
        ));

        // Both dictionaries should exist
        var dicts = MozcProtobufCodec.readFile(dictPath);
        assertEquals(2, dicts.size());
        assertEquals("ユーザー辞書 1", dicts.get(0).name());
        assertEquals(1, dicts.get(0).entries().size());
        assertEquals("てすと", dicts.get(0).entries().get(0).key());
        assertEquals(MozcDictWriter.MANAGED_DICT_NAME, dicts.get(1).name());
        assertEquals("きょう", dicts.get(1).entries().get(0).key());
    }

    @Test
    void replacesOnlyManagedDictionary() throws IOException {
        Path dictPath = tempDir.resolve("user_dictionary.db");
        MozcDictWriter writer = new MozcDictWriter(dictPath.toString());

        // First write
        writer.writeDictionary(List.of(
                new KnowledgeBase.DictEntry("きょう", "今日", "general")
        ));

        // Second write with different entries
        writer.writeDictionary(List.of(
                new KnowledgeBase.DictEntry("あした", "明日", "general")
        ));

        var dicts = MozcProtobufCodec.readFile(dictPath);
        assertEquals(1, dicts.size());
        var entries = dicts.get(0).entries();
        assertEquals(1, entries.size());
        assertEquals("あした", entries.get(0).key());
        assertEquals("明日", entries.get(0).value());
    }
}
