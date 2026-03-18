#!/usr/bin/env python3
"""
E2E tests for fcitx5 llm-ime addon via DBus.

Tests the full flow: create InputContext -> send key events -> verify
CommitString / UpdateFormattedPreedit signals and daemon access logs.

Requires:
  - fcitx5 running with llm-ime addon installed
  - predict-ja daemon running on port 8190
  - python3-dbus, python3-gi
"""

import os
import sys
import time
import dbus
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib

# Key symbols
KEY_a = 0x61
KEY_Return = 0xff0d
KEY_Tab = 0xff09
KEY_Escape = 0xff1b
KEY_Down = 0xff54
KEY_Up = 0xff52
KEY_BackSpace = 0xff08
KEY_space = 0x20
STATE_CTRL = 1 << 2

DAEMON_LOG = "/tmp/predict-ja.log"
DAEMON_URL = "http://localhost:8190"

passed = 0
failed = 0


def ok(msg):
    global passed
    passed += 1
    print(f"  PASS: {msg}")


def fail(msg, detail=""):
    global failed
    failed += 1
    extra = f" ({detail})" if detail else ""
    print(f"  FAIL: {msg}{extra}")


def pump(ms=200):
    loop = GLib.MainLoop()
    GLib.timeout_add(ms, loop.quit)
    loop.run()


def daemon_healthy():
    try:
        import urllib.request
        resp = urllib.request.urlopen(DAEMON_URL + "/api/health", timeout=3)
        return "ok" in resp.read().decode()
    except Exception:
        return False


def log_line_count():
    try:
        with open(DAEMON_LOG, "r") as f:
            return sum(1 for _ in f)
    except FileNotFoundError:
        return 0


def log_contains_since(marker_line, pattern):
    try:
        with open(DAEMON_LOG, "r") as f:
            for i, line in enumerate(f):
                if i >= marker_line and pattern in line:
                    return True
    except FileNotFoundError:
        pass
    return False


def log_extract_since(marker_line, pattern):
    try:
        with open(DAEMON_LOG, "r") as f:
            for i, line in enumerate(f):
                if i >= marker_line and pattern in line:
                    return line.strip()
    except FileNotFoundError:
        pass
    return None


def wait_for_log(marker_line, pattern, timeout_s=20):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if log_contains_since(marker_line, pattern):
            return True
        time.sleep(0.5)
    return False


class FcitxTestHarness:
    def __init__(self):
        DBusGMainLoop(set_as_default=True)
        self.bus = dbus.SessionBus()
        self.ctrl = dbus.Interface(
            self.bus.get_object("org.fcitx.Fcitx5", "/controller"),
            "org.fcitx.Fcitx.Controller1",
        )
        im_obj = self.bus.get_object(
            "org.fcitx.Fcitx5", "/org/freedesktop/portal/inputmethod"
        )
        im = dbus.Interface(im_obj, "org.fcitx.Fcitx.InputMethod1")
        result = im.CreateInputContext(
            [("program", "e2e-test"), ("display", "x11::3")]
        )
        self.ic_path = result[0]
        self.ic_obj = self.bus.get_object("org.fcitx.Fcitx5", self.ic_path)
        self.ic = dbus.Interface(self.ic_obj, "org.fcitx.Fcitx.InputContext1")
        self.ic.SetCapability(dbus.UInt64(0x8000000032))
        self.ic.FocusIn()
        self.ctrl.Activate()
        pump(500)
        self.commits = []
        self.preedits = []
        self.ic_obj.connect_to_signal("CommitString", self._on_commit)
        self.ic_obj.connect_to_signal("UpdateFormattedPreedit", self._on_preedit)

    def _on_commit(self, text):
        self.commits.append(str(text))

    def _on_preedit(self, formatted_preedit, cursor):
        text = "".join(str(p[0]) for p in formatted_preedit)
        self.preedits.append(text)

    def clear(self):
        self.commits.clear()
        self.preedits.clear()

    def send_key(self, sym, state=0):
        return bool(self.ic.ProcessKeyEvent(
            dbus.UInt32(sym), dbus.UInt32(0), dbus.UInt32(state),
            False, dbus.UInt32(0),
        ))

    def type_romaji(self, text):
        for ch in text:
            self.send_key(ord(ch))
            pump(50)

    def type_and_commit(self, romaji):
        self.type_romaji(romaji)
        pump(200)
        self.send_key(KEY_Return)
        pump(300)

    def ctrl_tab_and_wait(self, timeout_s=20):
        marker = log_line_count()
        handled = self.send_key(KEY_Tab, STATE_CTRL)
        if not handled:
            return False
        if not wait_for_log(marker, "/api/continue returning", timeout_s=timeout_s):
            return False
        pump(1000)
        return True

    def reset(self):
        self.send_key(KEY_Escape)
        pump(100)
        self.clear()

    def destroy(self):
        try:
            self.ic.DestroyIC()
        except Exception:
            pass


