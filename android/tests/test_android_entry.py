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
    finally:
        android_entry.stop()
    assert not json.loads(android_entry.status())["listening"]
