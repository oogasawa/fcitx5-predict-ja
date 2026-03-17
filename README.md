# fcitx5-predict-ja

日本語予測変換システム。MCP Gateway経由で収集した会話履歴から予測候補を学習し、fcitx5上で入力補完を提供する。

## コンポーネント

| コンポーネント | 役割 |
|---|---|
| `daemon/` | 予測変換デーモン（Java）— 知識ベース管理、LLMフィルタ、Mozc連携、HTTPサーバ |
| `fcitx5-llm-ime` | fcitx5 C++アドオン（別リポジトリ）— IME入力処理、予測候補表示 |

## 依存関係

- Java 21+
- [POJO-actor](../POJO-actor/) — アクターフレームワーク（`mvn install`しておくこと）
- vLLMサーバ — フレーズフィルタ・continuation生成に使用
- MCP Gateway — 会話履歴の集約（オプション）
- `fcitx5-llm-ime` — fcitx5上で候補を表示するC++アドオン（別リポジトリ）

## ビルド

```bash
# 1. POJO-actorをインストール（未インストールの場合）
cd ~/works/POJO-actor
rm -rf target && mvn install

# 2. デーモンをビルド
cd ~/works/fcitx5-predict-ja/daemon
rm -rf target && mvn package

# 3. fcitx5-llm-imeをビルド（別リポジトリ）
cd ~/works/fcitx5-llm-ime
mkdir -p build && cd build
cmake .. && make -j$(nproc)
```

## インストール

### デーモン

JARファイルを任意の場所に配置する。ビルド後のJARは `daemon/target/fcitx5-predict-ja-0.1.0-SNAPSHOT.jar`。

### fcitx5アドオン（`fcitx5-llm-ime`）

```bash
sudo cp ~/works/fcitx5-llm-ime/build/lib/llm-ime.so /usr/lib/x86_64-linux-gnu/fcitx5/
sudo cp ~/works/fcitx5-llm-ime/build/lib/llm-ime.so /usr/local/lib/fcitx5/
fcitx5 -r   # fcitx5を再起動
```

## 起動

```bash
# デーモン起動
java -jar daemon/target/fcitx5-predict-ja-0.1.0-SNAPSHOT.jar \
  --vllm-url http://<vllm-host>:8000 \
  --vllm-model <model-name> \
  --port 8190 \
  --curate-interval 1 \
  --gateway-url http://localhost:8888 \
  --gateway-poll 60 \
  --ime-learning false
```

### 起動オプション

| オプション | デフォルト | 説明 |
|---|---|---|
| `--vllm-url` | なし（必須） | vLLMサーバのURL |
| `--vllm-model` | なし（必須） | 使用するモデル名 |
| `--port` | `8190` | デーモンのHTTPポート |
| `--curate-interval` | `1` | 知識ベース整理の間隔（分） |
| `--gateway-url` | なし | MCP GatewayのURL（指定しなければポーリングなし） |
| `--gateway-poll` | `60` | Gatewayポーリング間隔（秒） |
| `--ime-learning` | `true` | IME確定テキストから学習するか |

## キーバインド

### 通常入力モード（ローマ字入力中）

| キー | 動作 |
|---|---|
| ローマ字入力 | ひらがなに変換してプリエディットに表示 |
| `Space` | Mozc文節変換を開始 |
| `Enter` | ひらがなのまま確定 |
| `Ctrl+Enter` | LLM全文変換（一括変換） |
| `Backspace` | 1文字削除 |
| `Escape` | 入力をキャンセル |

### 変換モード（Space押下後）

| キー | 動作 |
|---|---|
| `Space` / `↓` | 次の変換候補 |
| `↑` | 前の変換候補 |
| `Enter` | 選択中の変換を確定 |
| `Escape` | 変換をキャンセル（ひらがなに戻る） |
| `Shift+→` | 文節を伸ばす |
| `Shift+←` | 文節を縮める |
| `→` | 次の文節へ移動 |
| `←` | 前の文節へ移動 |

### 予測入力モード（プリエディット中、ひらがな5文字以上）

入力中のひらがなで知識ベースを前方一致検索し、候補を自動表示する。

| キー | 動作 |
|---|---|
| `↓` / `↑` | 予測候補をナビゲート |
| `Tab` / `Enter` | 予測候補を選択・確定 |
| `Escape` | 予測候補を閉じる |
| そのまま入力継続 | 予測候補を無視して通常入力 |

### LLM continuation モード（入力バッファが空の状態）

確定済みテキストの「続き」をLLMに生成させる。

| キー | 動作 |
|---|---|
| `Ctrl+Tab` | LLMにcontinuation要求（非同期、UIはブロックしない） |
| `↓` / `↑` | continuation候補をナビゲート |
| `Tab` / `Enter` | continuation候補を選択・確定 |
| `Escape` | continuation候補を閉じる |

## APIエンドポイント

| エンドポイント | メソッド | 説明 |
|---|---|---|
| `/api/predict?prefix=<ひらがな>&limit=<n>` | GET | 知識ベースの前方一致検索 |
| `/api/continue` | POST | LLM continuation生成（`{"context":"...", "n":5}`） |
| `/api/segment-convert` | POST | Mozc文節変換（`{"input":"ひらがな"}`） |
| `/api/record` | POST | IME確定テキストの記録（`--ime-learning true`時のみ有効） |
| `/api/health` | GET | ヘルスチェック |

## アーキテクチャ

```
┌──────────────┐     ┌─────────────────┐     ┌──────────┐
│  fcitx5      │     │ predict-ja      │     │  vLLM    │
│  llm-ime     │────▶│ daemon (:8190)  │────▶│  server  │
│  (C++ addon) │     │                 │     │          │
└──────────────┘     │  ┌───────────┐  │     └──────────┘
                     │  │KnowledgeBase│ │
                     │  │   (H2 DB)  │ │     ┌──────────┐
                     │  └───────────┘  │     │  MCP     │
                     │                 │◀────│ Gateway  │
                     │  ┌───────────┐  │     │ (:8888)  │
                     │  │   Mozc    │  │     └──────────┘
                     │  │  server   │  │
                     │  └───────────┘  │
                     └─────────────────┘
```

### データフロー

1. **知識ベース蓄積**: Gateway → ポーリング → 会話テキストを句読点で分割 → LLMフィルタ（重複除去） → kuromoji読み付け → H2 DB保存
2. **予測入力**: ひらがな5文字以上入力 → `/api/predict` で前方一致検索 → fcitx5候補ウィンドウに表示
3. **LLM continuation**: `Ctrl+Tab` → 確定済みテキスト + Gateway会話履歴をLLMに送信 → 続きの候補を非同期で表示