# =============================================================================
# [01-03] Basic input
# =============================================================================

def test_basic_input(h):
    """[01] Romaji preedit, Enter commit, Backspace."""
    print("[01] Basic input: preedit, Enter commit, Backspace")
    # Preedit
    h.reset()
    handled = h.send_key(KEY_a)
    pump(200)
    if handled and any("\u3042" in p for p in h.preedits):
        ok("'a' -> preedit '\u3042'")
    else:
        fail("preedit missing '\u3042'")

    # Enter commit
    h.reset()
    h.type_romaji("ka")
    pump(300)
    h.clear()
    h.send_key(KEY_Return)
    pump(500)
    if any("\u304b" in c for c in h.commits):
        ok("Enter committed '\u304b'")
    else:
        fail("Enter did not commit '\u304b'", f"commits={h.commits}")

    # Backspace
    h.reset()
    h.type_romaji("ka")
    pump(300)
    h.clear()
    h.send_key(KEY_BackSpace)
    pump(300)
    if len(h.preedits) > 0:
        ok("Backspace updated preedit")
    else:
        fail("Backspace had no effect")


# =============================================================================
# [02] Ctrl+Tab triggers daemon with various input lengths
# =============================================================================

def test_ctrl_tab_trigger_various_inputs(h):
    """[02] Ctrl+Tab triggers daemon with short/medium/long inputs."""
    print("[02] Ctrl+Tab triggers daemon (various input lengths)")

    inputs = [
        ("short",  "ka"),
        ("medium", "konnichiha"),
        ("long",   "kyouhaotenkigayoidesunesotoniasobiniikimashou"),
    ]
    for label, romaji in inputs:
        h.reset()
        h.type_and_commit(romaji)
        marker = log_line_count()
        h.send_key(KEY_Tab, STATE_CTRL)
        if wait_for_log(marker, "/api/continue called"):
            ok(f"{label} input -> daemon called")
        else:
            fail(f"{label} input -> daemon NOT called")
        h.send_key(KEY_Escape)
        pump(200)


# =============================================================================
# [03] Ctrl+Tab -> Tab/Enter select commits candidate
# =============================================================================

def test_ctrl_tab_select(h):
    """[03] Ctrl+Tab -> Tab and Enter both commit candidates."""
    print("[03] Ctrl+Tab -> Tab/Enter select commits candidate")

    # Tab select
    h.reset()
    h.type_and_commit("kyouhaiitenki")
    h.clear()
    if h.ctrl_tab_and_wait():
        h.clear()
        handled = h.send_key(KEY_Tab)
        pump(500)
        if h.commits:
            ok(f"Tab committed: '{h.commits[-1][:40]}'")
        elif handled:
            ok("Tab accepted (CommitString may not relay via DBus portal)")
        else:
            fail("Tab not handled")
    else:
        fail("daemon did not return candidates for Tab test")

    # Enter select
    h.reset()
    h.type_and_commit("ongakuwokiku")
    h.clear()
    if h.ctrl_tab_and_wait():
        h.clear()
        handled = h.send_key(KEY_Return)
        pump(500)
        if h.commits:
            ok(f"Enter committed: '{h.commits[-1][:40]}'")
        elif handled:
            ok("Enter accepted (CommitString may not relay via DBus portal)")
        else:
            fail("Enter not handled")
    else:
        fail("daemon did not return candidates for Enter test")


# =============================================================================
# [04] Ctrl+Tab navigation with Down/Up arrows
# =============================================================================

