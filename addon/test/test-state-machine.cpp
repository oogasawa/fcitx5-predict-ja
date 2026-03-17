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

// === Ctrl+Tab continuation full flow ===

TEST(flow_ctrl_tab_to_select) {
    // Simulate: IDLE -> Ctrl+Tab -> continuation arrives -> Down -> Tab select
    InputStateMachine sm;
    ASSERT_EQ(sm.currentState(), State::IDLE);

    // Step 1: Ctrl+Tab triggers fetch
    auto a1 = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_EQ(a1.type, ActionType::FETCH_CONTINUATION);

    // Step 2: Continuation candidates arrive (simulated by engine setting flag)
    sm.setContinuationAvailable(true);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);

    // Step 3: Navigate down
    auto a2 = sm.handleKey(makeKey(KEY_DOWN));
    ASSERT_EQ(a2.type, ActionType::NAV_CONTINUATION_DOWN);

    // Step 4: Select with Tab
    auto a3 = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(a3.type, ActionType::SELECT_CONTINUATION);
}

TEST(flow_ctrl_tab_to_enter_select) {
    // Same flow but select with Enter instead of Tab
    InputStateMachine sm;
    sm.handleKey(makeKey(KEY_TAB, true));
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(a.type, ActionType::SELECT_CONTINUATION);
}

TEST(flow_ctrl_tab_to_escape) {
    // Ctrl+Tab -> candidates arrive -> Escape dismisses
    InputStateMachine sm;
    sm.handleKey(makeKey(KEY_TAB, true));
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_ESCAPE));
    ASSERT_EQ(a.type, ActionType::DISMISS_CONTINUATION);
}

TEST(flow_ctrl_tab_then_type_dismisses) {
    // Ctrl+Tab -> candidates arrive -> user starts typing -> dismiss and add char
    InputStateMachine sm;
    sm.handleKey(makeKey(KEY_TAB, true));
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeChar('k'));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
}

TEST(flow_ctrl_tab_no_candidates) {
    // Ctrl+Tab but no candidates arrive -> stays in IDLE, can type normally
    InputStateMachine sm;
    sm.handleKey(makeKey(KEY_TAB, true));
    // Candidates never arrive (setContinuationAvailable not called)
    ASSERT_EQ(sm.currentState(), State::IDLE);
    auto a = sm.handleKey(makeChar('a'));
    ASSERT_EQ(a.type, ActionType::ADD_CHAR);
}

TEST(flow_ctrl_tab_with_buffer_does_not_trigger) {
    // Ctrl+Tab with non-empty buffer should NOT trigger continuation
    InputStateMachine sm;
    sm.setBufferSize(5);
    ASSERT_EQ(sm.currentState(), State::INPUT);
    auto a = sm.handleKey(makeKey(KEY_TAB, true));
    // In INPUT state, Ctrl+Tab is not handled -> passes through or ignored
    ASSERT_NE(a.type, ActionType::FETCH_CONTINUATION);
}

// === Ctrl+Tab CONTINUATION: exhaustive key tests ===
// Every key that can be pressed while in CONTINUATION state.

TEST(cont_key_down) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
    auto a = sm.handleKey(makeKey(KEY_DOWN));
    ASSERT_EQ(a.type, ActionType::NAV_CONTINUATION_DOWN);
}

TEST(cont_key_up) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_UP));
    ASSERT_EQ(a.type, ActionType::NAV_CONTINUATION_UP);
}

TEST(cont_key_tab) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(a.type, ActionType::SELECT_CONTINUATION);
}

TEST(cont_key_enter) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(a.type, ActionType::SELECT_CONTINUATION);
}

TEST(cont_key_ctrl_enter) {
    // Ctrl+Enter in CONTINUATION: keySym is RETURN, matches SELECT
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_RETURN, true));
    ASSERT_EQ(a.type, ActionType::SELECT_CONTINUATION);
}

TEST(cont_key_ctrl_tab) {
    // Ctrl+Tab in CONTINUATION: keySym is TAB, matches SELECT
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_EQ(a.type, ActionType::SELECT_CONTINUATION);
}

TEST(cont_key_escape) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_ESCAPE));
    ASSERT_EQ(a.type, ActionType::DISMISS_CONTINUATION);
}

TEST(cont_key_space) {
    // Space is not Down/Up/Tab/Enter/Escape -> DISMISS_AND_PROCESS
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_SPACE));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
}

