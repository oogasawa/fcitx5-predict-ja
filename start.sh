#!/bin/bash
# Start fcitx5-predict-ja and fcitx5-predict-en daemons
# vLLM: Qwen3.5-35B-A3B on 192.168.5.15:8000

VLLM_URL="http://192.168.5.15:8000"
VLLM_MODEL="Qwen3.5-35B-A3B"
GATEWAY_URL="http://localhost:8888"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JA_JAR="$SCRIPT_DIR/daemon/target/fcitx5-predict-ja-0.1.0-SNAPSHOT.jar"
EN_JAR="$(dirname "$SCRIPT_DIR")/fcitx5-predict-en/daemon/target/fcitx5-predict-en-0.1.0-SNAPSHOT.jar"

# Stop existing daemons
for port in 8190 8191; do
    pid=$(lsof -ti:$port 2>/dev/null)
    if [ -n "$pid" ]; then
        echo "Stopping PID $pid on port $port"
        kill "$pid" 2>/dev/null
    fi
done
sleep 1

# Start Japanese daemon
if [ -f "$JA_JAR" ]; then
    echo "Starting fcitx5-predict-ja on port 8190 (model: $VLLM_MODEL)"
    java -jar "$JA_JAR" \
        --vllm-url "$VLLM_URL" \
        --vllm-model "$VLLM_MODEL" \
        --port 8190 \
        --curate-interval 1 \
        --gateway-url "$GATEWAY_URL" \
        --gateway-poll 60 \
        --ime-learning false \
        > /tmp/predict-ja.log 2>&1 &
else
    echo "ERROR: $JA_JAR not found. Run: cd daemon && mvn package"
fi

# Start English daemon
if [ -f "$EN_JAR" ]; then
    echo "Starting fcitx5-predict-en on port 8191 (model: $VLLM_MODEL)"
    java -jar "$EN_JAR" \
        --vllm-url "$VLLM_URL" \
        --vllm-model "$VLLM_MODEL" \
        --port 8191 \
        --curate-interval 5 \
        --gateway-url "$GATEWAY_URL" \
        --gateway-poll 60 \
        > /tmp/predict-en.log 2>&1 &
else
    echo "WARNING: $EN_JAR not found. English daemon not started."
fi

sleep 3
echo ""
echo "Listening ports:"
ss -tlnp 2>/dev/null | head -1
ss -tlnp 2>/dev/null | grep -E '8190|8191'
