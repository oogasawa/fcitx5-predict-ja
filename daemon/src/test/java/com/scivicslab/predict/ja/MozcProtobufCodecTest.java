package com.scivicslab.predict.ja;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MozcProtobufCodecTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripSingleDictionary() {
        var entries = List.of(
                new MozcProtobufCodec.Entry("きょう", "今日", "", MozcProtobufCodec.POS_NOUN),
                new MozcProtobufCodec.Entry("はしる", "走る", "", MozcProtobufCodec.POS_VERB)
        );
        var dict = new MozcProtobufCodec.Dictionary(42L, "test-dict", entries);

        byte[] encoded = MozcProtobufCodec.encodeStorage(List.of(dict));
        var decoded = MozcProtobufCodec.decodeStorage(encoded);

        assertEquals(1, decoded.size());
        assertEquals(42L, decoded.get(0).id());
        assertEquals("test-dict", decoded.get(0).name());
        assertEquals(2, decoded.get(0).entries().size());
        assertEquals("きょう", decoded.get(0).entries().get(0).key());
        assertEquals("今日", decoded.get(0).entries().get(0).value());
        assertEquals(MozcProtobufCodec.POS_NOUN, decoded.get(0).entries().get(0).pos());
        assertEquals("はしる", decoded.get(0).entries().get(1).key());
        assertEquals("走る", decoded.get(0).entries().get(1).value());
        assertEquals(MozcProtobufCodec.POS_VERB, decoded.get(0).entries().get(1).pos());
    }

    @Test
    void roundTripMultipleDictionaries() {
        var dict1 = new MozcProtobufCodec.Dictionary(1L, "dict-1",
                List.of(new MozcProtobufCodec.Entry("あ", "亜", "", MozcProtobufCodec.POS_NOUN)));
        var dict2 = new MozcProtobufCodec.Dictionary(2L, "dict-2",
                List.of(new MozcProtobufCodec.Entry("い", "位", "comment", MozcProtobufCodec.POS_NOUN)));

        byte[] encoded = MozcProtobufCodec.encodeStorage(List.of(dict1, dict2));
        var decoded = MozcProtobufCodec.decodeStorage(encoded);

        assertEquals(2, decoded.size());
        assertEquals("dict-1", decoded.get(0).name());
        assertEquals("dict-2", decoded.get(1).name());
        assertEquals("comment", decoded.get(1).entries().get(0).comment());
    }

    @Test
    void decodesRealMozcFile() {
        // The actual bytes from a real Mozc user_dictionary.db
        byte[] realData = {
                0x12, 0x2a,
                0x08, (byte)0x90, (byte)0x93, (byte)0xff, (byte)0x95,
                (byte)0xc7, (byte)0xee, (byte)0x84, (byte)0xc7, 0x7e,
                0x1a, 0x14,
                (byte)0xe3, (byte)0x83, (byte)0xa6, (byte)0xe3, (byte)0x83, (byte)0xbc,
                (byte)0xe3, (byte)0x82, (byte)0xb6, (byte)0xe3, (byte)0x83, (byte)0xbc,
                (byte)0xe8, (byte)0xbe, (byte)0x9e, (byte)0xe6, (byte)0x9b, (byte)0xb8,
                0x20, 0x31,
                0x22, 0x08,
                0x0a, 0x00, 0x12, 0x00, 0x22, 0x00, 0x28, 0x01
        };

        var dicts = MozcProtobufCodec.decodeStorage(realData);
        assertEquals(1, dicts.size());
        assertEquals("ユーザー辞書 1", dicts.get(0).name());
        assertEquals(1, dicts.get(0).entries().size());
        assertEquals(MozcProtobufCodec.POS_NOUN, dicts.get(0).entries().get(0).pos());
    }

    @Test
    void fileRoundTrip() throws IOException {
        Path file = tempDir.resolve("user_dictionary.db");
        var dict = new MozcProtobufCodec.Dictionary(99L, "テスト辞書",
                List.of(new MozcProtobufCodec.Entry("でぷろいめんと", "デプロイメント", "predict", MozcProtobufCodec.POS_NOUN)));

        MozcProtobufCodec.writeFile(file, List.of(dict));
        assertTrue(Files.exists(file));

        var read = MozcProtobufCodec.readFile(file);
        assertEquals(1, read.size());
        assertEquals("テスト辞書", read.get(0).name());
        assertEquals("でぷろいめんと", read.get(0).entries().get(0).key());
        assertEquals("デプロイメント", read.get(0).entries().get(0).value());
        assertEquals("predict", read.get(0).entries().get(0).comment());
    }

    @Test
    void varintEncoding() {
        // Test small value
        var out = new ByteArrayOutputStream();
        MozcProtobufCodec.writeVarint(out, 1);
        assertArrayEquals(new byte[]{0x01}, out.toByteArray());

        // Test multi-byte
        out = new ByteArrayOutputStream();
        MozcProtobufCodec.writeVarint(out, 300);
        // 300 = 0x12C -> varint: AC 02
        assertArrayEquals(new byte[]{(byte)0xAC, 0x02}, out.toByteArray());
    }

    @Test
    void emptyFileReturnsEmptyList() throws IOException {
        Path file = tempDir.resolve("nonexistent.db");
        var dicts = MozcProtobufCodec.readFile(file);
        assertTrue(dicts.isEmpty());
    }
}