TEST(cont_key_backspace) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_BACKSPACE));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
}

TEST(cont_key_left) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_LEFT));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
}

TEST(cont_key_right) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_RIGHT));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
}

TEST(cont_key_shift_left) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_LEFT, false, true));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
}

TEST(cont_key_shift_right) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_RIGHT, false, true));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
}

TEST(cont_key_printable_a) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeChar('a'));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
}

TEST(cont_key_printable_k) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeChar('k'));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
}

TEST(cont_key_number_1) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_1));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
}

TEST(cont_key_number_9) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_9));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
}

TEST(cont_key_release_ignored) {
    // Key releases should always pass through, even in CONTINUATION
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeRelease(KEY_TAB));
    ASSERT_EQ(a.type, ActionType::PASS_THROUGH);
}

// === Ctrl+Tab CONTINUATION: multi-step navigation sequences ===

TEST(cont_nav_down_down_down_tab) {
    // Navigate 3 items then select
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    ASSERT_EQ(sm.handleKey(makeKey(KEY_DOWN)).type, ActionType::NAV_CONTINUATION_DOWN);
    ASSERT_EQ(sm.handleKey(makeKey(KEY_DOWN)).type, ActionType::NAV_CONTINUATION_DOWN);
    ASSERT_EQ(sm.handleKey(makeKey(KEY_DOWN)).type, ActionType::NAV_CONTINUATION_DOWN);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
    auto a = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(a.type, ActionType::SELECT_CONTINUATION);
}

TEST(cont_nav_down_up_down_tab) {
    // Navigate back and forth then select
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    ASSERT_EQ(sm.handleKey(makeKey(KEY_DOWN)).type, ActionType::NAV_CONTINUATION_DOWN);
    ASSERT_EQ(sm.handleKey(makeKey(KEY_UP)).type, ActionType::NAV_CONTINUATION_UP);
    ASSERT_EQ(sm.handleKey(makeKey(KEY_DOWN)).type, ActionType::NAV_CONTINUATION_DOWN);
    auto a = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(a.type, ActionType::SELECT_CONTINUATION);
}

TEST(cont_nav_down_down_enter) {
    // Navigate then select with Enter
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.handleKey(makeKey(KEY_DOWN));
    sm.handleKey(makeKey(KEY_DOWN));
    auto a = sm.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(a.type, ActionType::SELECT_CONTINUATION);
}

TEST(cont_nav_down_down_escape) {
    // Navigate then dismiss
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.handleKey(makeKey(KEY_DOWN));
    sm.handleKey(makeKey(KEY_DOWN));
    auto a = sm.handleKey(makeKey(KEY_ESCAPE));
    ASSERT_EQ(a.type, ActionType::DISMISS_CONTINUATION);
}

TEST(cont_nav_down_then_type_char) {
    // Navigate then type dismisses
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.handleKey(makeKey(KEY_DOWN));
    sm.handleKey(makeKey(KEY_DOWN));
    auto a = sm.handleKey(makeChar('x'));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
}

TEST(cont_nav_up_only) {
    // Up without prior Down (wrapping or no-op in engine)
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_UP));
    ASSERT_EQ(a.type, ActionType::NAV_CONTINUATION_UP);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
}

TEST(cont_nav_many_ups_then_select) {
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.handleKey(makeKey(KEY_UP));
    sm.handleKey(makeKey(KEY_UP));
    sm.handleKey(makeKey(KEY_UP));
    sm.handleKey(makeKey(KEY_UP));
    sm.handleKey(makeKey(KEY_UP));
    auto a = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(a.type, ActionType::SELECT_CONTINUATION);
}

TEST(cont_nav_interleaved_down_up) {
    // Rapid up/down interleaving stays in CONTINUATION
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    for (int i = 0; i < 10; i++) {
        ASSERT_EQ(sm.currentState(), State::CONTINUATION);
        sm.handleKey(makeKey(KEY_DOWN));
        sm.handleKey(makeKey(KEY_UP));
    }
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
    auto a = sm.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(a.type, ActionType::SELECT_CONTINUATION);
}

// === Ctrl+Tab CONTINUATION: entry from every state ===

TEST(cont_entry_from_idle) {
    // Standard entry: IDLE -> Ctrl+Tab -> FETCH -> candidates arrive -> CONTINUATION
    InputStateMachine sm;
    ASSERT_EQ(sm.currentState(), State::IDLE);
    auto a = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_EQ(a.type, ActionType::FETCH_CONTINUATION);
    sm.setContinuationAvailable(true);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
}

