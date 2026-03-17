#!/bin/bash
# E2E API tests for fcitx5-predict-ja daemon (port 8190)
# Usage: ./test-api.sh [port]
# Assumes daemon is running.

PORT="${1:-8190}"
BASE="http://localhost:$PORT"
PASS=0
FAIL=0

check() {
    local name="$1"
    local expected="$2"
    local actual="$3"
    if echo "$actual" | python3 -c "import sys,json; d=json.load(sys.stdin); $expected" 2>/dev/null; then
        echo "  PASS: $name"
        PASS=$((PASS+1))
    else
        echo "  FAIL: $name"
        echo "    Response: $actual"
        FAIL=$((FAIL+1))
    fi
}

check_status() {
    local name="$1"
    local expected_code="$2"
    local actual_code="$3"
    if [ "$actual_code" = "$expected_code" ]; then
        echo "  PASS: $name"
        PASS=$((PASS+1))
    else
        echo "  FAIL: $name (expected $expected_code, got $actual_code)"
        FAIL=$((FAIL+1))
    fi
}

echo "=== fcitx5-predict-ja E2E API Tests (port $PORT) ==="
echo ""

# 1. Health check
echo "[1] GET /api/health"
resp=$(curl -s "$BASE/api/health")
check "returns status ok" "assert d['status'] == 'ok'" "$resp"

# 2. Predict with empty prefix
echo "[2] GET /api/predict?prefix=&limit=5"
resp=$(curl -s "$BASE/api/predict?prefix=&limit=5")
check "returns array" "assert isinstance(d, list)" "$resp"

# 3. Predict with specific prefix
echo "[3] GET /api/predict?prefix=%E3%81%B3%E3%82%8B%E3%81%A9&limit=5"
resp=$(curl -s "$BASE/api/predict?prefix=%E3%81%B3%E3%82%8B%E3%81%A9&limit=5")
check "returns array" "assert isinstance(d, list)" "$resp"
check "entries have reading field" "assert all('reading' in e for e in d) if d else True" "$resp"
check "entries have candidate field" "assert all('candidate' in e for e in d) if d else True" "$resp"

# 4. Segment-convert
echo "[4] POST /api/segment-convert"
resp=$(curl -s -X POST "$BASE/api/segment-convert" \
    -H "Content-Type: application/json" \
    -d '{"input":"こんにちは"}')
check "returns segments object" "assert 'segments' in d" "$resp"
check "segments is array" "assert isinstance(d['segments'], list)" "$resp"

# 5. Segment-convert with empty input
echo "[5] POST /api/segment-convert (empty)"
resp=$(curl -s -X POST "$BASE/api/segment-convert" \
    -H "Content-Type: application/json" \
    -d '{"input":""}')
check "returns empty segments" "assert d['segments'] == []" "$resp"

# 6. Continue endpoint
echo "[6] POST /api/continue"
resp=$(curl -s -X POST "$BASE/api/continue" \
    -H "Content-Type: application/json" \
    -d '{"context":"ビルドを実行して","n":3}')
check "returns array" "assert isinstance(d, list)" "$resp"

# 7. Continue with empty context
echo "[7] POST /api/continue (empty context)"
resp=$(curl -s -X POST "$BASE/api/continue" \
    -H "Content-Type: application/json" \
    -d '{"context":"","n":3}')
check "returns array (possibly empty)" "assert isinstance(d, list)" "$resp"

# --- Ctrl+Tab Continuation Flow (E2E) ---
# These tests verify the full API contract that the C++ addon depends on
# when the user presses Ctrl+Tab to trigger LLM continuation.

# 8. Continuation returns correct structure for C++ addon parsing
echo "[8] POST /api/continue - response structure for Ctrl+Tab"
resp=$(curl -s -X POST "$BASE/api/continue" \
    -H "Content-Type: application/json" \
    -d '{"context":"インストールを実行して","n":5}')
check "returns array" "assert isinstance(d, list)" "$resp"
check "returns non-empty array" "assert len(d) > 0" "$resp"
check "each entry has 'text' field (addon reads this)" \
    "assert all('text' in e for e in d)" "$resp"
