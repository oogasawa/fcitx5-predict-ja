package com.scivicslab.predict.ja;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PredictDaemon utility methods: JSON extraction, query parsing, escaping.
 */
class PredictDaemonUtilTest {

    private String callExtractJsonString(String json, String key) throws Exception {
        Method m = PredictDaemon.class.getDeclaredMethod("extractJsonString", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, json, key);
    }

    private String callEscapeJson(String s) throws Exception {
        Method m = PredictDaemon.class.getDeclaredMethod("escapeJson", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    @Test
    void extractJsonStringBasic() throws Exception {
        String json = """
                {"input":"hello","output":"world"}""";
        assertEquals("hello", callExtractJsonString(json, "input"));
        assertEquals("world", callExtractJsonString(json, "output"));
    }

    @Test
    void extractJsonStringMissing() throws Exception {
        assertNull(callExtractJsonString("{\"a\":\"b\"}", "missing"));
    }

    @Test
    void extractJsonStringWithSpaces() throws Exception {
        String json = """
                { "input" : "spaced value" }""";
        assertEquals("spaced value", callExtractJsonString(json, "input"));
    }

    @Test
    void extractJsonStringNumeric() throws Exception {
        // extractJsonString looks for quotes around value — numbers won't match
        String json = """
                {"n":5,"text":"abc"}""";
        assertEquals("abc", callExtractJsonString(json, "text"));
    }

    @Test
    void escapeJsonBackslash() throws Exception {
        assertEquals("a\\\\b", callEscapeJson("a\\b"));
    }

    @Test
    void escapeJsonQuotes() throws Exception {
        assertEquals("say \\\"hi\\\"", callEscapeJson("say \"hi\""));
    }

    @Test
    void escapeJsonNewlines() throws Exception {
        assertEquals("line1\\nline2", callEscapeJson("line1\nline2"));
    }

    @Test
    void escapeJsonCarriageReturn() throws Exception {
        assertEquals("a\\rb", callEscapeJson("a\rb"));
    }

    @Test
    void escapeJsonPlainText() throws Exception {
        assertEquals("hello world", callEscapeJson("hello world"));
    }
}
