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

# 8. Flush
echo "[8] POST /api/flush"
resp=$(curl -s -X POST "$BASE/api/flush")
check "returns flushed status" "assert d['status'] == 'flushed'" "$resp"

# 9. Curate
echo "[9] POST /api/curate"
resp=$(curl -s -X POST "$BASE/api/curate")
check "returns curating status" "assert d['status'] == 'curating'" "$resp"

# 10. Record (with ime-learning disabled)
echo "[10] POST /api/record"
resp=$(curl -s -X POST "$BASE/api/record" \
    -H "Content-Type: application/json" \
    -d '{"input":"test","output":"テスト"}')
check "returns valid JSON" "assert isinstance(d, dict)" "$resp"

# 11. Predict returns valid candidate structure for selection
echo "[11] GET /api/predict - candidate structure for Tab select"
resp=$(curl -s "$BASE/api/predict?prefix=%E3%81%AF%E3%81%84%E3%81%91%E3%81%84&limit=1")
check "returns array for single-candidate request" "assert isinstance(d, list)" "$resp"
check "each entry has 'candidate' field for commit" \
    "assert all('candidate' in e for e in d) if d else True" "$resp"
check "each entry has 'reading' field" \
    "assert all('reading' in e for e in d) if d else True" "$resp"
check "candidate != reading (prediction is converted form)" \
    "assert all(e.get('candidate','') != e.get('reading','') for e in d) if len(d) > 0 else True" "$resp"

# 12. Predict with limit=1 returns at most 1 result
echo "[12] GET /api/predict - limit=1 returns at most 1"
resp=$(curl -s "$BASE/api/predict?prefix=%E3%81%93%E3%82%93%E3%81%AB%E3%81%A1&limit=1")
check "returns at most 1 entry" "assert isinstance(d, list) and len(d) <= 1" "$resp"

# 13. Predict with very short prefix returns empty (< 5 hiragana chars triggers no fetch)
echo "[13] GET /api/predict - short prefix behavior"
resp=$(curl -s "$BASE/api/predict?prefix=%E3%81%82&limit=5")
check "returns array (possibly empty for short prefix)" "assert isinstance(d, list)" "$resp"

# 14. Wrong method on segment-convert
echo "[14] GET /api/segment-convert (wrong method)"
code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/segment-convert")
check_status "returns 405" "405" "$code"

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
exit $FAIL
