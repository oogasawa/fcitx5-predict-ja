package com.scivicslab.predict.ja;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadingResolverTest {

    private final ReadingResolver resolver = new ReadingResolver();

    @Test
    void resolveKanjiWord() {
        assertEquals("けんげん", resolver.resolve("権限"));
    }

    @Test
    void resolveKanjiCompound() {
        assertEquals("へいれつしょり", resolver.resolve("並列処理"));
    }

    @Test
    void resolveKatakana() {
        assertEquals("でぷろいめんと", resolver.resolve("デプロイメント"));
    }

    @Test
    void resolveKatakanaWord() {
        assertEquals("こんてな", resolver.resolve("コンテナ"));
    }

    @Test
    void resolveMixedKanjiKatakana() {
        // Kanji + katakana compound
        String reading = resolver.resolve("設定ファイル");
        assertNotNull(reading);
        assertTrue(reading.startsWith("せってい"));
    }

    @Test
    void resolveCommonWords() {
        assertEquals("かくにん", resolver.resolve("確認"));
        assertEquals("じっこう", resolver.resolve("実行"));
        assertEquals("かんり", resolver.resolve("管理"));
        assertEquals("つうち", resolver.resolve("通知"));
        assertEquals("さぎょう", resolver.resolve("作業"));
    }

    @Test
    void resolveNull() {
        assertNull(resolver.resolve(null));
        assertNull(resolver.resolve(""));
        assertNull(resolver.resolve("   "));
    }

    @Test
    void resolveAsciiOnly() {
        // Pure ASCII should return null (no Japanese reading)
        assertNull(resolver.resolve("Kubernetes"));
    }

    @Test
    void resolveHiragana() {
        // Already hiragana: returns as-is
        assertEquals("ひらがな", resolver.resolve("ひらがな"));
    }
}
