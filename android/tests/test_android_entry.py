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



def _tls_server(group: str):
    """A one-shot TLS 1.3 server on localhost offering one key-exchange group.

    Returns (port, cert_pem) so clients can trust the self-signed certificate.
    """
    import datetime
    import ssl
    import tempfile
    import threading

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'localhost')])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=1))
            .not_valid_after(now + datetime.timedelta(days=1))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost')]), critical=False)
            .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
            .sign(key, hashes.SHA256()))
    cert_pem = cert.public_bytes(serialization.Encoding.PEM).decode()
    with tempfile.NamedTemporaryFile('w', suffix='.pem', delete=False) as pem:
        pem.write(cert_pem)
        pem.write(key.private_bytes(serialization.Encoding.PEM,
                                    serialization.PrivateFormat.PKCS8,
                                    serialization.NoEncryption()).decode())

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    ctx.load_cert_chain(pem.name)
    ctx.set_ecdh_curve(group)
    os.unlink(pem.name)

    listener = socket.create_server(('127.0.0.1', 0))

    def serve():
        try:
            conn, _ = listener.accept()
            with ctx.wrap_socket(conn, server_side=True) as tls:
                tls.recv(1)
        except (ssl.SSLError, OSError):
            pass
        finally:
            listener.close()

    threading.Thread(target=serve, daemon=True).start()
    return listener.getsockname()[1], cert_pem


def _handshakes(context, group: str) -> bool:
    import ssl
    port, cert_pem = _tls_server(group)
    context.load_verify_locations(cadata=cert_pem)
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=5) as raw:
            with context.wrap_socket(raw, server_hostname='localhost'):
                return True
    except (ssl.SSLError, OSError):
        return False


def test_core_tls_offers_only_x25519():
    # Upstream limits TLS to X25519 since 1.11.3 (android.6 patched this in the adapter):
    # a post-quantum key share makes the ClientHello big enough for some DPI to stall it.
    from proxy import h2_transport, raw_websocket, utils

    contexts = [utils.create_ssl_context(), h2_transport._default_ssl_context(),
                raw_websocket._ssl_ctx, raw_websocket._ssl_ctx_fronting]
    for context in contexts:
        assert _handshakes(context, 'X25519')
        assert not _handshakes(context, 'prime256v1')
