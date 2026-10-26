"""Desktop smoke test for the Android adapter; runs against the upstream proxy/."""
import json
import os
import socket
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, 'android', 'app', 'src', 'main', 'python'))

import android_entry  # noqa: E402


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


def test_start_listen_stop(tmp_path):
    cfg = _cfg()
    port = json.loads(cfg)["port"]
    assert android_entry.start(cfg, str(tmp_path)) is None
    try:
        assert _wait(lambda: _listening(port))
        st = json.loads(android_entry.status())
        assert st["running"] and st["error"] is None
    finally:
        android_entry.stop()
    assert not json.loads(android_entry.status())["running"]
    assert _wait(lambda: not _listening(port))
    assert (tmp_path / 'proxy.log').exists()


def test_restart_same_port(tmp_path):
    cfg = _cfg()
    port = json.loads(cfg)["port"]
    for _ in range(2):
        assert android_entry.start(cfg, str(tmp_path)) is None
        assert _wait(lambda: _listening(port))
        android_entry.stop()


def test_port_in_use_reports_code_and_free_port(tmp_path):
    with socket.socket() as busy:
        busy.bind(('127.0.0.1', 0))
        busy.listen()
        port = busy.getsockname()[1]
        assert android_entry.start(_cfg(port=port), str(tmp_path)) is None
        assert _wait(lambda: not json.loads(android_entry.status())["running"])
        android_entry.stop()
    err = json.loads(android_entry.status())["error"]
    assert err["code"] == "port_in_use"
    assert err["detail"] == str(port)
    suggested = err["suggested_port"]
    assert suggested and suggested > port
    with socket.socket() as s:
        s.bind(('127.0.0.1', suggested))  # really free


def test_success_clears_previous_error(tmp_path):
    android_entry.start(_cfg(secret="xyz"), str(tmp_path))
    assert json.loads(android_entry.status())["error"]
    cfg = _cfg()
    assert android_entry.start(cfg, str(tmp_path)) is None
    try:
        assert json.loads(android_entry.status())["error"] is None
    finally:
        android_entry.stop()


def test_bad_secret_rejected(tmp_path):
    err = json.loads(android_entry.start(_cfg(secret="xyz"), str(tmp_path)))
    assert err["code"] == "bad_secret"
    st = json.loads(android_entry.status())
    assert not st["running"] and st["error"]["code"] == "bad_secret"


def test_bad_port_rejected(tmp_path):
    err = json.loads(android_entry.start(_cfg(port=70000), str(tmp_path)))
    assert err == {"code": "bad_port", "detail": "70000", "suggested_port": None}


def test_bad_dc_ip_rejected(tmp_path):
    err = json.loads(android_entry.start(_cfg(dc_ip=["nonsense"]), str(tmp_path)))
    assert err["code"] == "bad_dc_ip"
    assert err["detail"] == "nonsense"


def test_links():
    secret = "0123456789abcdef0123456789abcdef"
    dd = android_entry.proxy_link(json.dumps({"secret": secret}))
    assert dd == f"tg://proxy?server=127.0.0.1&port=1443&secret=dd{secret}"
    ee = android_entry.proxy_link(json.dumps(
        {"secret": secret, "fake_tls_domain": "example.com"}))
    assert ee.endswith("secret=ee" + secret + b"example.com".hex())
    assert len(android_entry.new_secret()) == 32
