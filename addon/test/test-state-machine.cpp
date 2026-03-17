/**
 * Unit tests for InputStateMachine.
 * Compile: g++ -std=c++17 -I../src -o test-state-machine \
 *          test-state-machine.cpp ../src/input-state-machine.cpp && ./test-state-machine
 */

#include "input-state-machine.h"
#include <cassert>
#include <iostream>
#include <string>

using namespace ime_state;

static int passed = 0;
static int failed = 0;

#define TEST(name) static void name()
#define RUN(name) do { \
    try { name(); passed++; std::cout << "  PASS: " #name "\n"; } \
    catch (const std::exception &e) { failed++; std::cout << "  FAIL: " #name " - " << e.what() << "\n"; } \
    catch (...) { failed++; std::cout << "  FAIL: " #name " - unknown error\n"; } \
} while(0)

#define ASSERT_EQ(a, b) do { if ((a) != (b)) { throw std::runtime_error("assertion failed"); } } while(0)
#define ASSERT_NE(a, b) do { if ((a) == (b)) { throw std::runtime_error("assertion failed"); } } while(0)

static KeyInput makeKey(uint32_t sym, bool ctrl = false, bool shift = false) {
    return {sym, ctrl, shift, false, false, false, 0};
}

static KeyInput makeChar(char c) {
    return {static_cast<uint32_t>(c), false, false, false, true, false, static_cast<uint32_t>(c)};
}

static KeyInput makeRelease(uint32_t sym) {
    return {sym, false, false, true, false, false, 0};
}

// === IDLE state tests ===

TEST(idle_initial_state) {
    InputStateMachine sm;
    ASSERT_EQ(sm.currentState(), State::IDLE);
}

TEST(idle_ctrl_tab_fetches_continuation) {
    InputStateMachine sm;
    auto action = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_EQ(action.type, ActionType::FETCH_CONTINUATION);
}

TEST(idle_printable_adds_char) {
    InputStateMachine sm;
    auto action = sm.handleKey(makeChar('k'));
    ASSERT_EQ(action.type, ActionType::ADD_CHAR);
    ASSERT_EQ(action.ch, static_cast<uint32_t>('k'));
}

TEST(idle_other_key_passes_through) {
    InputStateMachine sm;
    auto action = sm.handleKey(makeKey(KEY_ESCAPE));
    ASSERT_EQ(action.type, ActionType::PASS_THROUGH);
}

TEST(idle_release_passes_through) {
    InputStateMachine sm;
    auto action = sm.handleKey(makeRelease(KEY_TAB));
    ASSERT_EQ(action.type, ActionType::PASS_THROUGH);
}

// === INPUT state tests ===

TEST(input_space_starts_conversion) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    auto action = sm.handleKey(makeKey(KEY_SPACE));
    ASSERT_EQ(action.type, ActionType::START_CONVERSION);
}

TEST(input_enter_commits_hiragana) {
    InputStateMachine sm;
    sm.setBufferSize(3);
    auto action = sm.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(action.type, ActionType::COMMIT_HIRAGANA);
}

TEST(input_ctrl_enter_starts_llm) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    auto action = sm.handleKey(makeKey(KEY_RETURN, true));
    ASSERT_EQ(action.type, ActionType::START_LLM_CONVERT);
}

TEST(input_escape_cancels) {
    InputStateMachine sm;
    sm.setBufferSize(3);
    auto action = sm.handleKey(makeKey(KEY_ESCAPE));
    ASSERT_EQ(action.type, ActionType::CANCEL_INPUT);
}

TEST(input_backspace) {
    InputStateMachine sm;
    sm.setBufferSize(3);
    auto action = sm.handleKey(makeKey(KEY_BACKSPACE));
    ASSERT_EQ(action.type, ActionType::BACKSPACE);
}

TEST(input_char_appends) {
    InputStateMachine sm;
    sm.setBufferSize(3);
    auto action = sm.handleKey(makeChar('a'));
    ASSERT_EQ(action.type, ActionType::ADD_CHAR);
}

// === INPUT_PREDICT state tests ===

TEST(input_predict_tab_selects) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    ASSERT_EQ(sm.currentState(), State::INPUT_PREDICT);
    auto action = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(action.type, ActionType::SELECT_PREDICTION);
}