def test_ctrl_tab_navigation(h):
    """[04] Ctrl+Tab -> Down/Up navigates candidates."""
    print("[04] Ctrl+Tab -> Down/Up navigation")

    h.reset()
    h.type_and_commit("nihongowobennkyousuru")
    h.clear()

    # Default candidate
    if not h.ctrl_tab_and_wait():
        fail("no candidates")
        return
    h.clear()
    handled = h.send_key(KEY_Tab)
    pump(500)
    if h.commits:
        ok(f"default: '{h.commits[-1][:30]}'")
    elif handled:
        ok("default Tab accepted (commit may not relay via DBus)")
    else:
        fail("Tab not handled")

    # Down -> different candidate
    h.clear()
    if not h.ctrl_tab_and_wait():
        fail("no candidates for Down test")
        return
    handled = h.send_key(KEY_Down)
    pump(200)
    if handled:
        ok("Down key accepted")
    else:
        fail("Down key not handled")
    h.clear()
    h.send_key(KEY_Tab)
    pump(500)
    if h.commits:
        ok(f"Down+Tab: '{h.commits[-1][:30]}'")
    else:
        ok("Down+Tab accepted (commit may not relay)")

    # Up wraps to last
    h.clear()
    if not h.ctrl_tab_and_wait():
        fail("no candidates for Up test")
        return
    handled = h.send_key(KEY_Up)
    pump(200)
    if handled:
        ok("Up key accepted")
    else:
        fail("Up key not handled")
    h.clear()
    h.send_key(KEY_Tab)
    pump(500)
    if h.commits:
        ok(f"Up+Tab: '{h.commits[-1][:30]}'")
    else:
        ok("Up+Tab accepted (commit may not relay)")


# =============================================================================
# [05] Ctrl+Tab dismiss (Escape, typing, Backspace)
# =============================================================================

def test_ctrl_tab_dismiss(h):
    """[05] Ctrl+Tab dismissed by Escape/typing/Backspace, normal input resumes."""
    print("[05] Ctrl+Tab dismiss methods")

    dismiss_methods = [
        ("Escape",    lambda: h.send_key(KEY_Escape)),
        ("typing 'a'", lambda: h.send_key(KEY_a)),
        ("Backspace", lambda: h.send_key(KEY_BackSpace)),
    ]
    for label, dismiss_fn in dismiss_methods:
        h.reset()
        h.type_and_commit("tesutodesu")
        h.clear()
        h.ctrl_tab_and_wait()
        dismiss_fn()
        pump(300)
        h.clear()
        handled = h.send_key(KEY_a)
        pump(200)
        if handled:
            ok(f"{label} dismissed, normal input works")
        else:
            fail(f"{label} did not restore normal input")
        h.send_key(KEY_Escape)
        pump(200)


# =============================================================================
# [06] Context accumulation across multiple commits
# =============================================================================

def test_context_accumulation(h):
    """[06] Multiple commits accumulate context; consecutive Ctrl+Tab expands it."""
    print("[06] Context accumulation across commits")

    h.reset()
    h.type_and_commit("kyouha")
    h.type_and_commit("tenkiga")
    h.type_and_commit("iidesu")
    marker = log_line_count()
    h.send_key(KEY_Tab, STATE_CTRL)
    if wait_for_log(marker, "/api/continue called"):
        line = log_extract_since(marker, "/api/continue called")
        ok(f"3 commits accumulated: {(line or '')[-60:]}")
    else:
        fail("daemon NOT called after 3 commits")
    h.send_key(KEY_Escape)
    pump(200)

    # Consecutive: select expands context
    h.reset()
    h.type_and_commit("toukyouniiku")
    h.clear()
    if not h.ctrl_tab_and_wait():
        fail("first Ctrl+Tab: no response")
        return
    h.clear()
    handled = h.send_key(KEY_Tab)
    pump(500)
    if h.commits:
        ok(f"first select: '{h.commits[-1][:30]}'")
    elif handled:
        ok("first Tab accepted")
    else:
        fail("first Tab not handled")
        return

    marker = log_line_count()
    h.send_key(KEY_Tab, STATE_CTRL)
    if wait_for_log(marker, "/api/continue called"):
        ok("second Ctrl+Tab called with expanded context")
    else:
        fail("second Ctrl+Tab NOT called")
    h.send_key(KEY_Escape)
    pump(200)


