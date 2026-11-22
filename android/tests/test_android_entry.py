"""Desktop smoke test for the Android adapter; runs against the upstream proxy/."""
import asyncio
import json
import re
import os
import socket
import sys
import threading
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


def test_link_converts_idn_fake_tls_domain_to_punycode():
    secret = "0123456789abcdef0123456789abcdef"
    link = android_entry.proxy_link(json.dumps(
        {"secret": secret, "fake_tls_domain": "пример.рф"}))
    assert link.endswith("secret=ee" + secret + b"xn--e1afmkfd.xn--p1ai".hex())


def test_link_never_raises_on_bad_fake_tls_domain():
    secret = "0123456789abcdef0123456789abcdef"
    link = android_entry.proxy_link(json.dumps(
        {"secret": secret, "fake_tls_domain": "bad..domain"}))
    assert link.endswith("secret=dd" + secret)


def test_bad_fake_tls_rejected(tmp_path):
    err = json.loads(android_entry.start(
        _cfg(fake_tls_domain="bad..domain"), str(tmp_path)))
    assert err == {"code": "bad_fake_tls", "detail": "bad..domain",
                   "suggested_port": None}


def test_bad_port_zero_keeps_detail(tmp_path):
    err = json.loads(android_entry.start(_cfg(port=0), str(tmp_path)))
    assert err["code"] == "bad_port" and err["detail"] == "0"


def test_stop_right_after_start_frees_port(tmp_path):
    # stop() must reach the proxy even before its event loop has started.
    for _ in range(5):
        cfg = _cfg()
        port = json.loads(cfg)["port"]
        assert android_entry.start(cfg, str(tmp_path)) is None
        android_entry.stop()
        assert not json.loads(android_entry.status())["running"]
        assert _wait(lambda: not _listening(port))



def _probe_client_hello() -> bytes:
    """The ClientHello upstream's Fake TLS probe sends, captured offline."""
    import asyncio
    from unittest.mock import AsyncMock, Mock, patch

    from proxy import fake_tls

    reader = Mock(read=AsyncMock(return_value=b""))
    writer = Mock(drain=AsyncMock())
    with patch.object(fake_tls.asyncio, "open_connection",
                      AsyncMock(return_value=(reader, writer))):
        asyncio.run(fake_tls.probe_appdata_len("example.com", timeout=1))
    return writer.write.call_args[0][0]


def test_core_tls_offers_only_x25519():
    # Some ISPs' DPI stalls the ~1.5 KB ClientHello with a post-quantum key share
    # (OpenSSL 3.5's default). Upstream limits TLS to X25519 since 1.11.3; android.6
    # patched it in the adapter. Every TLS client in the core must offer X25519 only.
    from proxy import h2_transport, raw_websocket, utils

    hellos = [android_entry.client_hello(ctx) for ctx in (
        utils.create_ssl_context(), utils.create_ssl_context(check_hostname=False),
        h2_transport._default_ssl_context(),
        raw_websocket._ssl_ctx, raw_websocket._ssl_ctx_fronting)]
    hellos.append(_probe_client_hello())
    for hello in hellos:
        assert android_entry.offered_tls_groups(hello) == [0x001d]


def test_offered_tls_groups_sees_post_quantum_default():
    # The check must tell an unrestricted context apart (OpenSSL 3.x offers several groups).
    import ssl
    groups = android_entry.offered_tls_groups(
        android_entry.client_hello(ssl.create_default_context()))
    assert len(groups) > 1 and 0x001d in groups


def _silent_tls_site():
    """A local site that accepts TCP but never answers, like a DPI-stalled one."""
    listener = socket.create_server(("127.0.0.1", 0))
    return listener, listener.getsockname()[1]


def test_stop_during_fake_tls_probe_is_fast_and_restart_works(tmp_path, monkeypatch):
    # Upstream's _run() probes the Fake TLS site for up to 5 s before listening.
    from proxy import fake_tls

    listener, site_port = _silent_tls_site()
    real_probe = fake_tls.probe_appdata_len
    monkeypatch.setattr(
        "proxy.tg_ws_proxy.probe_appdata_len",
        lambda domain: real_probe("127.0.0.1", site_port))
    try:
        cfg = _cfg(fake_tls_domain="example.com")
        port = json.loads(cfg)["port"]
        assert android_entry.start(cfg, str(tmp_path)) is None
        time.sleep(0.3)
        status = json.loads(android_entry.status())
        assert status["running"] and not status["listening"]
        started = time.monotonic()
        android_entry.stop()
        assert time.monotonic() - started < 1.5
        assert not json.loads(android_entry.status())["running"]
        assert not _listening(port)

        cfg2 = _cfg(fake_tls_domain="example.com")
        port2 = json.loads(cfg2)["port"]
        assert android_entry.start(cfg2, str(tmp_path)) is None
        android_entry.stop()
        assert not json.loads(android_entry.status())["running"]
        assert not _listening(port2)
    finally:
        android_entry.stop()
        listener.close()