TEST(cont_entry_blocked_from_input) {
    // Ctrl+Tab in INPUT state: buffer > 0 -> should NOT fetch continuation
    InputStateMachine sm;
    sm.setBufferSize(3);
    ASSERT_EQ(sm.currentState(), State::INPUT);
    auto a = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_NE(a.type, ActionType::FETCH_CONTINUATION);
}

TEST(cont_entry_blocked_from_input_predict) {
    // Ctrl+Tab in INPUT_PREDICT: predictions showing, buffer > 0
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setPredictionAvailable(true);
    ASSERT_EQ(sm.currentState(), State::INPUT_PREDICT);
    auto a = sm.handleKey(makeKey(KEY_TAB, true));
    // In INPUT_PREDICT, Tab (even with Ctrl) -> SELECT_PREDICTION
    // because predictionAvailable_ is true and handleInput checks Tab first
    ASSERT_NE(a.type, ActionType::FETCH_CONTINUATION);
}

TEST(cont_entry_blocked_from_converting) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, false);
    ASSERT_EQ(sm.currentState(), State::CONVERTING);
    auto a = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_NE(a.type, ActionType::FETCH_CONTINUATION);
}

TEST(cont_entry_blocked_from_converting_llm) {
    InputStateMachine sm;
    sm.setBufferSize(5);
    sm.setConverting(true, true);
    ASSERT_EQ(sm.currentState(), State::CONVERTING_LLM);
    auto a = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_NE(a.type, ActionType::FETCH_CONTINUATION);
}

TEST(cont_entry_blocked_from_continuation) {
    // Already in CONTINUATION: Ctrl+Tab selects (treated as Tab)
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
    auto a = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_EQ(a.type, ActionType::SELECT_CONTINUATION);
    ASSERT_NE(a.type, ActionType::FETCH_CONTINUATION);
}

// === Ctrl+Tab CONTINUATION: state after selection/dismiss ===

TEST(cont_select_then_idle) {
    // After selecting, engine calls reset() -> back to IDLE
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.handleKey(makeKey(KEY_TAB));  // SELECT_CONTINUATION
    sm.reset();
    ASSERT_EQ(sm.currentState(), State::IDLE);
}

TEST(cont_dismiss_then_idle) {
    // After dismiss, engine calls reset() -> back to IDLE
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.handleKey(makeKey(KEY_ESCAPE));  // DISMISS_CONTINUATION
    sm.setContinuationAvailable(false);
    ASSERT_EQ(sm.currentState(), State::IDLE);
}

TEST(cont_dismiss_and_process_then_input) {
    // Dismiss by typing -> engine processes key -> now in INPUT
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeChar('k'));  // DISMISS_AND_PROCESS
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
    // Engine would dismiss continuation and re-process 'k':
    sm.setContinuationAvailable(false);
    sm.setBufferSize(1);  // 'k' was added
    ASSERT_EQ(sm.currentState(), State::INPUT);
}

// === Ctrl+Tab CONTINUATION: re-trigger after dismiss ===

TEST(cont_retrigger_after_escape) {
    // Dismiss with Escape -> Ctrl+Tab again -> should fetch again
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.handleKey(makeKey(KEY_ESCAPE));
    sm.setContinuationAvailable(false);
    ASSERT_EQ(sm.currentState(), State::IDLE);
    auto a = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_EQ(a.type, ActionType::FETCH_CONTINUATION);
}

TEST(cont_retrigger_after_select) {
    // Select -> reset -> Ctrl+Tab -> should fetch again
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.handleKey(makeKey(KEY_TAB));
    sm.reset();
    ASSERT_EQ(sm.currentState(), State::IDLE);
    auto a = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_EQ(a.type, ActionType::FETCH_CONTINUATION);
}

TEST(cont_retrigger_after_dismiss_and_type) {
    // Dismiss by typing -> type more -> cancel -> Ctrl+Tab
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.handleKey(makeChar('a'));  // DISMISS_AND_PROCESS
    sm.setContinuationAvailable(false);
    sm.setBufferSize(1);
    ASSERT_EQ(sm.currentState(), State::INPUT);
    // User cancels input with Escape
    sm.handleKey(makeKey(KEY_ESCAPE));
    sm.setBufferSize(0);
    ASSERT_EQ(sm.currentState(), State::IDLE);
    // Re-trigger Ctrl+Tab
    auto a = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_EQ(a.type, ActionType::FETCH_CONTINUATION);
}

