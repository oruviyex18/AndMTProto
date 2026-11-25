"""
Merges repeated log lines (AndMTProto only).

Telegram opens many connections at once, and the core logs the same line for
each, differing only in the client's port ("[127.0.0.1:41976] DC2 -> trying CF
proxy"). A run of such lines in a row is written once, then one line saying how
many more there were; a long run says so every SUMMARY_EVERY_S, so the log
still shows that it went on. Lines that differ in anything else, or come from
another level, are written as usual.

Each handler gets its own RepeatFilter (attach() does that): the filter keeps
the run it is in and writes the summary through its own handler.
"""
from __future__ import annotations

import logging
import re
import threading
import time
from typing import Optional, Tuple

SUMMARY_EVERY_S = 60.0

# "[127.0.0.1:41976]" and the like: the client's address in the core's lines.
_CLIENT_PORT = re.compile(r"(\[(?:\d{1,3}\.){3}\d{1,3}|\[[0-9a-fA-F:]*\]):\d{1,5}\]")
_SUMMARY_ATTR = "_andmtproto_repeat_summary"


def _key(record: logging.LogRecord) -> Tuple[int, str, str]:
    return record.levelno, record.name, _CLIENT_PORT.sub(r"\1:*]", record.getMessage())


class RepeatFilter(logging.Filter):
    def __init__(self, handler: logging.Handler, clock=time.monotonic) -> None:
        super().__init__()
        self._handler = handler
        self._clock = clock
        self._lock = threading.Lock()
        self._last: Optional[Tuple[int, str, str]] = None
        self._last_record: Optional[logging.LogRecord] = None
        self._repeats = 0
        self._since = 0.0

    def filter(self, record: logging.LogRecord) -> bool:
        if getattr(record, _SUMMARY_ATTR, False):
            return True
        key = _key(record)
        with self._lock:
            repeat = key == self._last
            if repeat:
                self._repeats += 1
                if self._clock() - self._since < SUMMARY_EVERY_S:
                    return False
            # A long run's count so far, or the count of the run this line ends.
            summary = self._summary()
            self._since = self._clock()
            if not repeat:
                self._last, self._last_record = key, record
        if summary is not None:
            self._handler.handle(summary)
        return not repeat

    def _summary(self) -> Optional[logging.LogRecord]:
        """The "repeated" line for the run so far, or None; starts a new count."""
        if not self._repeats or self._last_record is None:
            return None
        last = self._last_record
        n, self._repeats = self._repeats, 0
        record = logging.LogRecord(
            last.name, last.levelno, last.pathname, last.lineno,
            "(the line above repeated %d more time%s, ports aside)", (n, "" if n == 1 else "s"),
            None)
        setattr(record, _SUMMARY_ATTR, True)
        return record


def attach(handler: logging.Handler) -> None:
    """Merges repeated lines on `handler`; add it before the censoring filters
    so the summary goes through them too."""
    handler.addFilter(RepeatFilter(handler))
