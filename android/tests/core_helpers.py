"""Helpers for tests that start the real core (test_android_entry, test_ws_pool_control)."""
import json
import socket
import time


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def _cfg(**overrides) -> str:
    cfg = {
        "port": _free_port(),
        "secret": "0123456789abcdef0123456789abcdef",
        # Keep the test offline: no CF domain refresh, no WS pool warmup.
        "cfproxy": False,
        "pool_size": 0,
    }
    cfg.update(overrides)
    return json.dumps(cfg)


def _wait(predicate, timeout=5.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.05)
    return False


def _listening(port: int) -> bool:
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=0.2):
            return True
    except OSError:
        return False
