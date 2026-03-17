package com.scivicslab.predict.ja;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Minimal protobuf codec for Mozc user_dictionary.db.
 *
 * Wire format reference (only two wire types used):
 *   varint  (wire type 0): tag = (field << 3) | 0
 *   length-delimited (wire type 2): tag = (field << 3) | 2, then varint length, then bytes
 *
 * Mozc proto structure:
 *   UserDictionaryStorage { repeated UserDictionary dictionaries = 2; }
 *   UserDictionary { uint64 id = 1; string name = 3; repeated Entry entries = 4; }
 *   Entry { string key = 1; string value = 2; string comment = 4; uint32 pos = 5; }
 */
public class MozcProtobufCodec {

    private static final Logger LOG = Logger.getLogger(MozcProtobufCodec.class.getName());

    // POS enum values used by Mozc
    public static final int POS_NOUN = 1;
    public static final int POS_VERB = 7;
    public static final int POS_ADJECTIVE = 10;

    /** A single entry in a Mozc user dictionary. */
    public record Entry(String key, String value, String comment, int pos) {}

    /** A named dictionary with an id and entries. */
    public record Dictionary(long id, String name, List<Entry> entries) {}

    // --- Encoding ---

    /** Encode a complete UserDictionaryStorage to bytes. */
    public static byte[] encodeStorage(List<Dictionary> dictionaries) {
        try {
            var out = new ByteArrayOutputStream();
            for (var dict : dictionaries) {
                byte[] dictBytes = encodeDictionary(dict);
                writeTag(out, 2, 2); // field 2, wire type 2
                writeVarint(out, dictBytes.length);
                out.write(dictBytes);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode storage", e);
        }
    }

    private static byte[] encodeDictionary(Dictionary dict) throws IOException {
        var out = new ByteArrayOutputStream();
        // field 1: id (varint)
        writeTag(out, 1, 0);
        writeVarint(out, dict.id());
        // field 3: name (string)
        writeString(out, 3, dict.name());
        // field 4: repeated entries
        for (var entry : dict.entries()) {
            byte[] entryBytes = encodeEntry(entry);
            writeTag(out, 4, 2);
            writeVarint(out, entryBytes.length);
            out.write(entryBytes);
        }
        return out.toByteArray();
    }

    private static byte[] encodeEntry(Entry entry) throws IOException {
        var out = new ByteArrayOutputStream();
        writeString(out, 1, entry.key());   // reading
        writeString(out, 2, entry.value()); // candidate
        writeString(out, 4, entry.comment());
        // field 5: pos (varint)
        writeTag(out, 5, 0);
        writeVarint(out, entry.pos());
        return out.toByteArray();
    }

    private static void writeTag(ByteArrayOutputStream out, int field, int wireType) throws IOException {
        writeVarint(out, (field << 3) | wireType);
    }

    private static void writeString(ByteArrayOutputStream out, int field, String value) throws IOException {
        byte[] bytes = (value != null ? value : "").getBytes(StandardCharsets.UTF_8);
        writeTag(out, field, 2);
        writeVarint(out, bytes.length);
        out.write(bytes);
    }

    static void writeVarint(ByteArrayOutputStream out, long value) {
        // Unsigned varint encoding
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) (v & 0x7F));
    }

    // --- Decoding ---

    /** Decode a UserDictionaryStorage from bytes. */
    public static List<Dictionary> decodeStorage(byte[] data) {
        var dictionaries = new ArrayList<Dictionary>();
        int pos = 0;
        while (pos < data.length) {
            int[] tagResult = readTag(data, pos);
            int field = tagResult[0];
            int wireType = tagResult[1];
            pos = tagResult[2];

            if (wireType == 2) { // length-delimited
                long[] lenResult = readVarint(data, pos);
                int len = (int) lenResult[0];
                pos = (int) lenResult[1];
                if (field == 2) {
                    dictionaries.add(decodeDictionary(data, pos, pos + len));
                }
                pos += len;
            } else if (wireType == 0) { // varint (skip)
                long[] vr = readVarint(data, pos);
                pos = (int) vr[1];
            }
        }
        return dictionaries;
    }

    private static Dictionary decodeDictionary(byte[] data, int start, int end) {
        long id = 0;
        String name = "";
        var entries = new ArrayList<Entry>();
        int pos = start;

        while (pos < end) {
            int[] tagResult = readTag(data, pos);
            int field = tagResult[0];
            int wireType = tagResult[1];
            pos = tagResult[2];

            if (wireType == 0) {
                long[] vr = readVarint(data, pos);
                pos = (int) vr[1];
                if (field == 1) id = vr[0];
            } else if (wireType == 2) {
                long[] lenResult = readVarint(data, pos);
                int len = (int) lenResult[0];
                pos = (int) lenResult[1];
                if (field == 3) {
                    name = new String(data, pos, len, StandardCharsets.UTF_8);
                } else if (field == 4) {
                    entries.add(decodeEntry(data, pos, pos + len));
                }
                pos += len;
            }
        }
        return new Dictionary(id, name, entries);
    }

    private static Entry decodeEntry(byte[] data, int start, int end) {
        String key = "", value = "", comment = "";
        int posType = POS_NOUN;
        int pos = start;

        while (pos < end) {
            int[] tagResult = readTag(data, pos);
            int field = tagResult[0];
            int wireType = tagResult[1];
            pos = tagResult[2];

            if (wireType == 0) {
                long[] vr = readVarint(data, pos);
                pos = (int) vr[1];
                if (field == 5) posType = (int) vr[0];
            } else if (wireType == 2) {
                long[] lenResult = readVarint(data, pos);
                int len = (int) lenResult[0];
                pos = (int) lenResult[1];
                String s = new String(data, pos, len, StandardCharsets.UTF_8);
                switch (field) {
                    case 1 -> key = s;
                    case 2 -> value = s;
                    case 4 -> comment = s;
                }
                pos += len;
            }
        }
        return new Entry(key, value, comment, posType);
    }

    private static int[] readTag(byte[] data, int pos) {
        long[] vr = readVarint(data, pos);
        int tag = (int) vr[0];
        return new int[]{tag >> 3, tag & 0x7, (int) vr[1]};
    }

    static long[] readVarint(byte[] data, int pos) {
        long result = 0;
        int shift = 0;
        int p = pos;
        while (p < data.length) {
            byte b = data[p++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return new long[]{result, p};
    }

    // --- File I/O convenience ---

    /** Read and decode a Mozc user_dictionary.db file. */
    public static List<Dictionary> readFile(Path path) throws IOException {
        if (!Files.exists(path)) return new ArrayList<>();
        return decodeStorage(Files.readAllBytes(path));
    }

    /** Encode and write dictionaries to a Mozc user_dictionary.db file. */
    public static void writeFile(Path path, List<Dictionary> dictionaries) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, encodeStorage(dictionaries));
    }

    /** Map internal category string to Mozc POS enum. */
    public static int categoryToPos(String category) {
        return switch (category) {
            case "verb" -> POS_VERB;
            case "adjective" -> POS_ADJECTIVE;
            default -> POS_NOUN;
        };
    }
}
