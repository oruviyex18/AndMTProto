"""Tests for how the core's Cloudflare attempts are counted and explained
(android/app/src/main/python/cf_outcomes.py)."""
import asyncio
import json
import os
import sys
import time

import pytest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, 'android', 'app', 'src', 'main', 'python'))

import android_entry  # noqa: E402  (installs cf_outcomes' hooks)
import cf_outcomes  # noqa: E402
from core_helpers import _cfg, _wait  # noqa: E402


def _cf_hosts(*names):
    return [(name, "kws2." + name) for name in names]


def _fake_cf_connect(monkeypatch, opens=(), times_out=()):
    """RawWebSocket.connect as the core's CF code sees it: hosts in `opens` give
    a WebSocket, those in `times_out` time out, the rest are refused."""
    from proxy import cf_websocket, raw_websocket
    tried = []

    async def connect(host, domain, **kwargs):
        tried.append(host)
        name = host.split(".", 1)[1]
        if name in opens:
            return object()
        if name in times_out:
            raise asyncio.TimeoutError()
        if name == "hangs.example":
            await asyncio.sleep(3600)
        raise ConnectionResetError()
    # The base class: the core's CF code reaches it through the adapter's counting subclass.
    monkeypatch.setattr(raw_websocket.RawWebSocket, "connect", staticmethod(connect))
    cf_websocket.reset_cf_timeouts()
    return tried


def _connect_cf(hosts):
    from proxy import bridge
    return asyncio.run(bridge.connect_cf_domains(hosts, label="t", secure=False))


def test_cf_failures_count_the_hosts_the_core_tried(monkeypatch):
    """Through the core's own connect code: each host it tried that failed or
    timed out counts once; a host it skips because it timed out moments ago
    doesn't, and neither does the host that opened."""
    from proxy import cf_websocket
    tried = _fake_cf_connect(monkeypatch, opens={"c.example"}, times_out={"slow.example"})
    try:
        before = cf_outcomes._cf_failures.count
        assert _connect_cf(_cf_hosts("bad.example", "slow.example")) is None
        assert cf_outcomes._cf_failures.count - before == 2
        before, tried[:] = cf_outcomes._cf_failures.count, []
        assert _connect_cf(_cf_hosts("slow.example", "bad.example")) is None
        assert tried == ["kws2.bad.example"]  # slow.example sits out its cooldown
        assert cf_outcomes._cf_failures.count - before == 1
        before, tried[:] = cf_outcomes._cf_failures.count, []
        result = _connect_cf(_cf_hosts("bad.example", "slow.example", "c.example", "d.example"))
        assert result is not None and result[1] == "c.example"
        assert tried == ["kws2.bad.example", "kws2.c.example"]
        assert cf_outcomes._cf_failures.count - before == 1
    finally:
        cf_websocket.reset_cf_timeouts()


def test_cf_failures_count_a_connection_that_tried_no_host(monkeypatch):
    """Every host sits out a timeout: the core fails the connection at once and
    logs nothing; that still counts, once."""
    from proxy import cf_websocket
    tried = _fake_cf_connect(monkeypatch, times_out={"slow.example"})
    try:
        _connect_cf(_cf_hosts("slow.example"))
        before, tried[:] = cf_outcomes._cf_failures.count, []
        assert _connect_cf(_cf_hosts("slow.example")) is None
        assert tried == []
        assert cf_outcomes._cf_failures.count - before == 1
    finally:
        cf_websocket.reset_cf_timeouts()


def test_cf_failures_skip_a_connection_its_client_left(monkeypatch):
    """Telegram dropped the connection while the core dialled: not a CF failure."""
    from proxy import bridge, cf_websocket
    _fake_cf_connect(monkeypatch)

    async def scenario():
        task = asyncio.ensure_future(bridge.connect_cf_domains(
            _cf_hosts("hangs.example"), label="t", secure=False))
        await asyncio.sleep(0.01)
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
    try:
        before = cf_outcomes._cf_failures.count
        asyncio.run(scenario())
        assert cf_outcomes._cf_failures.count == before
    finally:
        cf_websocket.reset_cf_timeouts()


def test_cf_failures_reset_at_each_start(tmp_path, monkeypatch):
    from proxy import cf_websocket
    _fake_cf_connect(monkeypatch)
    try:
        assert android_entry.start(_cfg(), str(tmp_path)) is None
        assert _wait(lambda: json.loads(android_entry.status())["listening"])
        _connect_cf(_cf_hosts("a.example", "b.example", "c.example"))
        android_entry.stats.connections_cfproxy += 1
        st = json.loads(android_entry.status())
        assert (st["cf_failed"], st["cf_ok"]) == (3, 1)
        android_entry.stop()
        assert android_entry.start(_cfg(), str(tmp_path)) is None
        st = json.loads(android_entry.status())
        assert (st["cf_failed"], st["cf_ok"]) == (0, 0)
    finally:
        android_entry.stop()
        cf_websocket.reset_cf_timeouts()