check "text values are non-empty strings" \
    "assert all(isinstance(e['text'], str) and len(e['text']) > 0 for e in d)" "$resp"

# 9. Continuation returns requested number of candidates
echo "[9] POST /api/continue - candidate count matches request"
resp=$(curl -s -X POST "$BASE/api/continue" \
    -H "Content-Type: application/json" \
    -d '{"context":"テストが全部通った","n":3}')
check "returns exactly n or fewer candidates" \
    "assert isinstance(d, list) and 0 < len(d) <= 3" "$resp"

# 10. Continuation candidates are diverse (not all identical)
echo "[10] POST /api/continue - candidates are diverse"
resp=$(curl -s -X POST "$BASE/api/continue" \
    -H "Content-Type: application/json" \
    -d '{"context":"このプロジェクトは","n":5}')
check "at least 2 distinct candidates" \
    "texts = [e['text'] for e in d]; assert len(set(texts)) >= 2" "$resp"

# 11. Continuation with empty context returns empty (no crash)
echo "[11] POST /api/continue - empty context returns empty"
resp=$(curl -s -X POST "$BASE/api/continue" \
    -H "Content-Type: application/json" \
    -d '{"context":"","n":3}')
check "returns empty array for empty context" "assert isinstance(d, list) and len(d) == 0" "$resp"

# 12. Continuation with n=1 returns single candidate
echo "[12] POST /api/continue - n=1 single candidate"
resp=$(curl -s -X POST "$BASE/api/continue" \
    -H "Content-Type: application/json" \
    -d '{"context":"明日の会議は","n":1}')
check "returns exactly 1 candidate" \
    "assert isinstance(d, list) and len(d) == 1" "$resp"
check "single candidate has text field" \
    "assert d[0].get('text','')" "$resp"

# --- End Ctrl+Tab Continuation Flow ---

# 13. Flush
echo "[13] POST /api/flush"
resp=$(curl -s -X POST "$BASE/api/flush")
check "returns flushed status" "assert d['status'] == 'flushed'" "$resp"

# 14. Curate
echo "[14] POST /api/curate"
resp=$(curl -s -X POST "$BASE/api/curate")
check "returns curating status" "assert d['status'] == 'curating'" "$resp"

# 15. Record (with ime-learning disabled)
echo "[15] POST /api/record"
resp=$(curl -s -X POST "$BASE/api/record" \
    -H "Content-Type: application/json" \
    -d '{"input":"test","output":"テスト"}')
check "returns valid JSON" "assert isinstance(d, dict)" "$resp"

# 16. Predict returns valid candidate structure for selection
echo "[16] GET /api/predict - candidate structure for Tab select"
resp=$(curl -s "$BASE/api/predict?prefix=%E3%81%AF%E3%81%84%E3%81%91%E3%81%84&limit=1")
check "returns array for single-candidate request" "assert isinstance(d, list)" "$resp"
check "each entry has 'candidate' field for commit" \
    "assert all('candidate' in e for e in d) if d else True" "$resp"
check "each entry has 'reading' field" \
    "assert all('reading' in e for e in d) if d else True" "$resp"
check "candidate != reading (prediction is converted form)" \
    "assert all(e.get('candidate','') != e.get('reading','') for e in d) if len(d) > 0 else True" "$resp"

# 17. Predict with limit=1 returns at most 1 result
echo "[17] GET /api/predict - limit=1 returns at most 1"
resp=$(curl -s "$BASE/api/predict?prefix=%E3%81%93%E3%82%93%E3%81%AB%E3%81%A1&limit=1")
check "returns at most 1 entry" "assert isinstance(d, list) and len(d) <= 1" "$resp"

# 18. Predict with very short prefix returns empty (< 5 hiragana chars triggers no fetch)
echo "[18] GET /api/predict - short prefix behavior"
resp=$(curl -s "$BASE/api/predict?prefix=%E3%81%82&limit=5")
check "returns array (possibly empty for short prefix)" "assert isinstance(d, list)" "$resp"

# 19. Wrong method on segment-convert
echo "[19] GET /api/segment-convert (wrong method)"
code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/segment-convert")
check_status "returns 405" "405" "$code"

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
exit $FAIL