def test_status_reports_listening(tmp_path):
    cfg = _cfg()
    port = json.loads(cfg)["port"]
    try:
        assert android_entry.start(cfg, str(tmp_path)) is None
        assert _wait(lambda: _listening(port))
        assert _wait(lambda: json.loads(android_entry.status())["listening"])
        st = json.loads(android_entry.status())
        # Nothing routed yet: the readiness probe sends no handshake.
        assert (st["ws"], st["cf"], st["routed"]) == (0, 0, 0)
    finally:
        android_entry.stop()
    assert not json.loads(android_entry.status())["listening"]


def test_connections_last_hour_window():
    from proxy.stats import stats

    android_entry._reset_stats(now=0.0)
    for minute, total in ((10, 5), (40, 12), (70, 20), (100, 26)):
        stats.connections_total = total
        android_entry._record_sample(now=minute * 60.0)
    # At 100 min the hour starts at 40 min, when the total was 12.
    assert android_entry._connections_last_hour() == 26 - 12
    android_entry._reset_stats(now=0.0)
    assert stats.connections_total == 0
    assert android_entry._connections_last_hour() == 0


def test_counters_restart_from_zero(tmp_path):
    from proxy.stats import stats

    stats.connections_total, stats.bytes_down = 1533, 10 ** 6
    cfg = _cfg()
    port = json.loads(cfg)["port"]
    try:
        assert android_entry.start(cfg, str(tmp_path)) is None
        # Not _listening(): its probe connection would be counted too.
        assert _wait(lambda: json.loads(android_entry.status())["listening"])
        with socket.create_connection(("127.0.0.1", port)):
            pass
        assert _wait(lambda: json.loads(android_entry.status())["total"] == 1)
        status = json.loads(android_entry.status())
        assert status["last_hour"] == 1 and status["bytes_down"] == 0
    finally:
        android_entry.stop()


def test_secret_not_in_log(tmp_path):
    secret = "fedcba9876543210fedcba9876543210"
    cfg = _cfg(secret=secret)
    assert android_entry.start(cfg, str(tmp_path)) is None
    try:
        # The core logs the secret and the tg:// link once it listens.
        assert _wait(lambda: json.loads(android_entry.status())["listening"])
    finally:
        android_entry.stop()
    handler = android_entry._log_handler  # opened by the first start in this process
    handler.flush()
    with open(handler.baseFilename, encoding="utf-8") as f:
        text = f.read()
    assert secret not in text.lower()
    assert "Secret:        <secret>" in text and "secret=dd<secret>" in text
    # The app still gets the real link for Telegram.
    assert secret in android_entry.proxy_link(cfg)


def test_secret_filter_ignores_case_and_args():
    android_entry.proxy_config.secret = "0123456789abcdef0123456789abcdef"
    record = android_entry.logging.LogRecord(
        "t", android_entry.logging.INFO, __file__, 1,
        "secret %s and %s", ("0123456789ABCDEF0123456789ABCDEF", 42), None)
    assert android_entry.SecretCensorFilter().filter(record)
    assert record.getMessage() == "secret <secret> and 42"


def test_is_loopback():
    for host in ("127.0.0.1", "127.1.2.3", "localhost", " LOCALHOST ", "::1"):
        assert android_entry._is_loopback(host), host
    for host in ("0.0.0.0", "", "::", "192.168.1.10", "10.0.0.1", "example.com"):
        assert not android_entry._is_loopback(host), host


def test_scrub_logged_secrets(tmp_path):
    old = tmp_path / "proxy.log"
    old.write_text(
        "10:00:00  INFO     Secret:        0123456789abcdef0123456789abcdef\n"
        "10:00:00  INFO       tg://proxy?server=127.0.0.1&port=1443"
        "&secret=dd0123456789ABCDEF0123456789abcdef\n"
        "10:00:00  INFO       tg://proxy?server=127.0.0.1&port=1443"
        "&secret=ee0123456789abcdef0123456789abcdef6578616d706c652e636f6d\n"
        "10:00:01  INFO   cf_ray=a53b5828caddfbc4 kept\n", encoding="utf-8")
    android_entry._scrub_logged_secrets(str(old))
    text = old.read_text(encoding="utf-8")
    assert "0123456789" not in text.lower()
    assert "Secret:        <secret>" in text and "secret=dd<secret>\n" in text
    assert "secret=ee<secret>6578616d706c652e636f6d" in text  # domain hex isn't secret
    assert "cf_ray=a53b5828caddfbc4 kept" in text
    android_entry._scrub_logged_secrets(str(tmp_path / "missing.log"))  # no error


def test_bad_handshakes_recent_window():
    from proxy.stats import stats

    android_entry._reset_stats(now=0.0)
    for minute, bad in ((2, 3), (5, 4), (12, 10), (20, 10)):
        stats.connections_bad = bad
        android_entry._record_sample(now=minute * 60.0)
    # At 20 min the window starts at 10 min; the newest sample by then is 5 min (4 bad).
    assert android_entry._bad_handshakes_recent(now=20 * 60.0) == 10 - 4
    # In the first 10 minutes everything since the start counts.
    assert android_entry._bad_handshakes_recent(now=4 * 60.0) == 10
    android_entry._reset_stats(now=0.0)
    assert android_entry._bad_handshakes_recent(now=0.0) == 0


def _send_bad_handshakes(port, n):
    """n handshakes of 64 zero bytes, which don't decrypt to a valid header with the
    test secret, like a Telegram proxy entry with an old secret."""
    for _ in range(n):
        with socket.create_connection(('127.0.0.1', port), timeout=2) as s:
            s.sendall(bytes(64))