TEST(input_predict_down_navigates) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_DOWN));
    ASSERT_EQ(action.type, ActionType::NAV_PREDICTION_DOWN);
}

TEST(input_predict_up_navigates) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_UP));
    ASSERT_EQ(action.type, ActionType::NAV_PREDICTION_UP);
}

TEST(input_predict_space_converts) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_SPACE));
    ASSERT_EQ(action.type, ActionType::START_CONVERSION);
}

TEST(input_predict_enter_commits_hiragana) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(action.type, ActionType::COMMIT_HIRAGANA);
}

// === CONVERTING state tests ===

TEST(converting_enter_commits) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    ASSERT_EQ(sm.currentState(), State::CONVERTING);
    auto action = sm.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(action.type, ActionType::COMMIT_CONVERSION);
}

TEST(converting_ctrl_enter_switches_to_llm) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    auto action = sm.handleKey(makeKey(KEY_RETURN, true));
    ASSERT_EQ(action.type, ActionType::START_LLM_CONVERT);
}

TEST(converting_space_next_candidate) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    auto action = sm.handleKey(makeKey(KEY_SPACE));
    ASSERT_EQ(action.type, ActionType::NEXT_CANDIDATE);
}

TEST(converting_escape_back_to_input) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    auto action = sm.handleKey(makeKey(KEY_ESCAPE));
    ASSERT_EQ(action.type, ActionType::CANCEL_CONVERSION);
}

TEST(converting_right_next_segment) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    auto action = sm.handleKey(makeKey(KEY_RIGHT));
    ASSERT_EQ(action.type, ActionType::NEXT_SEGMENT);
}

TEST(converting_left_prev_segment) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    auto action = sm.handleKey(makeKey(KEY_LEFT));
    ASSERT_EQ(action.type, ActionType::PREV_SEGMENT);
}

TEST(converting_shift_right_extends) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    auto action = sm.handleKey(makeKey(KEY_RIGHT, false, true));
    ASSERT_EQ(action.type, ActionType::EXTEND_SEGMENT);
}

TEST(converting_shift_left_shrinks) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    auto action = sm.handleKey(makeKey(KEY_LEFT, false, true));
    ASSERT_EQ(action.type, ActionType::SHRINK_SEGMENT);
}

TEST(converting_down_next_candidate) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    auto action = sm.handleKey(makeKey(KEY_DOWN));
    ASSERT_EQ(action.type, ActionType::NEXT_CANDIDATE);
}

TEST(converting_up_prev_candidate) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    auto action = sm.handleKey(makeKey(KEY_UP));
    ASSERT_EQ(action.type, ActionType::PREV_CANDIDATE);
}

TEST(converting_number_selects) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    auto action = sm.handleKey(makeKey(KEY_1 + 2)); // key '3'
    ASSERT_EQ(action.type, ActionType::SELECT_BY_NUMBER);
    ASSERT_EQ(action.number, 3);
}

// === CONVERTING_LLM state tests ===

TEST(converting_llm_state) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, true);
    ASSERT_EQ(sm.currentState(), State::CONVERTING_LLM);
}

TEST(converting_llm_enter_commits) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, true);
    auto action = sm.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(action.type, ActionType::COMMIT_CONVERSION);
}

TEST(converting_llm_ctrl_enter_retriggers) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, true);
    auto action = sm.handleKey(makeKey(KEY_RETURN, true));
    ASSERT_EQ(action.type, ActionType::START_LLM_CONVERT);
}

// === CONTINUATION state tests ===

TEST(continuation_state) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
}

TEST(continuation_down_navigates) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_DOWN));
    ASSERT_EQ(action.type, ActionType::NAV_CONTINUATION_DOWN);
}

TEST(continuation_up_navigates) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_UP));
    ASSERT_EQ(action.type, ActionType::NAV_CONTINUATION_UP);
}

TEST(continuation_tab_selects) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(action.type, ActionType::SELECT_CONTINUATION);
}

TEST(continuation_enter_selects) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(action.type, ActionType::SELECT_CONTINUATION);
}

TEST(continuation_escape_dismisses) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_ESCAPE));
    ASSERT_EQ(action.type, ActionType::DISMISS_CONTINUATION);
}

