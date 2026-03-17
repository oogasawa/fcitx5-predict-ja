package com.scivicslab.predict.ja;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class LlmConsoleSourceTest {

    @Test
    void parseGatewayResponse_normal() {
        String json = """
                [{"server":"claude-8200","role":"user","content":"Hello"},{"server":"claude-8200","role":"assistant","content":"Hi there"}]
                """;
        var entries = LlmConsoleSource.parseGatewayResponse(json);
        assertEquals(2, entries.size());
        assertEquals("claude-8200", entries.get(0).server());
        assertEquals("user", entries.get(0).role());
        assertEquals("Hello", entries.get(0).content());
        assertEquals("assistant", entries.get(1).role());
        assertEquals("Hi there", entries.get(1).content());
    }

    @Test
    void parseGatewayResponse_empty() {
        assertEquals(0, LlmConsoleSource.parseGatewayResponse("[]").size());
        assertEquals(0, LlmConsoleSource.parseGatewayResponse("").size());
        assertEquals(0, LlmConsoleSource.parseGatewayResponse(null).size());
    }

    @Test
    void parseGatewayResponse_escapedContent() {
        String json = """
                [{"server":"s1","role":"user","content":"line1\\nline2"}]
                """;
        var entries = LlmConsoleSource.parseGatewayResponse(json);
        assertEquals(1, entries.size());
        assertEquals("line1\nline2", entries.get(0).content());
    }

    @Test
    void parseGatewayResponse_japaneseContent() {
        String json = """
                [{"server":"codex-8201","role":"user","content":"日本語のテスト"},{"server":"codex-8201","role":"assistant","content":"応答です"}]
                """;
        var entries = LlmConsoleSource.parseGatewayResponse(json);
        assertEquals(2, entries.size());
        assertEquals("日本語のテスト", entries.get(0).content());
        assertEquals("応答です", entries.get(1).content());
    }

    @Test
    void parseGatewayResponse_multipleServers() {
        String json = """
                [{"server":"claude-8200","role":"user","content":"A"},{"server":"codex-8201","role":"user","content":"B"},{"server":"claude-8200","role":"assistant","content":"C"}]
                """;
        var entries = LlmConsoleSource.parseGatewayResponse(json);
        assertEquals(3, entries.size());
        assertEquals("claude-8200", entries.get(0).server());
        assertEquals("codex-8201", entries.get(1).server());
        assertEquals("claude-8200", entries.get(2).server());
    }

    @Test
    void extractField_simple() {
        assertEquals("user", LlmConsoleSource.extractField("{\"role\":\"user\"}", "role"));
        assertEquals("s1", LlmConsoleSource.extractField("{\"server\":\"s1\"}", "server"));
        assertNull(LlmConsoleSource.extractField("{\"role\":\"user\"}", "missing"));
    }
}