// === Ctrl+Tab CONTINUATION: full round-trip flows ===

TEST(flow_full_idle_fetch_nav_select_idle) {
    // Complete: IDLE -> Ctrl+Tab -> fetch -> arrive -> Down -> Tab -> reset -> IDLE
    InputStateMachine sm;
    ASSERT_EQ(sm.currentState(), State::IDLE);
    auto a1 = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_EQ(a1.type, ActionType::FETCH_CONTINUATION);
    ASSERT_EQ(sm.currentState(), State::IDLE);  // still IDLE until candidates arrive
    sm.setContinuationAvailable(true);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
    auto a2 = sm.handleKey(makeKey(KEY_DOWN));
    ASSERT_EQ(a2.type, ActionType::NAV_CONTINUATION_DOWN);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
    auto a3 = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(a3.type, ActionType::SELECT_CONTINUATION);
    sm.reset();
    ASSERT_EQ(sm.currentState(), State::IDLE);
}

TEST(flow_full_idle_fetch_nav_enter_idle) {
    // IDLE -> fetch -> arrive -> Down,Down -> Enter select -> reset -> IDLE
    InputStateMachine sm;
    sm.handleKey(makeKey(KEY_TAB, true));
    sm.setContinuationAvailable(true);
    sm.handleKey(makeKey(KEY_DOWN));
    sm.handleKey(makeKey(KEY_DOWN));
    auto a = sm.handleKey(makeKey(KEY_RETURN));
    ASSERT_EQ(a.type, ActionType::SELECT_CONTINUATION);
    sm.reset();
    ASSERT_EQ(sm.currentState(), State::IDLE);
}

TEST(flow_full_idle_fetch_escape_idle) {
    // IDLE -> fetch -> arrive -> Escape dismiss -> IDLE
    InputStateMachine sm;
    sm.handleKey(makeKey(KEY_TAB, true));
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeKey(KEY_ESCAPE));
    ASSERT_EQ(a.type, ActionType::DISMISS_CONTINUATION);
    sm.setContinuationAvailable(false);
    ASSERT_EQ(sm.currentState(), State::IDLE);
}

TEST(flow_full_idle_fetch_type_input) {
    // IDLE -> fetch -> arrive -> type 'k' -> dismiss -> INPUT
    InputStateMachine sm;
    sm.handleKey(makeKey(KEY_TAB, true));
    sm.setContinuationAvailable(true);
    auto a = sm.handleKey(makeChar('k'));
    ASSERT_EQ(a.type, ActionType::DISMISS_AND_PROCESS);
    sm.setContinuationAvailable(false);
    sm.setBufferSize(1);
    ASSERT_EQ(sm.currentState(), State::INPUT);
}

TEST(flow_full_idle_fetch_no_arrive_type) {
    // IDLE -> Ctrl+Tab -> no candidates -> user types -> INPUT
    InputStateMachine sm;
    sm.handleKey(makeKey(KEY_TAB, true));
    // No setContinuationAvailable
    ASSERT_EQ(sm.currentState(), State::IDLE);
    auto a = sm.handleKey(makeChar('a'));
    ASSERT_EQ(a.type, ActionType::ADD_CHAR);
    sm.setBufferSize(1);
    ASSERT_EQ(sm.currentState(), State::INPUT);
}

TEST(flow_full_select_then_type_then_convert) {
    // Ctrl+Tab select -> type new input -> Space convert
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.handleKey(makeKey(KEY_TAB));  // select
    sm.reset();
    ASSERT_EQ(sm.currentState(), State::IDLE);
    sm.handleKey(makeChar('a'));
    sm.setBufferSize(1);
    sm.handleKey(makeChar('b'));
    sm.setBufferSize(2);
    ASSERT_EQ(sm.currentState(), State::INPUT);
    auto a = sm.handleKey(makeKey(KEY_SPACE));
    ASSERT_EQ(a.type, ActionType::START_CONVERSION);
}