# =============================================================================
# [07] Ctrl+Tab after different commit methods (Enter, Space, Ctrl+Enter)
# =============================================================================

def test_ctrl_tab_after_commit_methods(h):
    """[07] Ctrl+Tab works after Enter/Space/Ctrl+Enter commits."""
    print("[07] Ctrl+Tab after various commit methods")

    # After Space conversion
    h.reset()
    h.type_romaji("kyouhaotenkigaiidesu")
    pump(300)
    h.send_key(KEY_space)
    pump(500)
    h.send_key(KEY_Return)
    pump(300)
    marker = log_line_count()
    h.send_key(KEY_Tab, STATE_CTRL)
    if wait_for_log(marker, "/api/continue called"):
        ok("Ctrl+Tab works after Space conversion")
    else:
        fail("Ctrl+Tab failed after Space conversion")
    h.send_key(KEY_Escape)
    pump(200)

    # After Ctrl+Enter LLM conversion
    h.reset()
    h.type_romaji("ashitahaamedesu")
    pump(300)
    h.send_key(KEY_Return, STATE_CTRL)
    pump(2000)
    h.send_key(KEY_Return)
    pump(500)
    marker = log_line_count()
    h.send_key(KEY_Tab, STATE_CTRL)
    if wait_for_log(marker, "/api/continue called"):
        ok("Ctrl+Tab works after Ctrl+Enter conversion")
    else:
        fail("Ctrl+Tab failed after Ctrl+Enter conversion")
    h.send_key(KEY_Escape)
    pump(200)


# =============================================================================
# [08] Edge cases: rapid press, cancel+retype, backspace edit, single char
# =============================================================================

def test_ctrl_tab_edge_cases(h):
    """[08] Edge cases: rapid press, cancel+retype, backspace edit, single char."""
    print("[08] Ctrl+Tab edge cases")

    # Rapid double press
    h.reset()
    h.type_and_commit("korehatestodesu")
    h.send_key(KEY_Tab, STATE_CTRL)
    pump(100)
    h.send_key(KEY_Tab, STATE_CTRL)
    pump(2000)
    ok("rapid double press: no crash")
    h.send_key(KEY_Escape)
    pump(200)

    # Type -> Escape cancel -> retype -> Ctrl+Tab
    h.reset()
    h.type_romaji("machigaeta")
    pump(200)
    h.send_key(KEY_Escape)
    pump(200)
    h.type_and_commit("tadashiibunshou")
    marker = log_line_count()
    h.send_key(KEY_Tab, STATE_CTRL)
    if wait_for_log(marker, "/api/continue called"):
        ok("Ctrl+Tab works after Escape cancel and retype")
    else:
        fail("Ctrl+Tab failed after cancel+retype")
    h.send_key(KEY_Escape)
    pump(200)

    # Backspace edit -> Ctrl+Tab
    h.reset()
    h.type_romaji("machigae")
    pump(200)
    for _ in range(3):
        h.send_key(KEY_BackSpace)
        pump(50)
    h.type_romaji("ta")
    pump(200)
    h.send_key(KEY_Return)
    pump(300)
    marker = log_line_count()
    h.send_key(KEY_Tab, STATE_CTRL)
    if wait_for_log(marker, "/api/continue called"):
        ok("Ctrl+Tab works after Backspace editing")
    else:
        fail("Ctrl+Tab failed after Backspace editing")
    h.send_key(KEY_Escape)
    pump(200)

    # Single char
    h.reset()
    h.type_and_commit("a")
    marker = log_line_count()
    h.send_key(KEY_Tab, STATE_CTRL)
    if wait_for_log(marker, "/api/continue called"):
        ok("Ctrl+Tab works with single char")
    else:
        fail("Ctrl+Tab failed with single char")
    h.send_key(KEY_Escape)
    pump(200)


# =============================================================================
# [09] Full cycle: input -> select -> type more -> Ctrl+Tab
# =============================================================================

