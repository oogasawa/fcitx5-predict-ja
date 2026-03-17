#include "input-state-machine.h"

namespace ime_state {

State InputStateMachine::currentState() const {
    if (continuationAvailable_ && bufferSize_ == 0) return State::CONTINUATION;
    if (converting_ && llmMode_) return State::CONVERTING_LLM;
    if (converting_) return State::CONVERTING;
    if (bufferSize_ > 0 && predictionAvailable_) return State::INPUT_PREDICT;
    if (bufferSize_ > 0) return State::INPUT;
    return State::IDLE;
}

Action InputStateMachine::handleKey(const KeyInput &key) {
    if (key.isRelease) return {ActionType::PASS_THROUGH, 0, 0};

    State state = currentState();
    switch (state) {
        case State::CONTINUATION:   return handleContinuation(key);
        case State::CONVERTING:
        case State::CONVERTING_LLM: return handleConverting(key);
        case State::INPUT_PREDICT:
        case State::INPUT:          return handleInput(key);
        case State::IDLE:           return handleIdle(key);
    }
    return {ActionType::PASS_THROUGH, 0, 0};
}

Action InputStateMachine::handleContinuation(const KeyInput &key) {
    if (key.keySym == KEY_DOWN)
        return {ActionType::NAV_CONTINUATION_DOWN, 0, 0};
    if (key.keySym == KEY_UP)
        return {ActionType::NAV_CONTINUATION_UP, 0, 0};
    if (key.keySym == KEY_RETURN || key.keySym == KEY_TAB)
        return {ActionType::SELECT_CONTINUATION, 0, 0};
    if (key.keySym == KEY_ESCAPE)
        return {ActionType::DISMISS_CONTINUATION, 0, 0};
    // Any other key -> dismiss and process
    return {ActionType::DISMISS_AND_PROCESS, 0, 0};
}

Action InputStateMachine::handleConverting(const KeyInput &key) {
    // Ctrl+Enter -> LLM (re)conversion
    if (key.keySym == KEY_RETURN && key.ctrl)
        return {ActionType::START_LLM_CONVERT, 0, 0};
    // Enter -> commit conversion
    if (key.keySym == KEY_RETURN)
        return {ActionType::COMMIT_CONVERSION, 0, 0};
    // Space -> next candidate
    if (key.keySym == KEY_SPACE)
        return {ActionType::NEXT_CANDIDATE, 0, 0};
    // Escape -> back to input
    if (key.keySym == KEY_ESCAPE)
        return {ActionType::CANCEL_CONVERSION, 0, 0};
    // Shift+Right -> extend segment
    if (key.keySym == KEY_RIGHT && key.shift)
        return {ActionType::EXTEND_SEGMENT, 0, 0};
    // Shift+Left -> shrink segment
    if (key.keySym == KEY_LEFT && key.shift)
        return {ActionType::SHRINK_SEGMENT, 0, 0};
    // Right -> next segment
    if (key.keySym == KEY_RIGHT)
        return {ActionType::NEXT_SEGMENT, 0, 0};
    // Left -> prev segment
    if (key.keySym == KEY_LEFT)
        return {ActionType::PREV_SEGMENT, 0, 0};
    // Down -> next candidate
    if (key.keySym == KEY_DOWN)
        return {ActionType::NEXT_CANDIDATE, 0, 0};
    // Up -> prev candidate
    if (key.keySym == KEY_UP)
        return {ActionType::PREV_CANDIDATE, 0, 0};
    // Number keys 1-9
    if (key.keySym >= KEY_1 && key.keySym <= KEY_9)
        return {ActionType::SELECT_BY_NUMBER, static_cast<int>(key.keySym - KEY_1 + 1), 0};

    return {ActionType::PASS_THROUGH, 0, 0};
}

Action InputStateMachine::handleInput(const KeyInput &key) {
    // Ctrl+Tab -> continuation (only if buffer empty, but we're in INPUT so buffer > 0)
    // This shouldn't fire in INPUT state

    // Tab with predictions -> select prediction
    if (key.keySym == KEY_TAB && predictionAvailable_)
        return {ActionType::SELECT_PREDICTION, 0, 0};

    // Down with predictions -> navigate
    if (key.keySym == KEY_DOWN && predictionAvailable_)
        return {ActionType::NAV_PREDICTION_DOWN, 0, 0};

    // Up with predictions -> navigate
    if (key.keySym == KEY_UP && predictionAvailable_)
        return {ActionType::NAV_PREDICTION_UP, 0, 0};

    // Ctrl+Enter -> LLM conversion
    if (key.keySym == KEY_RETURN && key.ctrl)
        return {ActionType::START_LLM_CONVERT, 0, 0};

    // Space -> start Mozc conversion
    if (key.keySym == KEY_SPACE)
        return {ActionType::START_CONVERSION, 0, 0};

    // Enter -> commit raw hiragana
    if (key.keySym == KEY_RETURN)
        return {ActionType::COMMIT_HIRAGANA, 0, 0};

    // Escape -> cancel
    if (key.keySym == KEY_ESCAPE)
        return {ActionType::CANCEL_INPUT, 0, 0};

    // Backspace
    if (key.keySym == KEY_BACKSPACE)
        return {ActionType::BACKSPACE, 0, 0};

    // Printable ASCII
    if (key.isSimple && !key.hasModifier && key.unicode > 0 && key.unicode < 128)
        return {ActionType::ADD_CHAR, 0, key.unicode};

    return {ActionType::PASS_THROUGH, 0, 0};
}

Action InputStateMachine::handleIdle(const KeyInput &key) {
    // Ctrl+Tab -> fetch continuation
    if (key.keySym == KEY_TAB && key.ctrl)
        return {ActionType::FETCH_CONTINUATION, 0, 0};

    // Printable ASCII -> start input
    if (key.isSimple && !key.hasModifier && key.unicode > 0 && key.unicode < 128)
        return {ActionType::ADD_CHAR, 0, key.unicode};

    return {ActionType::PASS_THROUGH, 0, 0};
}

void InputStateMachine::setBufferSize(size_t size) { bufferSize_ = size; }
void InputStateMachine::setHiraganaCharCount(size_t count) { hiraganaCharCount_ = count; }
void InputStateMachine::setPredictionAvailable(bool available) { predictionAvailable_ = available; }
void InputStateMachine::setContinuationAvailable(bool available) { continuationAvailable_ = available; }
void InputStateMachine::setConverting(bool converting, bool llmMode) {
    converting_ = converting;
    llmMode_ = llmMode;
}
void InputStateMachine::reset() {
    bufferSize_ = 0;
    hiraganaCharCount_ = 0;
    predictionAvailable_ = false;
    continuationAvailable_ = false;
    converting_ = false;
    llmMode_ = false;
}

} // namespace ime_state
