"""Tests for the TLS-blocking bypass (android/app/src/main/python/tls_bypass.py)."""
import asyncio
import asyncio.sslproto
import json
import os
import re
import ssl
import struct
import sys

import pytest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, 'android', 'app', 'src', 'main', 'python'))

import android_entry  # noqa: E402
import tls_bypass  # noqa: E402
from tls_bypass import StrategyError, parse, shape, sni_span  # noqa: E402

HOST = "kws2.example.co.uk"


@pytest.fixture(autouse=True)
def _bypass_off_after():
    yield
    tls_bypass.configure(None, lambda: ())
    android_entry._auto_pick_on = False


def _hello(host=HOST) -> bytes:
    out = ssl.MemoryBIO()
    tls = ssl.create_default_context().wrap_bio(ssl.MemoryBIO(), out, server_hostname=host)
    with pytest.raises(ssl.SSLWantReadError):
        tls.do_handshake()
    return out.read()


def _records(wire: bytes):
    """The TLS records in `wire` as (type, body)."""
    out, pos = [], 0
    while pos < len(wire):
        size = struct.unpack_from(">H", wire, pos + 3)[0]
        out.append((wire[pos], wire[pos + 5:pos + 5 + size]))
        pos += 5 + size
    assert pos == len(wire)
    return out


def test_sni_span_finds_the_name():
    h = _hello()
    a, b = sni_span(h)
    assert h[a:b] == HOST.encode()
    assert sni_span(b"\x17\x03\x03\x00\x01x") is None
    assert sni_span(h[:-1]) is None  # not one whole record


def test_default_strategy_is_the_one_tested_on_phones():
    # Records split in the middle of the name; the TCP split 2 bytes after the new header.
    h = _hello()
    a, b = sni_span(h)
    mid = a + (b - a) // 2
    segments = shape(h, parse(tls_bypass.DEFAULT_STRATEGY))
    assert [oob for _, oob in segments] == [False, False]
    assert len(segments[0][0]) == mid + 5 + 2
    records = _records(b"".join(chunk for chunk, _ in segments))
    assert [t for t, _ in records] == [0x16, 0x16]
    assert b"".join(body for _, body in records) == h[5:]
    assert len(records[0][1]) == mid - 5


def test_oob_and_repeats():
    h = _hello()
    a, _ = sni_span(h)
    segments = shape(h, parse("--split 1:3:5 --oob=0+s"))
    assert [len(c) for c, _ in segments[:3]] == [1, 5, 5]
    assert b"".join(c for c, _ in segments) == h
    ends = [sum(len(c) for c, _ in segments[:i + 1]) for i in range(len(segments))]
    assert [e for e, (_, oob) in zip(ends, segments) if oob] == [a]


def test_negative_offset_counts_from_the_end():
    h = _hello()
    assert [len(c) for c, _ in shape(h, parse("-s -1"))] == [len(h) - 1, 1]
    # With a flag it is relative to that point instead.
    a, _ = sni_span(h)
    assert len(shape(h, parse("-s -1+s"))[0][0]) == a - 1


def test_not_a_client_hello_is_left_alone():
    assert shape(b"GET / HTTP/1.1\r\n\r\n", parse("--split 1")) is None


@pytest.mark.parametrize("text, token", [
    ("--bogus 1", "--bogus"), ("--split", "--split"), ("--split x", "x"),
    ("--tlsrec 1+q", "1+q"), ("--split 1+nm", "1+nm"), ("", ""), ("--split '1", "--split '1"),
])
def test_bad_strategies_name_the_bad_part(text, token):
    with pytest.raises(StrategyError) as err:
        parse(text)
    assert err.value.token == token
    # An empty strategy means the default to start() and the settings screen.
    assert android_entry.check_tls_strategy(text) == token


@pytest.mark.parametrize("text", [
    tls_bypass.DEFAULT_STRATEGY, "--tlsrec 0+sm --oob 2+sm", "--oob 0+sm", "--sni-parent",
    "-r 1+s -s 3+s", "--split=1 --tlsrec=-10",
])
def test_presets_parse(text):
    assert android_entry.check_tls_strategy(text) == ""