def test_cf_failure_reasons_in_plain_kinds():
    import socket as _socket
    import ssl as _ssl
    from proxy.raw_websocket import WsHandshakeError
    reason = cf_outcomes._cf_failure_reason
    assert reason(asyncio.TimeoutError()) == "timeout"
    assert reason(ConnectionResetError()) == "reset"
    assert reason(_ssl.SSLEOFError()) == "reset"
    assert reason(WsHandshakeError(0, "empty response")) == "reset"
    assert reason(_ssl.SSLCertVerificationError()) == "tls"
    assert reason(_socket.gaierror()) == "dns"
    assert reason(ConnectionRefusedError()) == "refused"
    assert reason(WsHandshakeError(429, "HTTP/1.1 429 Too Many Requests")) == "limit"
    assert reason(WsHandshakeError(403, "HTTP/1.1 403 Forbidden")) == "forbidden"
    assert reason(WsHandshakeError(500, "HTTP/1.1 500 Internal Server Error")) == "server_error"
    assert reason(WsHandshakeError(404, "HTTP/1.1 404 Not Found")) == "http"
    assert reason(OSError(101, "Network is unreachable")) == "network"


def test_cf_recent_tells_cf_proxy_and_worker_apart(monkeypatch):
    """Through the core's own code: CF proxy hosts are recorded by reason, the
    user's Worker domains by their latest outcome; direct WebSockets aren't."""
    from proxy import cf_websocket, pool
    from proxy.raw_websocket import WsHandshakeError
    _fake_cf_connect(monkeypatch, opens={"good.example"}, times_out={"slow.example"})
    monkeypatch.setattr(android_entry.proxy_config, "cfproxy_worker_domains", ["w.example.workers.dev"])
    monkeypatch.setattr(android_entry.proxy_config, "disable_secure", True)
    from proxy import raw_websocket
    cf_connect = raw_websocket.RawWebSocket.connect

    async def connect(host, domain, **kwargs):
        if host == "w.example.workers.dev":
            raise WsHandshakeError(429, "HTTP/1.1 429 Too Many Requests")
        if host == "149.154.167.220":
            raise ConnectionResetError()
        return await cf_connect(host, domain, **kwargs)
    monkeypatch.setattr(raw_websocket.RawWebSocket, "connect", staticmethod(connect))
    with cf_outcomes._cf_events_lock:
        cf_outcomes._cf_events.clear()
    try:
        _connect_cf(_cf_hosts("slow.example", "good.example"))
        worker = pool._CfWorkerPool()
        assert asyncio.run(worker.connect_one(["w.example.workers.dev"], "149.154.167.51", 2)) is None
        with pytest.raises(ConnectionResetError):
            asyncio.run(pool.RawWebSocket.connect("149.154.167.220", "kws2.web.telegram.org"))
        recent = cf_outcomes._cf_recent(time.monotonic())
        assert recent == {"cf": {"timeout": 1, "ok": 1}}
        st = json.loads(android_entry.status())
        assert st["cf_recent"] == recent
        # The daily limit lasts until the reset: one 429 is enough to say so.
        assert st["worker_trouble"] == "limit"
    finally:
        cf_websocket.reset_cf_timeouts()
        with cf_outcomes._cf_events_lock:
            cf_outcomes._cf_events.clear()
            cf_outcomes._worker_outcomes.clear()


def test_worker_trouble_needs_every_worker_failing(monkeypatch):
    monkeypatch.setattr(cf_outcomes, "_worker_outcomes", {})
    record, trouble = cf_outcomes._record_worker, cf_outcomes._worker_trouble
    assert trouble() is None
    record("a.workers.dev", "timeout")
    assert trouble() is None  # one timeout can be a blip
    record("a.workers.dev", "timeout")
    assert trouble() == "timeout"
    record("b.workers.dev", "ok")
    assert trouble() is None  # another Worker still works
    record("b.workers.dev", "server_error")
    record("b.workers.dev", "server_error")
    assert trouble() in ("timeout", "server_error")
    record("a.workers.dev", "ok")
    assert trouble() is None


def test_worker_counts_as_working_only_once_data_comes_back(monkeypatch):
    """Our Worker accepts the WebSocket, then closes it (1011) when it can't reach
    Telegram: that's a failure, not a working Worker."""
    from proxy import pool, raw_websocket
    monkeypatch.setattr(cf_outcomes, "_worker_outcomes", {})
    monkeypatch.setattr(android_entry.proxy_config, "cfproxy_worker_domains", ["w.example.workers.dev"])

    class FakeWs:
        def __init__(self, first):
            self.first = first

        async def recv(self):
            return self.first

    replies = iter([None, None, b"resPQ"])

    async def connect(host, domain, **kwargs):
        return FakeWs(next(replies))
    monkeypatch.setattr(raw_websocket.RawWebSocket, "connect", staticmethod(connect))

    async def open_and_read():
        ws = await pool.RawWebSocket.connect("w.example.workers.dev", "w.example.workers.dev")
        return await ws.recv()

    assert asyncio.run(open_and_read()) is None
    assert cf_outcomes._worker_trouble() is None  # once can be a blip
    assert asyncio.run(open_and_read()) is None
    assert cf_outcomes._worker_trouble() == "worker_closed"
    assert asyncio.run(open_and_read()) == b"resPQ"
    assert cf_outcomes._worker_trouble() is None
