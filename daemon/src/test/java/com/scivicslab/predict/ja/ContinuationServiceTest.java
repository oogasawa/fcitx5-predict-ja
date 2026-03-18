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

    // =========================================================================
    // stripInputRepetition / stripOneCandidate tests
    // =========================================================================

    // --- Overlap detection (context suffix = candidate prefix) ---

    @Test
    void stripOverlap_exactContextEndsWithExclamation() {
        // Context ends with "技があるんですね！", candidate echoes it
        String ctx = "英語で指示を書くという技があるんですね！";
        String candidate = "英語で指示を書くという技があるんですね！指示の構造を変えてみました。";
        String result = ContinuationService.stripOneCandidate(candidate, ctx);
        assertEquals("指示の構造を変えてみました。", result);
    }

    @Test
    void stripOverlap_partialSuffix() {
        // Only the last part of context matches candidate start
        String ctx = "今日は天気が良い。散歩に行こう";
        String candidate = "散歩に行こうと思って靴を履いた";
        String result = ContinuationService.stripOneCandidate(candidate, ctx);
        assertEquals("と思って靴を履いた", result);
    }

    @Test
    void stripOverlap_fullContextRepeated() {
        String ctx = "テスト";
        String candidate = "テスト結果を確認する";
        String result = ContinuationService.stripOneCandidate(candidate, ctx);
        assertEquals("結果を確認する", result);
    }

    @Test
    void stripOverlap_noOverlap() {
        String ctx = "今日は天気が良い";
        String candidate = "明日は雨らしい";
        String result = ContinuationService.stripOneCandidate(candidate, ctx);
        assertEquals("明日は雨らしい", result);
    }

    @Test
    void stripOverlap_candidateIsEntirelyOverlap() {
        // Candidate is just a repeat of context end, nothing left
        String ctx = "テストが終わった";
        String candidate = "テストが終わった";
        String result = ContinuationService.stripOneCandidate(candidate, ctx);
        assertEquals("", result);
    }

    @Test
    void stripOverlap_longContext_shortOverlap() {
        String ctx = "これは非常に長いコンテキストです。色々なことを書きました。最後に質問";
        String candidate = "質問があります";
        String result = ContinuationService.stripOneCandidate(candidate, ctx);
        assertEquals("があります", result);
    }

    @Test
    void stripOverlap_questionMark() {
        String ctx = "これはどうですか？";
        String candidate = "これはどうですか？具体的には";
        String result = ContinuationService.stripOneCandidate(candidate, ctx);
        assertEquals("具体的には", result);
    }

    @Test
    void stripOverlap_period() {
        String ctx = "設定を変更しました。";
        String candidate = "設定を変更しました。その結果";
        String result = ContinuationService.stripOneCandidate(candidate, ctx);
        assertEquals("その結果", result);
    }

    // --- Sentence-boundary fragment detection ---

    @Test
    void stripFragment_afterPeriod() {
        // Context has sentence boundary, candidate starts with fragment after it
        String ctx = "最初の設定。次にビルドを実行";
        String candidate = "次にビルドを実行して確認する";
        String result = ContinuationService.stripOneCandidate(candidate, ctx);
        assertEquals("して確認する", result);
    }

    @Test
    void stripFragment_afterExclamation() {
        String ctx = "すごい！これで動くはず";
        String candidate = "これで動くはずだけど確認が必要";
        String result = ContinuationService.stripOneCandidate(candidate, ctx);
        assertEquals("だけど確認が必要", result);
    }

    @Test
    void stripFragment_afterQuestion() {
        String ctx = "本当に大丈夫？念のため確認";
        String candidate = "念のため確認してみましょう";
        String result = ContinuationService.stripOneCandidate(candidate, ctx);
        assertEquals("してみましょう", result);
    }

    @Test
    void stripFragment_afterNewline() {
        String ctx = "一行目\n二行目の内容";
        String candidate = "二行目の内容を修正する";
        String result = ContinuationService.stripOneCandidate(candidate, ctx);
        assertEquals("を修正する", result);
    }

    // --- stripInputRepetition (list-level) ---

    @Test
    void stripList_removesEmptyCandidatesAfterStrip() {
        String ctx = "テスト完了";
        List<String> candidates = List.of(
                "テスト完了",  // entirely overlap -> removed
                "テスト完了しました",  // overlap -> "しました"
                "別の話題"  // no overlap -> kept as-is
        );
        List<String> result = ContinuationService.stripInputRepetition(candidates, ctx);
        assertEquals(2, result.size());
        assertEquals("しました", result.get(0));
        assertEquals("別の話題", result.get(1));
    }

    @Test
    void stripList_nullInput() {
        List<String> candidates = List.of("テスト");
        List<String> result = ContinuationService.stripInputRepetition(candidates, null);
        assertEquals(1, result.size());
    }

    @Test
    void stripList_blankInput() {
        List<String> candidates = List.of("テスト");
        List<String> result = ContinuationService.stripInputRepetition(candidates, "  ");
        assertEquals(1, result.size());
    }

    @Test
    void stripList_realWorldCase_chatInput() {
        // Simulating the reported bug: user typed in chat, candidate echoes it
        String ctx = "どうかな？予測入力は...をだいぶ改善されました。英語で指示を書くという技があるんですね！";
        List<String> candidates = List.of(
                "英語で指示を書くという技があるんですね！指示の構造を分けてみました。",
                "具体的にはシステムプロンプトを英語にする方法です",
                "英語で指示を書くという技があるんですね！他にも工夫があります"
        );
        List<String> result = ContinuationService.stripInputRepetition(candidates, ctx);
        assertEquals(3, result.size());
        assertEquals("指示の構造を分けてみました。", result.get(0));
        assertEquals("具体的にはシステムプロンプトを英語にする方法です", result.get(1));
        assertEquals("他にも工夫があります", result.get(2));
    }

    @Test
    void stripList_realWorldCase_incompletePhrase() {
        // User typed an incomplete phrase, candidate continues from overlap
        String ctx = "パソコンが動かなくなって";
        List<String> candidates = List.of(
                "パソコンが動かなくなってしまいました",
                "再起動してみたけどだめでした",
                "動かなくなって困っています"
        );
        List<String> result = ContinuationService.stripInputRepetition(candidates, ctx);
        assertEquals(3, result.size());
        assertEquals("しまいました", result.get(0));
        assertEquals("再起動してみたけどだめでした", result.get(1));
        assertEquals("困っています", result.get(2));
    }

    @Test
    void stripList_multipleSentenceBoundaries() {
        String ctx = "まず設定を確認。次にログを見る。最後にテスト";
        List<String> candidates = List.of(
                "最後にテストを実行する",  // overlap: "最後にテスト" matches ctx suffix
                "全く新しい提案です"       // no overlap
        );
        List<String> result = ContinuationService.stripInputRepetition(candidates, ctx);
        assertEquals(2, result.size());
        assertEquals("を実行する", result.get(0));
        assertEquals("全く新しい提案です", result.get(1));
    }

    @Test
    void stripList_fragmentAfterMiddleBoundary() {
        // Fragment after first sentence boundary matches candidate start
        String ctx = "設定を確認。ログを見る";
        List<String> candidates = List.of(
                "ログを見ると原因がわかった"  // fragment "ログを見る" matches
        );
        List<String> result = ContinuationService.stripInputRepetition(candidates, ctx);
        assertEquals(1, result.size());
        assertEquals("と原因がわかった", result.get(0));
    }
}
