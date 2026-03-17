package com.scivicslab.predict.ja;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;

import java.util.List;
import java.util.logging.Logger;

/**
 * Resolves the hiragana reading of a Japanese phrase using kuromoji
 * morphological analysis. This replaces LLM-generated readings which
 * are unreliable (e.g. LLM outputs "ごんり" for "権限").
 */
public class ReadingResolver {

    private static final Logger LOG = Logger.getLogger(ReadingResolver.class.getName());

    private final Tokenizer tokenizer;

    public ReadingResolver() {
        this.tokenizer = new Tokenizer();
    }

    /**
     * Resolve the hiragana reading for a given Japanese phrase.
     * Returns null if no reading can be determined (e.g. ASCII-only input).
     *
     * @param phrase the phrase to resolve (e.g. "権限", "デプロイメント")
     * @return hiragana reading (e.g. "けんげん", "でぷろいめんと"), or null
     */
    public String resolve(String phrase) {
        if (phrase == null || phrase.isBlank()) return null;

        List<Token> tokens = tokenizer.tokenize(phrase);
        StringBuilder reading = new StringBuilder();

        for (Token token : tokens) {
            String tokenReading = token.getReading();
            if (tokenReading == null || tokenReading.equals("*")) {
                // Unknown word: try to use surface form
                String surface = token.getSurface();
                if (EntryFilter.isAllHiragana(surface)) {
                    reading.append(surface);
                } else if (isAllKatakana(surface)) {
                    // Katakana loanwords not in IPAdic: convert directly
                    reading.append(EntryFilter.katakanaToHiragana(surface));
                } else {
                    // Cannot determine reading (ASCII, unknown kanji, etc.)
                    LOG.fine("No reading for token: " + surface);
                    return null;
                }
            } else {
                // kuromoji returns katakana reading; convert to hiragana
                reading.append(EntryFilter.katakanaToHiragana(tokenReading));
            }
        }

        String result = reading.toString();
        return result.isEmpty() ? null : result;
    }

    /**
     * Check if a string consists only of katakana (U+30A1..U+30F6)
     * and prolonged sound mark (ー U+30FC).
     */
    private static boolean isAllKatakana(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '\u30A1' && c <= '\u30F6') || c == '\u30FC') {
                continue;
            }
            return false;
        }
        return true;
    }
}