TEST(continuation_other_key_dismisses_and_processes) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto action = sm.handleKey(makeChar('a'));
    ASSERT_EQ(action.type, ActionType::DISMISS_AND_PROCESS);
}

// === Prediction select edge cases ===

TEST(predict_tab_single_candidate) {
    // When there is exactly 1 prediction candidate, Tab must select it
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    ASSERT_EQ(sm.currentState(), State::INPUT_PREDICT);
    auto action = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(action.type, ActionType::SELECT_PREDICTION);
}

TEST(predict_tab_no_prior_navigation) {
    // Tab without any Down/Up navigation should still select (index defaults to 0)
    InputStateMachine sm;
    sm.setBufferSize(8);
    sm.setPredictionAvailable(true);
    // No Down/Up pressed — predictionIndex_ would be -1 in engine
    // State machine returns SELECT_PREDICTION regardless
    auto action = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(action.type, ActionType::SELECT_PREDICTION);
}

TEST(predict_tab_after_navigation) {
    // Tab after Down navigation should still return SELECT_PREDICTION
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    auto nav = sm.handleKey(makeKey(KEY_DOWN));
    ASSERT_EQ(nav.type, ActionType::NAV_PREDICTION_DOWN);
    // Prediction is still available after navigation
    auto action = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(action.type, ActionType::SELECT_PREDICTION);
}

TEST(predict_enter_does_not_select_prediction) {
    // Enter in INPUT_PREDICT must commit hiragana, NOT select prediction
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(action.type, ActionType::COMMIT_HIRAGANA);
    ASSERT_NE(action.type, ActionType::SELECT_PREDICTION);
}

TEST(predict_space_converts_not_selects) {
    // Space in INPUT_PREDICT must start conversion, NOT select prediction
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_SPACE));
    ASSERT_EQ(action.type, ActionType::START_CONVERSION);
    ASSERT_NE(action.type, ActionType::SELECT_PREDICTION);
}

TEST(predict_backspace_clears_char_not_select) {
    // Backspace in INPUT_PREDICT must delete char, not affect prediction
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_BACKSPACE));
    ASSERT_EQ(action.type, ActionType::BACKSPACE);
}

TEST(predict_escape_cancels_input) {
    // Escape in INPUT_PREDICT must cancel input entirely
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_ESCAPE));
    ASSERT_EQ(action.type, ActionType::CANCEL_INPUT);
}

TEST(predict_char_adds_to_buffer) {
    // Typing more chars while predictions are shown adds to buffer
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    auto action = sm.handleKey(makeChar('z'));
    ASSERT_EQ(action.type, ActionType::ADD_CHAR);
}

// === Commit protocol invariant tests ===
// These test that the state machine transitions imply the correct
// commit protocol: clear preedit BEFORE commitString.
// The state machine itself doesn't enforce order, but these tests
// document the expected action-to-engine-behavior mapping.

TEST(protocol_select_prediction_not_commit_hiragana) {
    // SELECT_PREDICTION and COMMIT_HIRAGANA are distinct actions.
    // Engine must implement SELECT_PREDICTION as:
    //   1. copy candidate  2. clear buffer  3. clear clientPreedit
    //   4. updatePreedit   5. commitString(candidate)
    // NOT as COMMIT_HIRAGANA which commits the buffer content.
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    auto tab_action = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(tab_action.type, ActionType::SELECT_PREDICTION);

    InputStateMachine sm2;
    sm2.setBufferSize(5);
    // No prediction available -> INPUT state
    auto enter_action = sm2.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(enter_action.type, ActionType::COMMIT_HIRAGANA);

    // These must be different actions
    ASSERT_NE(tab_action.type, enter_action.type);
}

TEST(protocol_select_continuation_is_distinct_action) {
    // SELECT_CONTINUATION must not be confused with any INPUT action
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(action.type, ActionType::SELECT_CONTINUATION);
    ASSERT_NE(action.type, ActionType::SELECT_PREDICTION);
    ASSERT_NE(action.type, ActionType::COMMIT_HIRAGANA);
}

// === Bug regression tests ===

