# fcitx5-predict-ja

Japanese predictive input system. Learns prediction candidates from conversation history collected via MCP Gateway and provides input completion on fcitx5.

## Components

| Component | Role |
|---|---|
| `daemon/` | Prediction daemon (Java) — knowledge base management, LLM filter, Mozc integration, HTTP server |
| `addon/` | fcitx5 C++ addon (llm-ime) — romaji→hiragana conversion, IME input handling, prediction candidate display, LLM continuation UI |
| `plugin/` | fcitx5 notification addon (predict-ja-notifier) |

## Dependencies

### Daemon
- Java 21+
- [POJO-actor](../POJO-actor/) — actor framework (must be `mvn install`ed first)
- vLLM server — used for phrase filtering and continuation generation
- MCP Gateway — conversation history aggregation (optional)

### C++ Addon
- fcitx5, libcurl, nlohmann-json
- CMake 3.16+, C++17 compiler

## Build

```bash
# 1. Install POJO-actor (if not already installed)
cd ~/works/POJO-actor
rm -rf target && mvn install

# 2. Build the daemon
cd ~/works/fcitx5-predict-ja/daemon
rm -rf target && mvn package

# 3. Build the C++ addon
cd ~/works/fcitx5-predict-ja/addon
mkdir -p build && cd build
cmake .. && make -j$(nproc)
```

## Install

### Daemon

Place the JAR file wherever you like. After build it is at `daemon/target/fcitx5-predict-ja-0.1.0-SNAPSHOT.jar`.

### fcitx5 Addon (`llm-ime`)

```bash
sudo cp ~/works/fcitx5-predict-ja/addon/build/lib/llm-ime.so /usr/lib/x86_64-linux-gnu/fcitx5/
sudo cp ~/works/fcitx5-predict-ja/addon/build/lib/llm-ime.so /usr/local/lib/fcitx5/
fcitx5 -r   # restart fcitx5
```

## Running

```bash
# Start the daemon
java -jar daemon/target/fcitx5-predict-ja-0.1.0-SNAPSHOT.jar \
  --vllm-url http://<vllm-host>:8000 \
  --vllm-model <model-name> \
  --port 8190 \
  --curate-interval 1 \
  --gateway-url http://localhost:8888 \
  --gateway-poll 60 \
  --ime-learning false
```

### Options

| Option | Default | Description |
|---|---|---|
| `--vllm-url` | (required) | vLLM server URL |
| `--vllm-model` | (required) | Model name to use |
| `--port` | `8190` | Daemon HTTP port |
| `--curate-interval` | `1` | Knowledge base curation interval (minutes) |
| `--gateway-url` | (none) | MCP Gateway URL (no polling if omitted) |
| `--gateway-poll` | `60` | Gateway polling interval (seconds) |
| `--ime-learning` | `true` | Whether to learn from IME committed text |

## Keybindings

### Input Mode (romaji input)

| Key | Action |
|---|---|
| Romaji keys | Convert to hiragana and display in preedit |
| `Space` | Start Mozc segment conversion |
| `Enter` | Commit as hiragana |
| `Ctrl+Enter` | LLM full-sentence conversion |
| `Backspace` | Delete one character |
| `Escape` | Cancel input |

### Converting Mode (after pressing Space)

| Key | Action |
|---|---|
| `Space` / `Down` | Next conversion candidate |
| `Up` | Previous conversion candidate |
| `Enter` | Commit selected conversion |
| `Escape` | Cancel conversion (return to hiragana) |
| `Shift+Right` | Extend segment |
| `Shift+Left` | Shrink segment |
| `Right` | Move to next segment |
| `Left` | Move to previous segment |

### Prediction Mode (during preedit, 5+ hiragana characters)

Performs prefix matching against the knowledge base using the current hiragana input and automatically displays candidates.

| Key | Action |
|---|---|
| `Down` / `Up` | Navigate prediction candidates |
| `Tab` | Select and commit prediction candidate |
| `Escape` | Dismiss prediction candidates |
| Continue typing | Ignore predictions and continue normal input |

### LLM Continuation Mode (empty input buffer)

Generates a continuation of the committed text using the LLM.

| Key | Action |
|---|---|
| `Ctrl+Tab` | Request LLM continuation (async, does not block UI) |
| `Down` / `Up` | Navigate continuation candidates |
| `Tab` / `Enter` | Select and commit continuation candidate |
| `Escape` | Dismiss continuation candidates |

## API Endpoints

| Endpoint | Method | Description |
|---|---|---|
| `/api/predict?prefix=<hiragana>&limit=<n>` | GET | Prefix search on the knowledge base |
| `/api/continue` | POST | LLM continuation generation (`{"context":"...", "n":5}`) |
| `/api/segment-convert` | POST | Mozc segment conversion (`{"input":"hiragana"}`) |
| `/api/record` | POST | Record IME committed text (only effective when `--ime-learning true`) |
| `/api/health` | GET | Health check |

## Architecture

```
                          ┌─────────────────┐     ┌──────────┐
┌──────────────┐          │ predict-ja      │     │  vLLM    │
│  fcitx5      │          │ daemon (:8190)  │────>│  server  │
│  llm-ime     │─────────>│                 │     │          │
│  (C++ addon) │          │  ┌───────────┐  │     └──────────┘
└──────────────┘          │  │KnowledgeBase│ │
                          │  │   (H2 DB)  │ │     ┌──────────┐
                          │                 │     │  MCP     │
                          │  ┌───────────┐  │<────│ Gateway  │
                          │  │   Mozc    │  │     │ (:8888)  │
                          │  │  server   │  │     └──────────┘
                          └─────────────────┘
```

### Data Flow

1. **Knowledge base accumulation**: Gateway → polling → split conversation text at sentence boundaries → LLM filter (deduplication) → kuromoji reading assignment → save to H2 DB
2. **Prediction input**: 5+ hiragana characters typed → `/api/predict` prefix search → display in fcitx5 candidate window
3. **LLM continuation**: `Ctrl+Tab` → send committed text + Gateway conversation history to LLM → display continuation candidates asynchronously

## Testing

### Unit Tests

```bash
# Daemon (Java)
cd daemon && mvn test

# C++ state machine
cd addon/test
g++ -std=c++17 -I../src -o test-state-machine \
    test-state-machine.cpp ../src/input-state-machine.cpp && ./test-state-machine
```

### E2E Tests

Requires the daemon to be running.

```bash
./test-api.sh
```