def test_kotlin_presets_match():
    """ProxySettings.kt lists automatic first, then the candidates in the same order."""
    kt = open(os.path.join(ROOT, "android", "app", "src", "main", "java", "io", "github",
                           "oruviyex18", "andmtproto", "ProxySettings.kt"), encoding="utf-8").read()
    consts = dict(re.findall(r'const val (\w+) = "([^"]+)"', kt))
    names = re.search(r"val PRESETS = listOf\(([^)]*)\)", kt).group(1).replace(" ", "").split(",")
    assert consts["AUTO"] == tls_bypass.AUTO
    assert "const val DEFAULT = AUTO" in kt
    assert [consts[n] for n in names] == [tls_bypass.AUTO, *tls_bypass.CANDIDATES]


def test_plan_picks_hosts_and_parent_sni():
    tls_bypass.configure(parse(tls_bypass.DEFAULT_STRATEGY), lambda: ["example.co.uk", "web.telegram.org"])
    assert tls_bypass.plan(HOST)[0] == HOST
    assert tls_bypass.plan("kws4.web.telegram.org") is not None
    assert tls_bypass.plan("github.com") is None
    assert tls_bypass.plan("notexample.co.uk") is None
    token = tls_bypass.override.set(False)
    assert tls_bypass.plan(HOST) is None
    tls_bypass.override.reset(token)
    tls_bypass.configure(parse("--sni-parent"), lambda: ["example.co.uk"])
    assert tls_bypass.plan(HOST)[0] == "example.co.uk"
    assert tls_bypass.plan("www.example.co.uk")[0] == "www.example.co.uk"  # only kwsN names
    tls_bypass.configure(None, lambda: ["example.co.uk"])
    assert tls_bypass.plan(HOST) is None
    # probe_cf can force a strategy while the bypass is off.
    token = tls_bypass.override.set(parse(tls_bypass.DEFAULT_STRATEGY))
    assert tls_bypass.plan(HOST) is not None
    tls_bypass.override.reset(token)


def _whole_records(buf: bytes) -> bool:
    pos = 0
    while pos + 5 <= len(buf):
        pos += 5 + struct.unpack_from(">H", buf, pos + 3)[0]
        if pos == len(buf):
            return True
    return False


