#pragma once

#include <cstdint>
#include <string>

namespace ime_state {

enum class State {
    IDLE,
    INPUT,
    INPUT_PREDICT,
    CONVERTING,
    CONVERTING_LLM,
    CONTINUATION
};

enum class ActionType {
    PASS_THROUGH,       // Do nothing, let key pass to application
    ADD_CHAR,           // Append character to buffer
    BACKSPACE,          // Remove last char from buffer
    START_CONVERSION,   // Mozc segment-convert (Space in INPUT)
    START_LLM_CONVERT,  // LLM full-sentence (Ctrl+Enter)
    COMMIT_HIRAGANA,    // Commit raw hiragana (Enter in INPUT)
    CANCEL_INPUT,       // Cancel input (Escape in INPUT)
    SELECT_PREDICTION,  // Commit prediction candidate (Tab in INPUT_PREDICT)
    NAV_PREDICTION_DOWN,  // Next prediction
    NAV_PREDICTION_UP,    // Previous prediction
    COMMIT_CONVERSION,  // Commit conversion result (Enter in CONVERTING)
    NEXT_CANDIDATE,     // Next candidate for segment (Space/Down in CONVERTING)
    PREV_CANDIDATE,     // Previous candidate (Up in CONVERTING)
    NEXT_SEGMENT,       // Right arrow in CONVERTING
    PREV_SEGMENT,       // Left arrow in CONVERTING
    EXTEND_SEGMENT,     // Shift+Right in CONVERTING
    SHRINK_SEGMENT,     // Shift+Left in CONVERTING
    SELECT_BY_NUMBER,   // 1-9 in CONVERTING
    CANCEL_CONVERSION,  // Escape in CONVERTING -> back to INPUT
    FETCH_CONTINUATION, // Ctrl+Tab in IDLE
    SELECT_CONTINUATION,  // Tab/Enter in CONTINUATION
    NAV_CONTINUATION_DOWN, // Down in CONTINUATION
    NAV_CONTINUATION_UP,   // Up in CONTINUATION
    DISMISS_CONTINUATION,  // Escape or other key in CONTINUATION
    DISMISS_AND_PROCESS    // Dismiss continuation, then process key normally
};

struct Action {
    ActionType type;
    int number;     // for SELECT_BY_NUMBER (1-9)
    uint32_t ch;    // for ADD_CHAR
};

struct KeyInput {
    uint32_t keySym;
    bool ctrl;
    bool shift;
    bool isRelease;
    bool isSimple;     // printable key
    bool hasModifier;
    uint32_t unicode;  // result of keySymToUnicode
};

// Key symbols (matching FcitxKey_ constants)
constexpr uint32_t KEY_TAB = 0xff09;
constexpr uint32_t KEY_RETURN = 0xff0d;
constexpr uint32_t KEY_ESCAPE = 0xff1b;
constexpr uint32_t KEY_SPACE = 0x020;
constexpr uint32_t KEY_BACKSPACE = 0xff08;
constexpr uint32_t KEY_LEFT = 0xff51;
constexpr uint32_t KEY_UP = 0xff52;
constexpr uint32_t KEY_RIGHT = 0xff53;
constexpr uint32_t KEY_DOWN = 0xff54;
constexpr uint32_t KEY_1 = 0x031;
constexpr uint32_t KEY_9 = 0x039;

/**
 * Pure state machine for IME keybinding logic.
 * No fcitx5 dependencies — testable in isolation.
 *
 * External code must call setXxx() to update the machine's view of UI state
 * after executing each action.
 */
class InputStateMachine {
public:
    State currentState() const;

    /**
     * Process a key and return the action to execute.
     * The caller must then execute the action and update state accordingly.
     */
    Action handleKey(const KeyInput &key);

    // State setters — called by engine after executing actions
    void setBufferSize(size_t size);
    void setHiraganaCharCount(size_t count);
    void setPredictionAvailable(bool available);
    void setContinuationAvailable(bool available);
    void setConverting(bool converting, bool llmMode);
    void reset();

private:
    Action handleContinuation(const KeyInput &key);
    Action handleConverting(const KeyInput &key);
    Action handleInput(const KeyInput &key);
    Action handleIdle(const KeyInput &key);

    size_t bufferSize_ = 0;
    size_t hiraganaCharCount_ = 0;
    bool predictionAvailable_ = false;
    bool continuationAvailable_ = false;
    bool converting_ = false;
    bool llmMode_ = false;
};

} // namespace ime_state
