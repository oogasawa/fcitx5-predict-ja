# Keybinding State Machine Specification

## States

| State | Conditions | Description |
|---|---|---|
| **IDLE** | `buffer.empty()`, no continuation/prediction | No input active. Keys pass through. |
| **INPUT** | `buffer.size() > 0`, `converting == false` | Romaji input in progress. Preedit shows hiragana. |
| **INPUT_PREDICT** | INPUT + `predictionCandidates` not empty | Prediction candidates visible (triggered at 5+ hiragana chars). |
| **CONVERTING** | `converting == true`, `llmMode == false` | Mozc segmentation active. |
| **CONVERTING_LLM** | `converting == true`, `llmMode == true` | LLM full-sentence conversion active. |
| **CONTINUATION** | `continuationCandidates` not empty, buffer empty | LLM continuation candidates shown. |

## State Transitions

```
IDLE ──[printable char]──> INPUT
IDLE ──[Ctrl+Tab]──> CONTINUATION (async fetch)
IDLE ──[any other key]──> pass through

INPUT ──[char]──> INPUT (append to buffer, update preedit)
INPUT ──[Backspace]──> INPUT (or IDLE if buffer becomes empty)
INPUT ──[Space]──> CONVERTING (Mozc segment-convert)
INPUT ──[Ctrl+Enter]──> CONVERTING_LLM (LLM full conversion)
INPUT ──[Enter]──> IDLE (commit raw hiragana)
INPUT ──[Escape]──> IDLE (cancel)
INPUT ──[5+ hiragana chars]──> INPUT_PREDICT (auto-fetch predictions)

INPUT_PREDICT ──[Tab]──> IDLE (commit selected prediction candidate)
INPUT_PREDICT ──[Down]──> INPUT_PREDICT (next prediction)
INPUT_PREDICT ──[Up]──> INPUT_PREDICT (prev prediction)
INPUT_PREDICT ──[Space]──> CONVERTING (predictions dismissed)
INPUT_PREDICT ──[Enter]──> IDLE (commit raw hiragana, predictions dismissed)
INPUT_PREDICT ──[Escape]──> IDLE (cancel all)
INPUT_PREDICT ──[char]──> INPUT or INPUT_PREDICT (re-fetch predictions)

CONVERTING ──[Enter]──> IDLE (commit conversion result)
CONVERTING ──[Ctrl+Enter]──> CONVERTING_LLM (switch to LLM)
CONVERTING ──[Space]──> CONVERTING (next candidate)
CONVERTING ──[Escape]──> INPUT (back to editing)
CONVERTING ──[Right]──> CONVERTING (next segment)
CONVERTING ──[Left]──> CONVERTING (prev segment)
CONVERTING ──[Shift+Right]──> CONVERTING (extend segment)
CONVERTING ──[Shift+Left]──> CONVERTING (shrink segment)
CONVERTING ──[Down]──> CONVERTING (next candidate for segment)
CONVERTING ──[Up]──> CONVERTING (prev candidate for segment)
CONVERTING ──[1-9]──> CONVERTING (select candidate by number)

CONVERTING_LLM ──[Enter]──> IDLE (commit)
CONVERTING_LLM ──[Space]──> CONVERTING_LLM or CONVERTING (cycle LLM then fallback to Mozc)
CONVERTING_LLM ──[Escape]──> INPUT
CONVERTING_LLM ──[Ctrl+Enter]──> CONVERTING_LLM (re-trigger LLM)
CONVERTING_LLM ──[Down/Up]──> CONVERTING_LLM (navigate candidates)

CONTINUATION ──[Down]──> CONTINUATION (next candidate, wrap)
CONTINUATION ──[Up]──> CONTINUATION (prev candidate, wrap)
CONTINUATION ──[Tab]──> IDLE (commit selected)
CONTINUATION ──[Enter]──> IDLE (commit selected)
CONTINUATION ──[Escape]──> IDLE (dismiss)
CONTINUATION ──[any other key]──> IDLE or INPUT (dismiss, process key normally)
```

## Key Priority Rules

1. **CONTINUATION handler runs first** — before checking `converting_` or buffer.
   If `continuationCandidates_` is not empty, Down/Up/Tab/Enter/Escape are consumed.
   Any other key dismisses continuation and falls through.

2. **Within handleInputKey**, prediction keys (Tab/Down/Up) are checked before
   conversion triggers (Space/Enter).

3. **Ctrl+Enter always takes priority over Enter** in both INPUT and CONVERTING modes.

## Commit Protocol (Bug Prevention)

When committing a prediction/continuation candidate, the order MUST be:

1. Save the selected text to a local variable
2. Clear `buffer_` / `wordBuffer_`
3. Clear `predictionCandidates_` / `continuationCandidates_`
4. `panel.reset()` + `ic->updatePreedit()` — flush preedit from UI
5. `ic->commitString(selectedText)` — commit the candidate
6. Update `committedContext_`
7. `ic->updateUserInterface(InputPanel)`

**Violating this order causes the preedit buffer (hiragana) to leak into the committed text.**

## Known Invariants

- `predictionCandidates_` and `continuationCandidates_` are engine-level (shared).
  TODO: Move to per-InputContext state to prevent leaks across windows.
- `continuationCandidates_` is populated asynchronously. If the user starts typing
  before the async response arrives, the dispatcher callback must check that the
  buffer is still empty before showing continuation UI.
- `predictionCandidates_` is cleared and re-fetched on every `updatePreedit` call.
  No stale prediction state should persist.