TEST(flow_full_select_then_retrigger) {
    // Ctrl+Tab -> select -> reset -> Ctrl+Tab again -> fetch again -> select again
    InputStateMachine sm;
    // First round
    sm.handleKey(makeKey(KEY_TAB, true));
    sm.setContinuationAvailable(true);
    sm.handleKey(makeKey(KEY_DOWN));
    sm.handleKey(makeKey(KEY_TAB));
    sm.reset();
    ASSERT_EQ(sm.currentState(), State::IDLE);
    // Second round
    auto a1 = sm.handleKey(makeKey(KEY_TAB, true));
    ASSERT_EQ(a1.type, ActionType::FETCH_CONTINUATION);
    sm.setContinuationAvailable(true);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
    auto a2 = sm.handleKey(makeKey(KEY_TAB));
    ASSERT_EQ(a2.type, ActionType::SELECT_CONTINUATION);
}

// === Ctrl+Tab CONTINUATION: race conditions / timing edge cases ===

TEST(cont_race_candidates_arrive_during_typing) {
    // User triggered Ctrl+Tab, then started typing before candidates arrived
    // setContinuationAvailable(true) called but buffer > 0 -> CONTINUATION not activated
    InputStateMachine sm;
    sm.handleKey(makeKey(KEY_TAB, true));  // fetch
    // User starts typing before candidates arrive
    sm.handleKey(makeChar('a'));
    sm.setBufferSize(1);
    ASSERT_EQ(sm.currentState(), State::INPUT);
    // Candidates arrive now, but buffer > 0 -> state is INPUT, not CONTINUATION
    sm.setContinuationAvailable(true);
    ASSERT_EQ(sm.currentState(), State::INPUT);
    // Continuation flag is set but inactive because buffer > 0
    auto a = sm.handleKey(makeKey(KEY_TAB));
    // No prediction available, so Tab in INPUT is not handled -> PASS_THROUGH
    ASSERT_NE(a.type, ActionType::SELECT_CONTINUATION);
}

TEST(cont_race_candidates_arrive_after_buffer_cleared) {
    // User typed, then cleared buffer with Escape, THEN continuation arrives
    InputStateMachine sm;
    sm.handleKey(makeKey(KEY_TAB, true));
    sm.handleKey(makeChar('a'));
    sm.setBufferSize(1);
    sm.handleKey(makeKey(KEY_ESCAPE));  // cancel input
    sm.setBufferSize(0);
    ASSERT_EQ(sm.currentState(), State::IDLE);
    // Now continuation arrives (late)
    sm.setContinuationAvailable(true);
    // buffer == 0 and continuation available -> CONTINUATION
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
}

TEST(cont_set_then_buffer_grows) {
    // Continuation available, then buffer grows -> leaves CONTINUATION
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
    sm.setBufferSize(3);
    // buffer > 0 takes priority: state is INPUT, not CONTINUATION
    ASSERT_EQ(sm.currentState(), State::INPUT);
}

TEST(cont_set_then_buffer_grows_then_shrinks) {
    // Continuation flag stays, buffer goes up then back to 0
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
    sm.setBufferSize(5);
    ASSERT_EQ(sm.currentState(), State::INPUT);
    sm.setBufferSize(0);
    // Back to CONTINUATION because flag is still set
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
}

// === Ctrl+Tab CONTINUATION: interaction with other state flags ===

TEST(cont_with_prediction_flag) {
    // Both continuation and prediction flags set, buffer empty
    // CONTINUATION takes priority (checked first in currentState)
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.setPredictionAvailable(true);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
}

TEST(cont_with_prediction_and_buffer) {
    // Continuation + prediction flags set, buffer > 0
    // buffer > 0 -> continuation not active, prediction is -> INPUT_PREDICT
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.setPredictionAvailable(true);
    sm.setBufferSize(5);
    ASSERT_EQ(sm.currentState(), State::INPUT_PREDICT);
}

TEST(cont_with_converting_flag) {
    // Continuation + converting flags set, buffer > 0
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.setConverting(true, false);
    sm.setBufferSize(5);
    ASSERT_EQ(sm.currentState(), State::CONVERTING);
}

TEST(cont_with_converting_llm_flag) {
    // Continuation + converting LLM flags set, buffer > 0
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.setConverting(true, true);
    sm.setBufferSize(5);
    ASSERT_EQ(sm.currentState(), State::CONVERTING_LLM);
}

TEST(cont_with_all_flags_set_buffer_empty) {
    // All flags set but buffer empty: CONTINUATION wins
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.setPredictionAvailable(true);
    sm.setConverting(true, true);
    sm.setBufferSize(0);
    ASSERT_EQ(sm.currentState(), State::CONTINUATION);
}

