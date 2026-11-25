"""Tests for merging repeated log lines (android/app/src/main/python/log_repeats.py)."""
import logging
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
sys.path.insert(0, os.path.join(ROOT, 'android', 'app', 'src', 'main', 'python'))

import log_repeats  # noqa: E402


class _Lines(logging.Handler):
    def __init__(self):
        super().__init__()
        self.lines = []

    def emit(self, record):
        self.lines.append(record.getMessage())


def _logger(clock=None):
    handler = _Lines()
    if clock is None:
        log_repeats.attach(handler)
    else:
        handler.addFilter(log_repeats.RepeatFilter(handler, clock=clock))
    log = logging.getLogger("test_log_repeats.%d" % id(handler))
    log.propagate = False
    log.setLevel(logging.DEBUG)
    log.addHandler(handler)
    return log, handler.lines


def test_lines_that_differ_only_by_port_are_merged():
    log, lines = _logger()
    for port in (41976, 42014, 42022):
        log.info("[127.0.0.1:%d] DC2 -> trying CF proxy", port)
    log.info("[127.0.0.1:42030] DC2 WS session closed (normal)")
    assert lines == [
        "[127.0.0.1:41976] DC2 -> trying CF proxy",
        "(the line above repeated 2 more times, ports aside)",
        "[127.0.0.1:42030] DC2 WS session closed (normal)",
    ]


def test_different_lines_and_levels_are_kept():
    log, lines = _logger()
    log.info("[127.0.0.1:1] DC2 -> trying CF proxy")
    log.info("[127.0.0.1:2] DC4 -> trying CF proxy")
    log.warning("[127.0.0.1:3] DC4 -> trying CF proxy")
    log.info("stats: total=1")
    log.info("stats: total=1")  # no port to ignore, but the same line: merged
    log.info("stats: total=2")
    assert lines == [
        "[127.0.0.1:1] DC2 -> trying CF proxy",
        "[127.0.0.1:2] DC4 -> trying CF proxy",
        "[127.0.0.1:3] DC4 -> trying CF proxy",
        "stats: total=1",
        "(the line above repeated 1 more time, ports aside)",
        "stats: total=2",
    ]


def test_a_long_run_is_counted_every_minute():
    now = [0.0]
    log, lines = _logger(clock=lambda: now[0])
    for second in range(0, 150, 10):
        now[0] = float(second)
        log.info("[127.0.0.1:%d] DC2 WS pool unavailable -> fallback", 1000 + second)
    log.info("done")
    assert lines == [
        "[127.0.0.1:1000] DC2 WS pool unavailable -> fallback",
        "(the line above repeated 6 more times, ports aside)",   # at 60 s
        "(the line above repeated 6 more times, ports aside)",   # at 120 s
        "(the line above repeated 2 more times, ports aside)",   # ended by "done"
        "done",
    ]


def test_summaries_go_through_the_handlers_other_filters():
    handler = _Lines()
    log_repeats.attach(handler)
    seen = []
    handler.addFilter(lambda record: seen.append(record.getMessage()) or True)
    log = logging.getLogger("test_log_repeats.filters")
    log.propagate = False
    log.addHandler(handler)
    log.setLevel(logging.INFO)
    log.info("[127.0.0.1:1] x")
    log.info("[127.0.0.1:2] x")
    log.info("y")
    assert seen == ["[127.0.0.1:1] x", "(the line above repeated 1 more time, ports aside)", "y"]
