package com.scivicslab.predict.ja;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntryFilterTest {

    @Test
    void katakanaToHiragana() {
        assertEquals("でぷろいめんと", EntryFilter.katakanaToHiragana("デプロイメント"));
        assertEquals("ぽっど", EntryFilter.katakanaToHiragana("ポッド"));
        // Mixed: katakana + hiragana
        assertEquals("かたかなひらがな", EntryFilter.katakanaToHiragana("カタカナひらがな"));
        // Prolonged sound mark preserved
        assertEquals("さーばー", EntryFilter.katakanaToHiragana("サーバー"));
    }

    @Test
    void acceptValidEntry() {
        // Hiragana reading -> kanji candidate
        assertNotNull(EntryFilter.filter("でぷろいめんと", "デプロイメント"));
        assertEquals("でぷろいめんと", EntryFilter.filter("でぷろいめんと", "デプロイメント"));
    }

    @Test
    void rejectRawIdentity() {
        // Raw reading == raw candidate → rejected before normalization
        // This is the common case from IME plugin (sends committed text as both)
        assertNull(EntryFilter.filter("デプロイメント", "デプロイメント"));
        assertNull(EntryFilter.filter("テスト", "テスト"));
    }

    @Test
    void acceptHiraganaToKatakana() {
        // Hiragana reading → katakana candidate is a valid conversion
        assertEquals("でぷろいめんと", EntryFilter.filter("でぷろいめんと", "デプロイメント"));
    }

    @Test
    void acceptKatakanaReadingWithKanjiCandidate() {
        // Katakana reading with kanji candidate should pass
        assertEquals("にゅうりょく", EntryFilter.filter("ニュウリョク", "入力"));
    }

    @Test
    void rejectSingleChar() {
        assertNull(EntryFilter.filter("の", "の"));
        assertNull(EntryFilter.filter("あ", "亜"));
    }

    @Test
    void rejectPunctuation() {
        assertNull(EntryFilter.filter("、", "、"));
        assertNull(EntryFilter.filter("。", "。"));
        assertNull(EntryFilter.filter("？", "？"));
    }

    @Test
    void rejectParticles() {
        assertNull(EntryFilter.filter("を", "を"));
        assertNull(EntryFilter.filter("が", "が"));
    }

    @Test
    void rejectIdentityConversion() {
        // Same after normalization → no conversion value
        assertNull(EntryFilter.filter("れぷりか", "れぷりか"));
        // Katakana reading, katakana candidate — both normalize to same hiragana
        assertNull(EntryFilter.filter("レプリカ", "レプリカ"));
    }

    @Test
    void rejectLongSentence() {
        String longReading = "ひょうきもかわるようにしてもらえませんでしょうか";
        assertNull(EntryFilter.filter(longReading, "表記も変わるようにしてもらえませんでしょうか"));
    }

    @Test
    void rejectNonHiraganaReading() {
        // ASCII in reading
        assertNull(EntryFilter.filter("test", "テスト"));
        // Kanji in reading
        assertNull(EntryFilter.filter("入力", "入力"));
    }

    @Test
    void rejectBlank() {
        assertNull(EntryFilter.filter("", "something"));
        assertNull(EntryFilter.filter("あいう", ""));
        assertNull(EntryFilter.filter(null, "something"));
    }

    @Test
    void isAllHiragana() {
        assertTrue(EntryFilter.isAllHiragana("ひらがな"));
        assertTrue(EntryFilter.isAllHiragana("さーばー")); // prolonged sound mark ok
        assertFalse(EntryFilter.isAllHiragana("カタカナ"));
        assertFalse(EntryFilter.isAllHiragana("abc"));
        assertFalse(EntryFilter.isAllHiragana("漢字"));
    }
}
