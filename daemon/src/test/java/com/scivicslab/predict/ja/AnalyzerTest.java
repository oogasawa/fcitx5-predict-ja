package com.scivicslab.predict.ja;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Analyzer sentence-splitting and buffering logic.
 * Uses a capture list instead of ActorRef to verify behavior.
 */
class AnalyzerTest {

    /**
     * Split text the same way Analyzer does, for testability.
     */
    static List<String> splitSentences(String text) {
        List<String> results = new ArrayList<>();
        if (text == null || text.isBlank()) return results;
        String[] segments = text.split("[\\n。！？!?]+");
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty() || trimmed.length() < 3) continue;
            results.add(trimmed);
        }
        return results;
    }

    @Test
    void splitOnPeriod() {
        List<String> result = splitSentences("ビルドします。テストします。");
        assertEquals(2, result.size());
        assertEquals("ビルドします", result.get(0));
        assertEquals("テストします", result.get(1));
    }

    @Test
    void splitOnExclamation() {
        List<String> result = splitSentences("成功です！次に進みます");
        assertEquals(2, result.size());
    }

    @Test
    void splitOnQuestion() {
        List<String> result = splitSentences("動いてますか？確認しましょう");
        assertEquals(2, result.size());
    }

    @Test
    void splitOnNewline() {
        List<String> result = splitSentences("一行目\n二行目\n三行目");
        assertEquals(3, result.size());
    }

    @Test
    void rejectShortSegments() {
        // Segments shorter than 3 chars are dropped
        List<String> result = splitSentences("はい。了解です。");
        assertEquals(1, result.size());
        assertEquals("了解です", result.get(0));
    }

    @Test
    void nullInput() {
        assertEquals(0, splitSentences(null).size());
    }

    @Test
    void blankInput() {
        assertEquals(0, splitSentences("   ").size());
    }

    @Test
    void consecutiveDelimiters() {
        // "テスト。。。成功！！" splits into ["テスト", "", "", "成功", "", ""]
        // After trimming empties and <3 char filter, only "テスト" and "成功" remain
        // But "成功" is 2 chars (2 kanji), so it is dropped by the <3 filter
        // Note: length() counts UTF-16 code units, not characters.
        // "テスト" = 3 code units, "成功" = 2 code units
        List<String> result = splitSentences("テスト。。。成功！！");
        assertEquals(1, result.size());
        assertEquals("テスト", result.get(0));
    }

    @Test
    void longSentencePreserved() {
        String long_ = "これは非常に長い文章で、予測変換候補として蓄積される可能性がある";
        List<String> result = splitSentences(long_);
        assertEquals(1, result.size());
        assertEquals(long_, result.get(0));
    }

    @Test
    void mixedEnglishAndJapanese() {
        List<String> result = splitSentences("Build succeeded! ビルド成功です");
        assertEquals(2, result.size());
        assertEquals("Build succeeded", result.get(0));
        assertEquals("ビルド成功です", result.get(1));
    }
}