async def _capture_hello(host: str) -> bytes:
    """What asyncio.open_connection(ssl=...) sends a server that never answers."""
    got = bytearray()
    done = asyncio.Event()

    async def serve(reader, writer):
        # Two records arrive one after the other; wait until the second is whole too.
        while not (_whole_records(got) and len(_records(bytes(got))) >= 2) and len(got) < 4096:
            try:
                chunk = await asyncio.wait_for(reader.read(4096), 0.5)
            except asyncio.TimeoutError:
                break
            if not chunk:
                break
            got.extend(chunk)
        done.set()
        writer.close()

    server = await asyncio.start_server(serve, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    try:
        await asyncio.wait_for(asyncio.open_connection(
            "127.0.0.1", port, ssl=ssl.create_default_context(), server_hostname=host), 5)
    except (ssl.SSLError, ConnectionError, OSError, asyncio.TimeoutError):
        pass
    await asyncio.wait_for(done.wait(), 5)
    server.close()
    return bytes(got)


def test_hook_sends_the_split_hello_over_a_real_socket():
    tls_bypass.configure(parse(tls_bypass.DEFAULT_STRATEGY), lambda: ["example.co.uk"])
    records = _records(asyncio.run(_capture_hello(HOST)))
    assert len(records) == 2
    assert records[0][1][0] == 0x01  # ClientHello
    assert HOST.encode() in b"".join(b for _, b in records)
    assert HOST.encode() not in records[0][1] and HOST.encode() not in records[1][1]
    # Other hosts are left alone: one record.
    assert len(_records(asyncio.run(_capture_hello("other.example")))) == 1


def test_start_rejects_a_bad_strategy_only_when_on():
    cfg = {"port": 1, "secret": "0123456789abcdef0123456789abcdef", "cfproxy": False,
           "pool_size": 0, "tls_strategy": "--nope"}
    with pytest.raises(android_entry.ConfigError) as err:
        android_entry._apply_config(android_entry._merged(json.dumps(cfg)))
    assert err.value.code == "bad_tls_strategy" and err.value.detail == "--nope"
    cfg["tls_bypass"] = False  # then it is ignored, and probe_cf checks them all
    android_entry._apply_config(android_entry._merged(json.dumps(cfg)))
    assert android_entry._probe_strategy is None


class _FakeWs:
    class writer:  # noqa: N801 - mimics RawWebSocket.writer
        @staticmethod
        def close():
            pass


def _connect_opening(works):
    """RawWebSocket.connect that opens when works(way) is true for its way:
    "plain" (port 80), "tls" (no strategy) or a strategy's text."""
    async def connect(host, domain, timeout=10.0, path='/apiws', *, sni=None, secure=True):
        bypass = tls_bypass.override.get()
        way = "plain" if not secure else (bypass.text if bypass else "tls")
        if works(way):
            return _FakeWs()
        raise TimeoutError()
    return connect


def _start_auto(monkeypatch, **cfg):
    base = {"port": 1, "secret": "0123456789abcdef0123456789abcdef", "cfproxy": True,
            "pool_size": 0}
    base.update(cfg)
    android_entry._apply_config(android_entry._merged(json.dumps(base)))
    monkeypatch.setattr(android_entry.balancer, "domains", ["a.example", "b.example"])


def test_automatic_is_the_default_and_starts_with_the_usual_strategy(monkeypatch):
    _start_auto(monkeypatch)
    assert android_entry.DEFAULT_CONFIG["tls_strategy"] == tls_bypass.AUTO
    assert android_entry._probe_strategy is None and android_entry._auto_pick_on
    assert tls_bypass.active().text == tls_bypass.DEFAULT_STRATEGY
    assert android_entry.check_tls_strategy("auto") == "" and android_entry.check_tls_strategy(" AUTO ") == ""


@pytest.mark.parametrize("working, picked", [
    ({tls_bypass.DEFAULT_STRATEGY, "--oob 0+sm", "tls"}, tls_bypass.DEFAULT_STRATEGY),
    ({"--oob 0+sm", "--sni-parent"}, "--oob 0+sm"),  # the first that works, in order
    ({"--sni-parent"}, "--sni-parent"),
    ({"tls"}, None),  # only plain TLS: no reshaping
])
def test_auto_pick_uses_the_first_strategy_that_opens(monkeypatch, working, picked):
    _start_auto(monkeypatch)
    monkeypatch.setattr(android_entry.RawWebSocket, "connect", _connect_opening(lambda w: w in working))
    asyncio.run(android_entry._auto_pick())
    active = tls_bypass.active()
    assert (active.text if active else None) == picked


def test_auto_pick_keeps_the_strategy_when_nothing_opens(monkeypatch):
    _start_auto(monkeypatch)
    monkeypatch.setattr(android_entry.RawWebSocket, "connect", _connect_opening(lambda w: False))
    asyncio.run(android_entry._auto_pick())
    assert tls_bypass.active().text == tls_bypass.DEFAULT_STRATEGY


def test_auto_pick_leaves_a_fixed_strategy_alone(monkeypatch):
    _start_auto(monkeypatch, tls_strategy="--oob 0+sm")
    monkeypatch.setattr(android_entry.RawWebSocket, "connect",
                        _connect_opening(lambda w: w == "--sni-parent"))
    asyncio.run(android_entry._auto_pick())
    assert tls_bypass.active().text == "--oob 0+sm"


def test_probe_cf_with_automatic_strategy_reports_and_switches(monkeypatch):
    _start_auto(monkeypatch)
    monkeypatch.setattr(android_entry.RawWebSocket, "connect",
                        _connect_opening(lambda w: w in ("--sni-parent", "plain")))
    assert json.loads(android_entry.probe_cf()) == {"tls": False, "bypass": True, "plain": True}
    assert tls_bypass.active().text == "--sni-parent"
    # Only plain TLS: "bypass" is false (nothing needed reshaping), "tls" true.
    monkeypatch.setattr(android_entry.RawWebSocket, "connect",
                        _connect_opening(lambda w: w == "tls"))
    assert json.loads(android_entry.probe_cf()) == {"tls": True, "bypass": False, "plain": False}
    assert tls_bypass.active() is None


def test_outgoing_tls_closes_without_waiting_30s():
    tls_bypass.configure(None, lambda: ())

    async def run():
        loop = asyncio.get_running_loop()
        proto = asyncio.sslproto.SSLProtocol(
            loop, asyncio.Protocol(), ssl.create_default_context(), None, server_hostname="x.example")
        server = asyncio.sslproto.SSLProtocol(
            loop, asyncio.Protocol(), ssl.create_default_context(ssl.Purpose.CLIENT_AUTH), None,
            server_side=True)
        return proto._ssl_shutdown_timeout, server._ssl_shutdown_timeout

    client, server = asyncio.run(run())
    assert client == tls_bypass.SSL_SHUTDOWN_TIMEOUT_S
    assert server != tls_bypass.SSL_SHUTDOWN_TIMEOUT_S  # the listener is left alone