TEST(bug_continuation_does_not_steal_converting_keys) {
    // If continuation arrives while converting, converting should take priority
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    // Even if continuation is available, buffer > 0 means CONVERTING state
    sm.setContinuationAvailable(true);
    // Continuation only activates when buffer is empty
    ASSERT_EQ(sm.currentState(), State::CONVERTING);
    auto action = sm.handleKey(makeKey(KEY_DOWN));
    ASSERT_EQ(action.type, ActionType::NEXT_CANDIDATE);
}

TEST(bug_prediction_select_is_separate_from_hiragana_commit) {
    // Tab in INPUT_PREDICT must select prediction, NOT commit hiragana
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    auto action = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(action.type, ActionType::SELECT_PREDICTION);
    ASSERT_NE(action.type, ActionType::COMMIT_HIRAGANA);
}

TEST(bug_ctrl_enter_before_enter) {
    // Ctrl+Enter must take priority over Enter in converting mode
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    auto ctrl_enter = sm.handleKey(makeKey(KEY_RETURN, true));
    ASSERT_EQ(ctrl_enter.type, ActionType::START_LLM_CONVERT);
    auto plain_enter = sm.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(plain_enter.type, ActionType::COMMIT_CONVERSION);
}

TEST(reset_returns_to_idle) {
    InputStateMachine sm;
    sm.setBufferSize(10);
    sm.setConverting(true, true);
    sm.setPredictionAvailable(true);
    sm.setContinuationAvailable(true);
    sm.reset();
    ASSERT_EQ(sm.currentState(), State::IDLE);
}

int main() {
    std::cout << "=== InputStateMachine Unit Tests ===\n\n";

    // IDLE
    RUN(idle_initial_state);
    RUN(idle_ctrl_tab_fetches_continuation);
    RUN(idle_printable_adds_char);
    RUN(idle_other_key_passes_through);
    RUN(idle_release_passes_through);

    // INPUT
    RUN(input_space_starts_conversion);
    RUN(input_enter_commits_hiragana);
    RUN(input_ctrl_enter_starts_llm);
    RUN(input_escape_cancels);
    RUN(input_backspace);
    RUN(input_char_appends);

    // INPUT_PREDICT
    RUN(input_predict_tab_selects);
    RUN(input_predict_down_navigates);
    RUN(input_predict_up_navigates);
    RUN(input_predict_space_converts);
    RUN(input_predict_enter_commits_hiragana);

    // CONVERTING
    RUN(converting_enter_commits);
    RUN(converting_ctrl_enter_switches_to_llm);
    RUN(converting_space_next_candidate);
    RUN(converting_escape_back_to_input);
    RUN(converting_right_next_segment);
    RUN(converting_left_prev_segment);
    RUN(converting_shift_right_extends);
    RUN(converting_shift_left_shrinks);
    RUN(converting_down_next_candidate);
    RUN(converting_up_prev_candidate);
    RUN(converting_number_selects);

    // CONVERTING_LLM
    RUN(converting_llm_state);
    RUN(converting_llm_enter_commits);
    RUN(converting_llm_ctrl_enter_retriggers);

    // CONTINUATION
    RUN(continuation_state);
    RUN(continuation_down_navigates);
    RUN(continuation_up_navigates);
    RUN(continuation_tab_selects);
    RUN(continuation_enter_selects);
    RUN(continuation_escape_dismisses);
    RUN(continuation_other_key_dismisses_and_processes);

    // Prediction select edge cases
    RUN(predict_tab_single_candidate);
    RUN(predict_tab_no_prior_navigation);
    RUN(predict_tab_after_navigation);
    RUN(predict_enter_does_not_select_prediction);
    RUN(predict_space_converts_not_selects);
    RUN(predict_backspace_clears_char_not_select);
    RUN(predict_escape_cancels_input);
    RUN(predict_char_adds_to_buffer);

    // Commit protocol invariants
    RUN(protocol_select_prediction_not_commit_hiragana);
    RUN(protocol_select_continuation_is_distinct_action);

    // Bug regressions
    RUN(bug_continuation_does_not_steal_converting_keys);
    RUN(bug_prediction_select_is_separate_from_hiragana_commit);
    RUN(bug_ctrl_enter_before_enter);
    RUN(reset_returns_to_idle);

    std::cout << "\n=== Results: " << passed << " passed, " << failed << " failed ===\n";
    return failed;
}
