package com.scivicslab.predict.ja;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordHandlerTest {

    @Test
    void extractJsonField() {
        String json = """
                {"input": "きょう", "output": "今日", "context": "明日は"}
                """;

        assertEquals("きょう", RecordHandler.extractJsonField(json, "input"));
        assertEquals("今日", RecordHandler.extractJsonField(json, "output"));
        assertEquals("明日は", RecordHandler.extractJsonField(json, "context"));
    }

    @Test
    void extractJsonFieldMissing() {
        String json = """
                {"input": "test"}
                """;
        assertNull(RecordHandler.extractJsonField(json, "missing"));
    }

    @Test
    void extractJsonFieldWithEscapedQuotes() {
        String json = """
                {"input": "hello \\"world\\""}
                """;
        assertEquals("hello \"world\"", RecordHandler.extractJsonField(json, "input"));
    }
}