def test_status_counts_rejected_handshakes(tmp_path):
    """bad_recent counts rejected handshakes since the last start only: a restart
    begins again from zero and new rejections still count."""
    cfg = _cfg()
    port = json.loads(cfg)["port"]
    try:
        assert android_entry.start(cfg, str(tmp_path)) is None
        assert _wait(lambda: json.loads(android_entry.status())["listening"])
        _send_bad_handshakes(port, 2)
        assert _wait(lambda: json.loads(android_entry.status())["bad_recent"] == 2)
        android_entry.stop()
        assert android_entry.start(cfg, str(tmp_path)) is None
        assert _wait(lambda: json.loads(android_entry.status())["listening"])
        assert json.loads(android_entry.status())["bad_recent"] == 0
        _send_bad_handshakes(port, 1)
        assert _wait(lambda: json.loads(android_entry.status())["bad_recent"] == 1)
    finally:
        android_entry.stop()



def test_status_counts_cf_proxy_failures(tmp_path):
    """cf_failed counts the core's "CF proxy failed" warnings (one per host that
    didn't open), from zero at each start; cf_ok its CF connections that opened."""
    import logging
    core_log = logging.getLogger("tg-mtproto-proxy")
    try:
        assert android_entry.start(_cfg(), str(tmp_path)) is None
        assert _wait(lambda: json.loads(android_entry.status())["listening"])
        for _ in range(3):
            core_log.warning("[%s] DC%d%s CF proxy failed: %r (host=%s port=%d)",
                             "x", 1, "", TimeoutError(), "kws1.a.example", 443)
        core_log.warning("[%s] DC%d no fallback available", "x", 1)
        android_entry.stats.connections_cfproxy += 1
        st = json.loads(android_entry.status())
        assert (st["cf_failed"], st["cf_ok"]) == (3, 1)
        android_entry.stop()
        assert android_entry.start(_cfg(), str(tmp_path)) is None
        st = json.loads(android_entry.status())
        assert (st["cf_failed"], st["cf_ok"]) == (0, 0)
    finally:
        android_entry.stop()

def test_note_writes_dated_line_to_log(tmp_path):
    """Lines from the Android side (network changes, automatic restarts) land in
    the proxy log, and the log file carries dates so a shared log can be read later."""
    cfg = _cfg()
    try:
        assert android_entry.start(cfg, str(tmp_path)) is None
        android_entry.note("Network changed to mobile data; restarting the proxy core")
        # The file handler is set up once per process; read whichever file it writes.
        with open(android_entry._log_handler.baseFilename, encoding="utf-8") as f:
            lines = f.read().splitlines()
        assert any(re.match(r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d  INFO   AndMTProto: Network changed to "
                            r"mobile data; restarting the proxy core$", line) for line in lines)
    finally:
        android_entry.stop()


class _FakeWs:
    """An open RawWebSocket; Telegram answers the probe unless `silent`."""
    def __init__(self, silent: bool = False):
        self.silent = silent
        self.sent = []

    class writer:  # noqa: N801 - mimics RawWebSocket.writer
        @staticmethod
        def close():
            pass

    async def send(self, data: bytes):
        self.sent.append(data)

    async def recv(self):
        if self.silent:
            await asyncio.sleep(3600)
        return b"resPQ"


def _fake_download(ok: bool = True, seen: list = None):
    async def download(domain, secure, timeout, bypass=False, port=None):
        if seen is not None:
            seen.append((domain, ("bypass" if bypass else "tls") if secure else "plain"))
        return 40_000 if ok else 16_000
    return download


def _fake_connect(tls_ok: bool, bypass_ok: bool, plain_ok: bool, seen: list):
    async def connect(host, domain, timeout=10.0, path='/apiws', *, sni=None, secure=True):
        way = ("bypass" if android_entry.tls_bypass.override.get() else "tls") if secure else "plain"
        seen.append((host, way))
        if {"tls": tls_ok, "bypass": bypass_ok, "plain": plain_ok}[way]:
            return _FakeWs()
        raise TimeoutError()
    return connect


def test_probe_cf_tells_tls_block_from_dead_network(monkeypatch):
    monkeypatch.setattr(android_entry.proxy_config, "fallback_cfproxy", True)
    monkeypatch.setattr(android_entry, "_probe_strategy",
                        android_entry.tls_bypass.parse(android_entry.tls_bypass.DEFAULT_STRATEGY))
    monkeypatch.setattr(android_entry.balancer, "domains", ["a.example", "b.example"])
    for tls_ok, bypass_ok, plain_ok in [(False, True, True), (False, False, True),
                                        (True, True, True), (False, False, False)]:
        seen, large = [], []
        monkeypatch.setattr(android_entry.RawWebSocket, "connect",
                            _fake_connect(tls_ok, bypass_ok, plain_ok, seen))
        monkeypatch.setattr(android_entry, "_transfer_size", _fake_download(seen=large))
        assert json.loads(android_entry.probe_cf()) == {
            "tls": tls_ok, "bypass": bypass_ok, "plain": plain_ok,
            "tls_full": tls_ok, "bypass_full": bypass_ok, "plain_full": plain_ok}
        # The small check: both domains, each over plain TLS, bypassed TLS and port 80,
        # on the DC2 host; then the large check on both hosts of each way that passed.
        small = sorted((f"kws2.{d}", way) for d in ("a.example", "b.example")
                       for way in ("tls", "bypass", "plain"))
        assert sorted(seen) == small
        assert sorted(large) == sorted(
            (f"kws2.{d}", way) for d in ("a.example", "b.example")
            for way, ok in (("tls", tls_ok), ("bypass", bypass_ok), ("plain", plain_ok)) if ok)