TEST(cont_with_all_flags_set_buffer_nonempty) {
    // All flags set and buffer > 0: CONVERTING_LLM wins
    InputStateMachine sm;
    sm.setContinuationAvailable(true);
    sm.setPredictionAvailable(true);
    sm.setConverting(true, true);
    sm.setBufferSize(5);
    ASSERT_EQ(sm.currentState(), State::CONVERTING_LLM);
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

    // Ctrl+Tab continuation full flow
    RUN(flow_ctrl_tab_to_select);
    RUN(flow_ctrl_tab_to_enter_select);
    RUN(flow_ctrl_tab_to_escape);
    RUN(flow_ctrl_tab_then_type_dismisses);
    RUN(flow_ctrl_tab_no_candidates);
    RUN(flow_ctrl_tab_with_buffer_does_not_trigger);

    // Ctrl+Tab CONTINUATION: exhaustive key tests
    RUN(cont_key_down);
    RUN(cont_key_up);
    RUN(cont_key_tab);
    RUN(cont_key_enter);
    RUN(cont_key_ctrl_enter);
    RUN(cont_key_ctrl_tab);
    RUN(cont_key_escape);
    RUN(cont_key_space);
    RUN(cont_key_backspace);
    RUN(cont_key_left);
    RUN(cont_key_right);
    RUN(cont_key_shift_left);
    RUN(cont_key_shift_right);
    RUN(cont_key_printable_a);
    RUN(cont_key_printable_k);
    RUN(cont_key_number_1);
    RUN(cont_key_number_9);
    RUN(cont_key_release_ignored);

    // Ctrl+Tab CONTINUATION: multi-step navigation
    RUN(cont_nav_down_down_down_tab);
    RUN(cont_nav_down_up_down_tab);
    RUN(cont_nav_down_down_enter);
    RUN(cont_nav_down_down_escape);
    RUN(cont_nav_down_then_type_char);
    RUN(cont_nav_up_only);
    RUN(cont_nav_many_ups_then_select);
    RUN(cont_nav_interleaved_down_up);

    // Ctrl+Tab CONTINUATION: entry from every state
    RUN(cont_entry_from_idle);
    RUN(cont_entry_blocked_from_input);
    RUN(cont_entry_blocked_from_input_predict);
    RUN(cont_entry_blocked_from_converting);
    RUN(cont_entry_blocked_from_converting_llm);
    RUN(cont_entry_blocked_from_continuation);

    // Ctrl+Tab CONTINUATION: state after selection/dismiss
    RUN(cont_select_then_idle);
    RUN(cont_dismiss_then_idle);
    RUN(cont_dismiss_and_process_then_input);

    // Ctrl+Tab CONTINUATION: re-trigger after dismiss
    RUN(cont_retrigger_after_escape);
    RUN(cont_retrigger_after_select);
    RUN(cont_retrigger_after_dismiss_and_type);

    // Ctrl+Tab CONTINUATION: full round-trip flows
    RUN(flow_full_idle_fetch_nav_select_idle);
    RUN(flow_full_idle_fetch_nav_enter_idle);
    RUN(flow_full_idle_fetch_escape_idle);
    RUN(flow_full_idle_fetch_type_input);
    RUN(flow_full_idle_fetch_no_arrive_type);
    RUN(flow_full_select_then_type_then_convert);
    RUN(flow_full_select_then_retrigger);

    // Ctrl+Tab CONTINUATION: race conditions / timing
    RUN(cont_race_candidates_arrive_during_typing);
    RUN(cont_race_candidates_arrive_after_buffer_cleared);
    RUN(cont_set_then_buffer_grows);
    RUN(cont_set_then_buffer_grows_then_shrinks);

    // Ctrl+Tab CONTINUATION: interaction with other state flags
    RUN(cont_with_prediction_flag);
    RUN(cont_with_prediction_and_buffer);
    RUN(cont_with_converting_flag);
    RUN(cont_with_converting_llm_flag);
    RUN(cont_with_all_flags_set_buffer_empty);
    RUN(cont_with_all_flags_set_buffer_nonempty);

    // Bug regressions
    RUN(bug_continuation_does_not_steal_converting_keys);
    RUN(bug_prediction_select_is_separate_from_hiragana_commit);
    RUN(bug_ctrl_enter_before_enter);
    RUN(reset_returns_to_idle);

    std::cout << "\n=== Results: " << passed << " passed, " << failed << " failed ===\n";
    return failed;
}
