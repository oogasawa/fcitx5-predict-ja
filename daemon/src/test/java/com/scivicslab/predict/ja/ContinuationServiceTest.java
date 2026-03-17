package com.scivicslab.predict.ja;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ContinuationService parsing and conversation buffer logic.
 * Network calls (callVllm) are not tested here.
 */
class ContinuationServiceTest {

    // Create instance with dummy URL/model (no actual HTTP calls in these tests)
    private ContinuationService createService() {
        return new ContinuationService("http://localhost:0", "test-model");
    }

    // Use reflection to call private parseResponse
    private List<String> callParseResponse(ContinuationService service, String response) throws Exception {
        Method m = ContinuationService.class.getDeclaredMethod("parseResponse", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) m.invoke(service, response);
        return result;
    }

    // Use reflection to call private extractContent
    private String callExtractContent(ContinuationService service, String json) throws Exception {
        Method m = ContinuationService.class.getDeclaredMethod("extractContent", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, json);
    }

    // Use reflection to call private escapeJsonValue
    private String callEscapeJsonValue(String s) throws Exception {
        Method m = ContinuationService.class.getDeclaredMethod("escapeJsonValue", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    @Test
    void parseResponseBasic() throws Exception {
        var svc = createService();
        List<String> result = callParseResponse(svc,
                "コンテナを再起動する\nビルドを実行する\n設定を確認する");
        assertEquals(3, result.size());
        assertEquals("コンテナを再起動する", result.get(0));
    }

    @Test
    void parseResponseStripsNumbering() throws Exception {
        var svc = createService();
        List<String> result = callParseResponse(svc,
                "1. コンテナを再起動する\n2) ビルドを実行する\n3 設定を確認する");
        assertEquals(3, result.size());
        assertEquals("コンテナを再起動する", result.get(0));
        assertEquals("ビルドを実行する", result.get(1));
        assertEquals("設定を確認する", result.get(2));
    }

    @Test
    void parseResponseStripsBullets() throws Exception {
        var svc = createService();
        List<String> result = callParseResponse(svc,
                "- コンテナを再起動する\n・ビルドを実行する");
        assertEquals(2, result.size());
        assertEquals("コンテナを再起動する", result.get(0));
        assertEquals("ビルドを実行する", result.get(1));
    }

    @Test
    void parseResponseRejectsLongLines() throws Exception {
        var svc = createService();
        String longLine = "あ".repeat(101);
        List<String> result = callParseResponse(svc, longLine + "\n短い行");
        assertEquals(1, result.size());
        assertEquals("短い行", result.get(0));
    }

    @Test
    void parseResponseEmptyLines() throws Exception {
        var svc = createService();
        List<String> result = callParseResponse(svc, "\n\n\nテスト\n\n");
        assertEquals(1, result.size());
    }

    @Test
    void extractContentFromChatCompletion() throws Exception {
        var svc = createService();
        String json = """
                {"id":"x","choices":[{"message":{"role":"assistant","content":"hello world"}}]}""";
        assertEquals("hello world", callExtractContent(svc, json));
    }

    @Test
    void extractContentWithEscapedNewlines() throws Exception {
        var svc = createService();
        String json = """
                {"choices":[{"message":{"content":"line1\\nline2"}}]}""";
        assertEquals("line1\nline2", callExtractContent(svc, json));
    }

    @Test
    void extractContentWithEscapedQuotes() throws Exception {
        var svc = createService();
        String json = """
                {"choices":[{"message":{"content":"he said \\"hello\\""}}]}""";
        assertEquals("he said \"hello\"", callExtractContent(svc, json));
    }

    @Test
    void extractContentEmpty() throws Exception {
        var svc = createService();
        assertEquals("", callExtractContent(svc, "{\"error\":\"bad\"}"));
    }

    @Test
    void escapeJsonValueHandlesSpecialChars() throws Exception {
        assertEquals("hello", callEscapeJsonValue("hello"));
        assertEquals("line1\\nline2", callEscapeJsonValue("line1\nline2"));
        assertEquals("tab\\there", callEscapeJsonValue("tab\there"));
        assertEquals("quote\\\"here", callEscapeJsonValue("quote\"here"));
        assertEquals("back\\\\slash", callEscapeJsonValue("back\\slash"));
    }

    @Test
    void appendConversationBasic() {
        var svc = createService();
        svc.appendConversation("user", "hello");
        svc.appendConversation("assistant", "hi there");
        // generate with blank input returns empty
        assertEquals(0, svc.generate(null, 3).size());
        assertEquals(0, svc.generate("", 3).size());
    }

    @Test
    void appendConversationTrimsToMaxLength() {
        var svc = createService();
        // Append a lot of text to exceed MAX_CONVERSATION_LENGTH (2000)
        for (int i = 0; i < 200; i++) {
            svc.appendConversation("user", "This is message number " + i + " with some padding text.");
        }
        // Should not throw, conversation buffer is trimmed
        svc.appendConversation("user", "final message");
    }

    @Test
    void appendConversationIgnoresBlank() {
        var svc = createService();
        svc.appendConversation("user", null);
        svc.appendConversation("user", "  ");
        // No exception
    }
}