def test_probe_cf_needs_an_answer_not_just_an_open_websocket(monkeypatch):
    """Beeline LTE, 18 Nov 2026: over the TLS split the CF proxy's WebSocket
    opened but no data came back. That counts as blocked."""
    monkeypatch.setattr(android_entry.proxy_config, "fallback_cfproxy", True)
    monkeypatch.setattr(android_entry, "_probe_strategy",
                        android_entry.tls_bypass.parse(android_entry.tls_bypass.DEFAULT_STRATEGY))
    monkeypatch.setattr(android_entry.balancer, "domains", ["a.example"])
    sockets = []

    async def connect(host, domain, timeout=10.0, path='/apiws', *, sni=None, secure=True):
        ws = _FakeWs(silent=secure)  # TLS opens but stays silent; port 80 answers
        sockets.append(ws)
        return ws
    monkeypatch.setattr(android_entry.RawWebSocket, "connect", connect)
    monkeypatch.setattr(android_entry, "_transfer_size", _fake_download())
    assert json.loads(android_entry.probe_cf(timeout=0.3)) == {
        "tls": False, "bypass": False, "plain": True,
        "tls_full": False, "bypass_full": False, "plain_full": True}
    # Each check sent what Telegram sends first: the 64-byte start, then req_pq_multi.
    start, request = sockets[0].sent
    assert len(start) == 64 and len(request) == 41


def test_probe_cf_large_check_catches_a_freeze_after_16_kb(monkeypatch):
    """An MTS network, 18 Nov 2026: Telegram answered the small check through
    the CF proxy, but nothing larger than ~16 KB got through one connection."""
    monkeypatch.setattr(android_entry.proxy_config, "fallback_cfproxy", True)
    monkeypatch.setattr(android_entry, "_probe_strategy",
                        android_entry.tls_bypass.parse(android_entry.tls_bypass.DEFAULT_STRATEGY))
    monkeypatch.setattr(android_entry.balancer, "domains", ["a.example"])
    sockets = []

    async def connect(host, domain, timeout=10.0, path='/apiws', *, sni=None, secure=True):
        ws = _FakeWs()
        sockets.append(ws)
        return ws
    monkeypatch.setattr(android_entry.RawWebSocket, "connect", connect)
    monkeypatch.setattr(android_entry, "_transfer_size", _fake_download(False))
    assert json.loads(android_entry.probe_cf(timeout=0.3)) == {
        "tls": True, "bypass": True, "plain": True,
        "tls_full": False, "bypass_full": False, "plain_full": False}
    # The large check goes to Cloudflare only: Telegram got just the small check.
    assert sockets and all(len(ws.sent) == 2 for ws in sockets)


