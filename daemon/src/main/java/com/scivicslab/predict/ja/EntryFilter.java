package com.scivicslab.predict.ja;

import java.util.Set;

/**
 * Filters and normalizes dictionary entries before they are stored
 * in the knowledge base. Rejects noise (punctuation, particles,
 * identity conversions, sentence-length entries) and converts
 * katakana readings to hiragana.
 */
public class EntryFilter {

    private static final Set<String> REJECTED_TOKENS = Set.of(
            "の", "で", "は", "を", "が", "に", "と", "も", "へ", "や",
            "か", "な", "よ", "ね", "わ", "て", "た",
            "、", "。", "！", "？", "!", "?",
            "「", "」", "（", "）", "(", ")", "・",
            "k"
    );

    private static final int MIN_READING_LENGTH = 2;
    private static final int MAX_READING_LENGTH = 40;

    /**
     * Convert katakana characters in the string to hiragana.
     * Full-width katakana U+30A1..U+30F6 -> hiragana U+3041..U+3096.
     * Prolonged sound mark (ー U+30FC) is preserved as-is.
     */
    public static String katakanaToHiragana(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u30A1' && c <= '\u30F6') {
                sb.append((char) (c - 0x60));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Check if a string consists only of hiragana (U+3040..U+309F)
     * and prolonged sound mark (ー U+30FC).
     */
    public static boolean isAllHiragana(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '\u3040' && c <= '\u309F') || c == '\u30FC') {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Validate an entry after normalization.
     * Returns true if the entry should be kept.
     */
    public static boolean isValid(String normalizedReading, String candidate) {
        if (normalizedReading == null || candidate == null) return false;
        if (normalizedReading.isBlank() || candidate.isBlank()) return false;

        // Too short
        if (normalizedReading.length() < MIN_READING_LENGTH) return false;

        // Too long (sentence-level)
        if (normalizedReading.length() > MAX_READING_LENGTH) return false;

        // Common particles / punctuation
        if (REJECTED_TOKENS.contains(normalizedReading)) return false;
        if (REJECTED_TOKENS.contains(candidate)) return false;

        // Identity conversion (no value): reject if reading and candidate
        // are literally the same string. Hiragana->katakana (e.g. でぷろいめんと->デプロイメント)
        // is a valid conversion and should be kept.
        if (normalizedReading.equals(candidate)) return false;

        // Reading must be all hiragana (after katakana normalization)
        if (!isAllHiragana(normalizedReading)) return false;

        return true;
    }

    /**
     * Normalize the reading and validate the entry.
     * Returns the normalized hiragana reading, or null if the entry should be rejected.
     */
    public static String filter(String reading, String candidate) {
        if (reading == null || candidate == null) return null;
        String rawReading = reading.trim();
        String rawCandidate = candidate.trim();
        // Reject if raw input is identical (e.g. "レプリカ" -> "レプリカ")
        if (rawReading.equals(rawCandidate)) return null;
        String normalized = katakanaToHiragana(rawReading);
        if (isValid(normalized, rawCandidate)) {
            return normalized;
        }
        return null;
    }
}