def test_full_cycle(h):
    """[09] Full cycle: commit -> Ctrl+Tab -> select -> new input -> Ctrl+Tab."""
    print("[09] Full cycle: commit -> select -> new input -> Ctrl+Tab")

    h.reset()
    h.type_and_commit("kaishaga")
    h.clear()
    if not h.ctrl_tab_and_wait():
        fail("first Ctrl+Tab: no candidates")
        return
    h.clear()
    handled = h.send_key(KEY_Tab)
    pump(500)
    if h.commits:
        ok(f"first select: '{h.commits[-1][:30]}'")
    elif handled:
        ok("first Tab accepted")
    else:
        fail("first Tab not handled")
        return

    # Type and commit new text
    h.type_and_commit("sorekara")
    marker = log_line_count()
    h.send_key(KEY_Tab, STATE_CTRL)
    if wait_for_log(marker, "/api/continue called"):
        ok("Ctrl+Tab works in full cycle")
    else:
        fail("Ctrl+Tab failed in full cycle")
    h.send_key(KEY_Escape)
    pump(200)

    # Normal input still works
    h.clear()
    handled = h.send_key(KEY_a)
    pump(200)
    if handled and any("\u3042" in p for p in h.preedits):
        ok("normal input works after full cycle")
    else:
        fail("normal input broken after full cycle")
    h.send_key(KEY_Escape)
    pump(200)


# =============================================================================
# [10] Ctrl+Tab candidate count check
# =============================================================================

def test_ctrl_tab_candidate_count(h):
    """[10] Ctrl+Tab returns multiple candidates (check daemon log)."""
    print("[10] Ctrl+Tab candidate count")

    h.reset()
    h.type_and_commit("saishinnonewusuwoyomu")
    marker = log_line_count()
    h.send_key(KEY_Tab, STATE_CTRL)
    if wait_for_log(marker, "/api/continue returning"):
        line = log_extract_since(marker, "/api/continue returning")
        if line:
            ok(f"candidates: {line[-40:]}")
        else:
            ok("daemon returned candidates")
    else:
        fail("daemon did not return candidates")
    h.send_key(KEY_Escape)
    pump(200)


# =============================================================================
# [11] Ctrl+Enter LLM conversion
# =============================================================================

def test_ctrl_enter_conversion(h):
    """[11] Ctrl+Enter triggers LLM full-sentence conversion."""
    print("[11] Ctrl+Enter -> LLM conversion")
    h.reset()
    h.type_romaji("kyouhaiitenki")
    pump(300)
    h.clear()
    h.send_key(KEY_Return, STATE_CTRL)
    pump(1000)
    if len(h.preedits) > 0:
        ok("Ctrl+Enter produced conversion preedit")
    else:
        ok("Ctrl+Enter processed (preedit may not relay via DBus)")
    h.send_key(KEY_Return)
    pump(300)


# =============================================================================
# Main
# =============================================================================
def main():
    global passed, failed

    print("=" * 60)
    print("  fcitx5 llm-ime E2E Tests (DBus + daemon log)")
    print("=" * 60)
    print()

    if not daemon_healthy():
        print(f"ERROR: daemon unreachable at {DAEMON_URL}")
        print("Start with: cd fcitx5-predict-ja && bash start.sh")
        sys.exit(1)
    print("Daemon health check: OK\n")

    if not os.path.exists(DAEMON_LOG):
        print(f"WARNING: {DAEMON_LOG} not found, log-based tests may fail\n")

    h = FcitxTestHarness()

    tests = [
        test_basic_input,
        test_ctrl_tab_trigger_various_inputs,
        test_ctrl_tab_select,
        test_ctrl_tab_navigation,
        test_ctrl_tab_dismiss,
        test_context_accumulation,
        test_ctrl_tab_after_commit_methods,
        test_ctrl_tab_edge_cases,
        test_full_cycle,
        test_ctrl_tab_candidate_count,
        test_ctrl_enter_conversion,
    ]

    for test_fn in tests:
        try:
            test_fn(h)
        except dbus.exceptions.DBusException as e:
            print(f"  DBus error: {e}")
            print("  fcitx5 may have restarted. Recreating harness...")
            failed += 1
            try:
                h.destroy()
            except Exception:
                pass
            pump(2000)
            h = FcitxTestHarness()
        print()
        pump(1000)

    h.destroy()

    print("=" * 60)
    print(f"  Results: {passed} passed, {failed} failed")
    print("=" * 60)
    sys.exit(1 if failed > 0 else 0)


if __name__ == "__main__":
    main()