def test_transfer_check_needs_more_than_24_kb_each_way_on_one_connection():
    """_transfer_size sends a 25 KB POST and then 50 requests for
    /cdn-cgi/trace at once; the answers come only after the whole body, and a
    server that stops after 16 KB gives less than the 24 KB needed."""
    async def scenario(limit):
        async def handle(reader, writer):
            head = await reader.readuntil(b"\r\n\r\n")
            assert head.startswith(b"POST /cdn-cgi/trace ")
            length = int(re.search(rb"Content-Length: (\d+)", head).group(1))
            assert length > 24 * 1024
            await reader.readexactly(length)
            assert (await reader.readuntil(b"\r\n\r\n")).startswith(b"GET /cdn-cgi/trace ")
            writer.write((b"x" * 800) * 50 if limit is None else b"x" * limit)
            await writer.drain()
            if limit is not None:
                await asyncio.sleep(3600)  # frozen: no more data, no close
            writer.close()
        server = await asyncio.start_server(handle, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            return await android_entry._transfer_size("127.0.0.1", False, 1.0, port=port)
        finally:
            server.close()
    assert asyncio.run(scenario(None)) > android_entry._LARGE_BYTES
    assert asyncio.run(scenario(16 * 1024)) <= android_entry._LARGE_BYTES


def test_probe_request_is_req_pq_multi_for_dc2():
    from proxy._aes import Cipher, algorithms, modes
    start, request = android_entry._probe_request()
    decryptor = Cipher(algorithms.AES(start[8:40]), modes.CTR(start[40:56])).encryptor()
    plain_start = decryptor.update(start)
    assert plain_start[56:60] == android_entry.proxy_utils.PROTO_TAG_ABRIDGED
    assert int.from_bytes(plain_start[60:62], "little", signed=True) == 2
    body = decryptor.update(request)
    assert body[0] == 10 and body[1:9] == bytes(8)  # 40 bytes; no auth key yet
    assert int.from_bytes(body[17:21], "little") == 20
    assert int.from_bytes(body[21:25], "little") == 0xBE7E8EF1


def test_probe_cf_skipped_without_cf_proxy(monkeypatch):
    monkeypatch.setattr(android_entry.proxy_config, "fallback_cfproxy", False)
    assert json.loads(android_entry.probe_cf()) == {}


def _unpause_pool_after_test():
    android_entry.set_pool_paused(False)


def _record_pool_changes(monkeypatch):
    """Replaces _apply_pool_state; returns the _pool_paused values it ran with."""
    calls = []

    async def record():
        calls.append(android_entry._pool_paused)
    monkeypatch.setattr(android_entry, "_apply_pool_state", record)
    return calls


def test_core_started_with_the_screen_off_has_no_pool(tmp_path, monkeypatch):
    calls = _record_pool_changes(monkeypatch)
    android_entry.set_pool_paused(True)  # before start, as ProxyService does
    cfg = _cfg(pool_size=2)
    port = json.loads(cfg)["port"]
    try:
        assert android_entry.start(cfg, str(tmp_path)) is None
        assert _wait(lambda: _listening(port))
        assert android_entry.proxy_config.pool_size == 0  # no warmup, so no network
        android_entry.set_pool_paused(False)
        assert android_entry.proxy_config.pool_size == 2
        assert _wait(lambda: calls == [False])  # the running core fills its pools
        android_entry.set_pool_paused(False)  # no change: nothing more to do
        time.sleep(0.2)
        assert calls == [False]
    finally:
        android_entry.stop()
        _unpause_pool_after_test()


def test_pool_size_zero_is_never_paused_or_resumed(tmp_path, monkeypatch):
    calls = _record_pool_changes(monkeypatch)
    cfg = _cfg(pool_size=0)
    port = json.loads(cfg)["port"]
    try:
        assert android_entry.start(cfg, str(tmp_path)) is None
        assert _wait(lambda: _listening(port))
        android_entry.set_pool_paused(True)
        android_entry.set_pool_paused(False)
        time.sleep(0.2)
        assert calls == [] and android_entry.proxy_config.pool_size == 0
    finally:
        android_entry.stop()
        _unpause_pool_after_test()


def test_no_pool_change_reaches_a_stopping_core(monkeypatch):
    class _Loop:
        def __init__(self):
            self.callbacks = []

        def call_soon_threadsafe(self, callback):
            self.callbacks.append(callback)

    class _ClosedLoop:
        def call_soon_threadsafe(self, callback):
            raise RuntimeError("Event loop is closed")

    calls = _record_pool_changes(monkeypatch)
    monkeypatch.setattr(android_entry, "_pool_size", 4)
    stopping = threading.Event()
    stopping.set()
    loop = _Loop()
    try:
        monkeypatch.setattr(android_entry, "_async_stop", (loop, stopping))
        android_entry.set_pool_paused(True)
        for callback in loop.callbacks:
            callback()  # runs in the loop: the core is stopping, so no task
        assert loop.callbacks and calls == []
        monkeypatch.setattr(android_entry, "_async_stop", (_ClosedLoop(), stopping))
        android_entry.set_pool_paused(False)  # no error, no coroutine left unawaited
    finally:
        monkeypatch.setattr(android_entry, "_async_stop", None)
        _unpause_pool_after_test()


class _PooledWs:
    def __init__(self):
        self.closed = False

    async def close(self):
        self.closed = True


def test_pause_closes_idle_websockets_and_keeps_the_backoff():
    from collections import deque
    from proxy.pool import _CfWorkerPool, _WsPool

    async def scenario(pool, key, item):
        refill = asyncio.ensure_future(asyncio.sleep(3600))
        pool._refilling[key] = refill
        pool._idle[key] = deque([item])
        backoff_until = time.monotonic() + 256
        pool._refill_after[key] = backoff_until
        await android_entry._pause_pool(pool)
        assert refill.cancelled()
        assert not pool._idle[key]
        # Resuming then skips this key until its back-off ends: no burst at screen-on.
        assert pool._refill_after[key] == backoff_until

    ws = _PooledWs()
    asyncio.run(scenario(_WsPool(), (2, False, False), (ws, time.monotonic())))
    assert ws.closed
    worker_ws = _PooledWs()
    asyncio.run(scenario(_CfWorkerPool(), 2, (worker_ws, time.monotonic(), "w.example")))
    assert worker_ws.closed


def test_resume_warms_up_both_pools(monkeypatch):
    from proxy import pool
    warmed = []
    for name in ("ws_pool", "cf_worker_pool"):
        async def warmup(name=name):
            warmed.append(name)
        monkeypatch.setattr(getattr(pool, name), "warmup", warmup)
    monkeypatch.setattr(android_entry, "_pool_paused", False)
    asyncio.run(android_entry._apply_pool_state())
    assert warmed == ["ws_pool", "cf_worker_pool"]


def test_resume_right_after_a_pause_keeps_its_websockets(monkeypatch):
    """The screen comes on while the pause still waits for a refill to stop:
    the resume runs after it, so the pause can't close what the resume pooled."""
    from proxy import pool
    order = []

    async def slow_pause(p):
        order.append("pause starts")
        await asyncio.sleep(0.05)
        order.append("pause ends")

    async def warmup():
        order.append("warmup")
    monkeypatch.setattr(android_entry, "_pause_pool", slow_pause)
    monkeypatch.setattr(pool.ws_pool, "warmup", warmup)
    monkeypatch.setattr(pool.cf_worker_pool, "warmup", warmup)

    async def scenario():
        monkeypatch.setattr(android_entry, "_pool_paused", True)
        pause = asyncio.ensure_future(android_entry._apply_pool_state())
        await asyncio.sleep(0.01)  # the pause is under way
        monkeypatch.setattr(android_entry, "_pool_paused", False)
        resume = asyncio.ensure_future(android_entry._apply_pool_state())
        await asyncio.gather(pause, resume)
    asyncio.run(scenario())
    assert order[-2:] == ["warmup", "warmup"]
    assert order.index("pause ends") < order.index("warmup")


def _fresh_routes(monkeypatch, paused=True, pool_size=4):
    """A clean on-demand state with DC2 routed, the pool paused or not."""
    from proxy import pool
    monkeypatch.setattr(android_entry, "_route_ok_at", {})
    monkeypatch.setattr(android_entry, "_on_demand_failures", {})
    monkeypatch.setattr(android_entry, "_on_demand_failed_at", {})
    monkeypatch.setattr(android_entry, "_on_demand_after", {})
    monkeypatch.setattr(android_entry, "_next_fronted", None)
    monkeypatch.setattr(android_entry, "_ON_DEMAND_FIRST_TRY_S", 0.1)
    monkeypatch.setattr(android_entry, "_ON_DEMAND_TRY_S", 0.05)
    monkeypatch.setattr(android_entry, "_pool_paused", paused)
    monkeypatch.setattr(android_entry, "_pool_size", pool_size)
    monkeypatch.setattr(android_entry.proxy_config, "pool_size", 0 if paused else pool_size)
    monkeypatch.setattr(android_entry.proxy_config, "dc_redirects", {2: "149.154.167.220"})
    monkeypatch.setattr(pool.ws_pool, "_refill_after", {})
    monkeypatch.setattr(pool.ws_pool, "_refill_failures", {})
    monkeypatch.setattr(pool.ws_pool, "try_fronting_first", False)
    return pool.ws_pool


def test_paused_pool_opens_websockets_on_demand(monkeypatch):
    """With the pool paused a connection still gets a direct WebSocket; without
    this, upstream's get() returns None for pool size 0 and every connection
    takes the CF/TCP fallback."""
    ws_pool = _fresh_routes(monkeypatch)
    opened = _PooledWs()
    tries = []

    async def open_ws(target_ip, domain, path, fronted):
        tries.append((target_ip, domain, path, fronted))
        return opened
    monkeypatch.setattr(android_entry, "_open_ws", open_ws)
    assert asyncio.run(ws_pool.get(2, False)) is opened
    assert tries == [("149.154.167.220", "kws2.web.telegram.org", "/apiws", False)]
    assert asyncio.run(ws_pool.get(4, False)) is None  # no route to DC4 configured
    assert len(tries) == 1


def test_each_fronting_mode_gets_its_own_try(monkeypatch):
    """On MTS Wi-Fi only fronting gets through, and after a core start upstream
    tries the other mode first: that try must not use up the whole wait."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(android_entry, "_ON_DEMAND_TRY_S", 0.05)
    opened = _PooledWs()
    tries = []

    async def open_ws(target_ip, domain, path, fronted):
        tries.append(fronted)
        if not fronted:
            await asyncio.sleep(3600)  # unfronted never answers here
        return opened
    monkeypatch.setattr(android_entry, "_open_ws", open_ws)
    assert asyncio.run(ws_pool.get(2, False)) is opened
    assert tries == [False, True]
    # Fronting worked, so it goes first next time, even after a core start.
    assert android_entry._next_fronted is True
    android_entry._reset_on_demand()
    assert asyncio.run(ws_pool.get(2, False)) is opened
    assert tries == [False, True, True]


def test_blocked_route_backs_off_on_its_own(monkeypatch):
    """Every try fails: the next connection goes straight to the fallback. The
    pool's back-off stays untouched, so a screen-on refill isn't held back by
    what happened with the screen off."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(android_entry, "_ON_DEMAND_TRY_S", 0.02)
    tries = []

    async def blocked(target_ip, domain, path, fronted):
        tries.append(domain)
        await asyncio.sleep(3600)
    monkeypatch.setattr(android_entry, "_open_ws", blocked)
    assert asyncio.run(ws_pool.get(2, True)) is None
    assert len(tries) == 4  # both media domains, both modes
    assert asyncio.run(ws_pool.get(2, True)) is None
    assert len(tries) == 4  # backing off: no tries
    assert android_entry._on_demand_failures == {(2, True, False): 1}
    assert ws_pool._refill_after == {} and ws_pool._refill_failures == {}


def test_on_demand_respects_the_pools_back_off(monkeypatch):
    ws_pool = _fresh_routes(monkeypatch)

    async def open_ws(*args):
        raise AssertionError("the pool's refills keep failing on this route")
    monkeypatch.setattr(android_entry, "_open_ws", open_ws)
    ws_pool._refill_after[(2, False, False)] = time.monotonic() + 60
    assert asyncio.run(ws_pool.get(2, False)) is None


def test_late_failure_doesnt_back_off_a_route_that_worked_meanwhile(monkeypatch):
    """Several connections to one DC at once: a slow one that fails after a
    fast one got through mustn't send the next connections to the fallback."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(android_entry, "_ON_DEMAND_TRY_S", 0.05)
    opened = _PooledWs()
    slow_task = []

    async def open_ws(target_ip, domain, path, fronted):
        if asyncio.current_task() in slow_task:  # every try of the slow connection hangs
            await asyncio.sleep(3600)
        return opened

    async def scenario():
        slow = asyncio.ensure_future(ws_pool.get(2, False))
        slow_task.append(slow)
        await asyncio.sleep(0.01)
        fast = await ws_pool.get(2, False)
        return fast, await slow
    monkeypatch.setattr(android_entry, "_open_ws", open_ws)
    fast, slow = asyncio.run(scenario())
    assert fast is opened and slow is None
    assert android_entry._on_demand_after == {}
    assert asyncio.run(ws_pool.get(2, False)) is opened


def test_empty_pool_opens_on_demand_once_the_route_worked(monkeypatch):
    """Screen on, pool not refilled yet (or emptied by a burst): a route that
    worked on this core gets a WebSocket on demand instead of the fallback; an
    unknown one keeps upstream's behaviour."""
    from proxy import pool
    ws_pool = _fresh_routes(monkeypatch, paused=False)
    opened = _PooledWs()
    tries = []

    async def open_ws(target_ip, domain, path, fronted):
        tries.append(fronted)
        return opened

    async def no_refill(*args):
        return None  # upstream's own refill, kept off the network
    monkeypatch.setattr(android_entry, "_open_ws", open_ws)
    monkeypatch.setattr(pool.ws_pool, "_connect_one", no_refill)
    assert asyncio.run(ws_pool.get(2, False)) is None
    assert tries == []
    android_entry._route_ok_at[(2, False, False)] = time.monotonic()
    assert asyncio.run(ws_pool.get(2, False)) is opened
    assert tries == [False]


def test_failures_in_one_burst_back_off_once(monkeypatch):
    """Telegram opens several connections at once; when they all fail together
    (the network isn't up yet) the route backs off as after one failure, not
    one doubling per connection."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(android_entry, "_ON_DEMAND_TRY_S", 0.02)
    # A back-off that ends before the burst's last connections give up.
    monkeypatch.setattr(ws_pool, "REFILL_BACKOFF_INITIAL", 0.001)

    async def blocked(target_ip, domain, path, fronted):
        await asyncio.sleep(3600)

    async def one(delay):
        await asyncio.sleep(delay)
        return await ws_pool.get(2, False)

    async def burst():
        return await asyncio.gather(*(one(i * 0.005) for i in range(8)))
    monkeypatch.setattr(android_entry, "_open_ws", blocked)
    assert asyncio.run(burst()) == [None] * 8
    assert android_entry._on_demand_failures == {(2, False, False): 1}


def test_pool_hit_clears_the_on_demand_back_off(monkeypatch):
    """A back-off left from the screen-off time mustn't keep an emptied pool's
    connections on the fallback once pool hits show the route works."""
    from collections import deque
    from proxy import pool
    ws_pool = _fresh_routes(monkeypatch, paused=False)
    key = (2, False, False)
    pooled = _PooledWs()
    for name in ("_refilling", "_rotating"):
        monkeypatch.setattr(pool.ws_pool, name, {})
    monkeypatch.setattr(pool.ws_pool, "_idle", {key: deque([(pooled, time.monotonic())])})
    monkeypatch.setattr(pool.ws_pool, "_is_stale", lambda *args: False)

    async def no_refill(*args):
        return None
    monkeypatch.setattr(pool.ws_pool, "_connect_one", no_refill)
    android_entry._on_demand_failures[key] = 12
    android_entry._on_demand_after[key] = time.monotonic() + 3600
    assert asyncio.run(ws_pool.get(2, False)) is pooled
    assert android_entry._on_demand_failures == {} and android_entry._on_demand_after == {}
    assert key in android_entry._route_ok_at


def test_the_mode_that_worked_gets_a_longer_try(monkeypatch):
    """Through a slow VPN a working route takes several seconds: the mode
    that last worked gets as long as upstream's pool would give it."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(android_entry, "_ON_DEMAND_FIRST_TRY_S", 0.5)
    monkeypatch.setattr(android_entry, "_next_fronted", True)
    opened = _PooledWs()
    tries = []

    async def slow(target_ip, domain, path, fronted):
        tries.append(fronted)
        await asyncio.sleep(0.15)  # longer than the other mode's 0.05 s try
        return opened
    monkeypatch.setattr(android_entry, "_open_ws", slow)
    assert asyncio.run(ws_pool.get(2, False)) is opened
    assert tries == [True]


def test_an_unproven_mode_gets_the_short_try(monkeypatch):
    """Before any mode has worked, upstream's default (unfronted after a core
    start) is only a guess: on MTS Wi-Fi it hangs, and fronting must not wait
    the long try for it."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(android_entry, "_ON_DEMAND_FIRST_TRY_S", 3600)
    opened = _PooledWs()
    tries = []

    async def open_ws(target_ip, domain, path, fronted):
        tries.append(fronted)
        if not fronted:
            await asyncio.sleep(3600)
        return opened
    monkeypatch.setattr(android_entry, "_open_ws", open_ws)
    assert asyncio.run(asyncio.wait_for(ws_pool.get(2, False), 5)) is opened
    assert tries == [False, True]


def test_unproven_modes_take_turns_at_the_long_try(monkeypatch):
    """After a boot with the screen off nothing has worked yet; through a slow
    VPN only the long try gets through, so each mode must get it in turn, or
    no mode ever works and the long try is never reached."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(android_entry, "_ON_DEMAND_FIRST_TRY_S", 0.5)
    monkeypatch.setattr(ws_pool, "REFILL_BACKOFF_INITIAL", 0.001)
    opened = _PooledWs()
    tries = []

    async def slow_unfronted(target_ip, domain, path, fronted):
        tries.append(fronted)
        await asyncio.sleep(0.15 if not fronted else 3600)  # fronting blocked
        return opened
    monkeypatch.setattr(android_entry, "_open_ws", slow_unfronted)

    async def scenario():
        first = await ws_pool.get(2, False)  # unfronted: short try, fronted: long
        await asyncio.sleep(0.01)  # the back-off ends
        return first, await ws_pool.get(2, False)  # unfronted long: gets through
    first, second = asyncio.run(scenario())
    assert first is None and second is opened
    assert tries == [False, True, False]
    assert android_entry._next_fronted is False


def test_a_proven_mode_hands_over_the_long_try_on_a_new_network(monkeypatch):
    """Fronting worked on the old network; on the new one it hangs and the
    other mode needs the long try (a slow VPN). After a failed attempt the
    other mode gets it, or nothing would ever open until the screen came on."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(android_entry, "_next_fronted", True)
    monkeypatch.setattr(android_entry, "_ON_DEMAND_FIRST_TRY_S", 0.5)
    monkeypatch.setattr(ws_pool, "REFILL_BACKOFF_INITIAL", 0.001)
    opened = _PooledWs()
    tries = []

    async def slow_unfronted(target_ip, domain, path, fronted):
        tries.append(fronted)
        await asyncio.sleep(0.15 if not fronted else 3600)
        return opened
    monkeypatch.setattr(android_entry, "_open_ws", slow_unfronted)

    async def scenario():
        first = await ws_pool.get(2, False)  # fronted long, unfronted short
        await asyncio.sleep(0.01)
        return first, await ws_pool.get(2, False)  # unfronted long
    first, second = asyncio.run(scenario())
    assert first is None and second is opened
    assert tries == [True, False, False]
    assert android_entry._next_fronted is False


def test_pool_hit_teaches_the_mode_to_on_demand(monkeypatch):
    """Fronting worked on the old network, the pool connects without it on
    the new one: once the pool has served a connection, on-demand WebSockets
    try the pool's mode first instead of losing a try on the old one."""
    from collections import deque
    from proxy import pool
    ws_pool = _fresh_routes(monkeypatch, paused=False)
    monkeypatch.setattr(android_entry, "_next_fronted", True)
    key = (2, False, False)
    for name in ("_refilling", "_rotating"):
        monkeypatch.setattr(pool.ws_pool, name, {})
    monkeypatch.setattr(pool.ws_pool, "_idle", {key: deque([(_PooledWs(), time.monotonic())])})
    monkeypatch.setattr(pool.ws_pool, "_is_stale", lambda *args: False)
    opened = _PooledWs()
    tries = []

    async def no_refill(*args):
        return None

    async def open_ws(target_ip, domain, path, fronted):
        tries.append(fronted)
        return opened
    monkeypatch.setattr(pool.ws_pool, "_connect_one", no_refill)
    monkeypatch.setattr(android_entry, "_open_ws", open_ws)
    ws_pool.try_fronting_first = False  # what the pool's refills learned

    async def scenario():
        hit = await ws_pool.get(2, False)
        return hit, await ws_pool.get(2, False)  # the pool is empty now
    hit, on_demand = asyncio.run(scenario())
    assert hit is not opened and on_demand is opened
    assert tries == [False] and android_entry._next_fronted is False


def test_pool_size_zero_set_by_the_user_still_skips_websockets(monkeypatch):
    ws_pool = _fresh_routes(monkeypatch, pool_size=0)

    async def open_ws(*args):
        raise AssertionError("pool size 0 means no direct WebSockets")
    monkeypatch.setattr(android_entry, "_open_ws", open_ws)
    assert asyncio.run(ws_pool.get(2, False)) is None


def test_pool_change_tasks_are_kept_until_done(monkeypatch):
    """The loop keeps only weak references to tasks; set_pool_paused's must not
    be collected half-way."""
    calls = _record_pool_changes(monkeypatch)
    monkeypatch.setattr(android_entry, "_pool_size", 4)

    async def scenario():
        loop = asyncio.get_running_loop()
        monkeypatch.setattr(android_entry, "_async_stop", (loop, asyncio.Event()))
        android_entry.set_pool_paused(True)
        await asyncio.sleep(0)  # the callback runs and creates the task
        assert len(android_entry._pool_tasks) == 1
        await asyncio.sleep(0.01)
        assert not android_entry._pool_tasks and calls == [True]
    try:
        asyncio.run(scenario())
    finally:
        monkeypatch.setattr(android_entry, "_async_stop", None)
        _unpause_pool_after_test()
