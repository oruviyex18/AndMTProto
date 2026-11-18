"""
TLS-blocking bypass for the core's outgoing TLS connections (AndMTProto only).

Since 16 Nov 2026 Russian DPI cuts TLS to the shared CF proxy domains by the
name in the ClientHello (SNI). It stops recognising that name when the
ClientHello is split into two TLS records inside the name and sent as two TCP
segments, the second starting a little after the new record header; Cloudflare
puts it back together as usual. Tested on home Wi-Fi and on mobile data on
18 Nov 2026. No root is needed: these are ordinary socket writes.

The bypass hooks asyncio's SSLProtocol, which every TLS connection of the core
goes through (asyncio.open_connection(ssl=...)), so upstream code is untouched.
Only connections to the CF proxy, CF worker and Telegram WebSocket hosts
(the `domains` given to configure()) are changed; every outgoing TLS connection
also gets a shorter close timeout (SSL_SHUTDOWN_TIMEOUT_S).

A strategy is written like ByeDPI's command line (a subset of it):
    --tlsrec POS / -r POS   split the TLS record at POS
    --split POS  / -s POS   split the TCP stream at POS
    --oob POS    / -o POS   same, plus one out-of-band byte there
    --sni-parent            SNI is the bare CF domain (kws2.example.co.uk ->
                            example.co.uk); Cloudflare serves the whole zone
POS is OFFSET[:REPEATS[:SKIP]][+FLAGS], counted in the original ClientHello:
    +s  from the start of the name in SNI, +sm its middle, +se its end
    +n  from the start (the default); a negative OFFSET without flags counts
        from the end of the ClientHello
TCP positions after a record split move with it: a position at or past a new
record header lands after the header.
"""
from __future__ import annotations

import asyncio.sslproto
import contextvars
import inspect
import logging
import re
import shlex
import socket
import struct
from dataclasses import dataclass, field
from typing import Callable, Iterable, List, Optional, Tuple

log = logging.getLogger('tg-mtproto-proxy')

DEFAULT_STRATEGY = "--tlsrec 0+sm --split 2+sm"

# "Automatic": android_entry tries CANDIDATES (in this order of preference) and
# uses the first that gets through; ProxySettings.kt lists the same presets.
AUTO = "auto"
CANDIDATES = (DEFAULT_STRATEGY, "--tlsrec 0+sm --oob 2+sm", "--oob 0+sm", "--sni-parent")

_POS = re.compile(r"^(-?\d+)(?::(\d+)(?::(\d+))?)?(?:\+([sn])([me])?)?$")
_KWS = re.compile(r"^kws\d+\.(.+)$")


class StrategyError(ValueError):
    """`token` is the part of the strategy that could not be understood."""

    def __init__(self, token: str):
        super().__init__(f"bad strategy token: {token}")
        self.token = token


@dataclass(frozen=True)
class Pos:
    offset: int
    base: str = "n"  # n, s, sm, se
    explicit: bool = False  # a base flag was given (a negative offset is then not from the end)

    def resolve(self, size: int, sni: Tuple[int, int]) -> int:
        start, end = sni
        if self.base == "s":
            at = start
        elif self.base == "sm":
            at = start + (end - start) // 2
        elif self.base == "se":
            at = end
        else:
            at = size if (self.offset < 0 and not self.explicit) else 0
        return at + self.offset


@dataclass(frozen=True)
class Strategy:
    text: str
    records: Tuple[Pos, ...] = ()
    splits: Tuple[Tuple[Pos, bool], ...] = ()  # (position, with an out-of-band byte)
    sni_parent: bool = False

    @property
    def shapes_hello(self) -> bool:
        return bool(self.records or self.splits)


def _positions(token: str) -> List[Pos]:
    m = _POS.match(token)
    if not m:
        raise StrategyError(token)
    offset, repeats, skip, base, where = m.groups()
    if base == "n" and where:
        raise StrategyError(token)
    flag = (base or "n") + (where or "")  # n, s, sm, se
    count = int(repeats) if repeats else 1
    step = int(skip) if skip else 0
    if not 1 <= count <= 64:
        raise StrategyError(token)
    return [Pos(int(offset) + i * step, flag, explicit=base is not None) for i in range(count)]


_OPTIONS = {
    "--tlsrec": "rec", "-r": "rec",
    "--split": "split", "-s": "split",
    "--oob": "oob", "-o": "oob",
}


def parse(text: str) -> Strategy:
    """Parses a strategy; raises StrategyError naming the first bad token."""
    try:
        tokens = shlex.split(text or "")
    except ValueError:
        raise StrategyError(text) from None
    records: List[Pos] = []
    splits: List[Tuple[Pos, bool]] = []
    sni_parent = False
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        name, eq, value = tok.partition("=")
        if name == "--sni-parent" and not eq:
            sni_parent = True
            i += 1
            continue
        kind = _OPTIONS.get(name)
        if kind is None:
            raise StrategyError(tok)
        if not eq:
            i += 1
            if i >= len(tokens):
                raise StrategyError(tok)
            value = tokens[i]
        for pos in _positions(value):
            if kind == "rec":
                records.append(pos)
            else:
                splits.append((pos, kind == "oob"))
        i += 1
    if not (records or splits or sni_parent):
        raise StrategyError(text or "")
    return Strategy(" ".join(tokens), tuple(records), tuple(splits), sni_parent)


def sni_span(hello: bytes) -> Optional[Tuple[int, int]]:
    """Where the host name sits in a single-record TLS ClientHello, or None."""
    try:
        if len(hello) < 5 + 4 or hello[0] != 0x16 or hello[5] != 0x01:
            return None
        if struct.unpack_from(">H", hello, 3)[0] != len(hello) - 5:
            return None  # not exactly one whole record
        pos = 5 + 4 + 2 + 32  # record and handshake headers, version, random
        pos += 1 + hello[pos]  # session id
        pos += 2 + struct.unpack_from(">H", hello, pos)[0]  # cipher suites
        pos += 1 + hello[pos]  # compression methods
        end = pos + 2 + struct.unpack_from(">H", hello, pos)[0]
        pos += 2
        while pos + 4 <= end:
            ext_type, ext_len = struct.unpack_from(">HH", hello, pos)
            pos += 4
            if ext_type == 0:  # server_name: list length, type, name length, name
                name_len = struct.unpack_from(">H", hello, pos + 3)[0]
                start = pos + 5
                if start + name_len > len(hello):
                    return None
                return start, start + name_len
            pos += ext_len
    except (IndexError, struct.error):
        return None
    return None


def shape(hello: bytes, strategy: Strategy) -> Optional[List[Tuple[bytes, bool]]]:
    """The ClientHello as TCP segments [(bytes, OOB byte after it)], or None to send it as is."""
    sni = sni_span(hello)
    if sni is None:
        return None
    size = len(hello)
    cuts = sorted(c for c in {p.resolve(size, sni) for p in strategy.records} if 5 < c < size)
    wire = hello
    if cuts:
        head, body = hello[:3], hello[5:]
        edges = [0] + [c - 5 for c in cuts] + [len(body)]
        wire = b"".join(head + struct.pack(">H", b - a) + body[a:b] for a, b in zip(edges, edges[1:]))

    def on_wire(pos: int) -> int:
        return pos + 5 * sum(1 for c in cuts if c <= pos)

    marks = {}
    for pos, oob in strategy.splits:
        at = on_wire(pos.resolve(size, sni))
        if 0 < at < len(wire):
            marks[at] = marks.get(at, False) or oob
    if not marks:
        return [(wire, False)]
    edges = [0] + sorted(marks) + [len(wire)]
    return [(wire[a:b], marks.get(b, False)) for a, b in zip(edges, edges[1:])]


# --- which connections, and the asyncio hook ---------------------------------

# None: follow the config; False: never; a Strategy: use it (probe_cf compares them).
override: contextvars.ContextVar = contextvars.ContextVar("tls_bypass_override", default=None)

_strategy: Optional[Strategy] = None  # the active strategy, None when off
_domains: Callable[[], Iterable[str]] = lambda: ()


def configure(strategy: Optional[Strategy], domains: Callable[[], Iterable[str]]) -> None:
    """Turns the bypass on with `strategy` (None: off) for hosts under `domains()`."""
    global _strategy, _domains
    _strategy = strategy
    _domains = domains
    _install()


def set_active(strategy: Optional[Strategy]) -> None:
    """Switches the strategy new connections use (None: plain TLS), keeping the hosts."""
    global _strategy
    _strategy = strategy


def active() -> Optional[Strategy]:
    return _strategy


def _matches(host: str, domains: Iterable[str]) -> bool:
    return any(host == d or host.endswith("." + d) for d in domains if d)


def plan(server_hostname: Optional[str]) -> Optional[Tuple[str, Strategy]]:
    """(SNI to use, strategy) for a new client TLS connection, or None to leave it."""
    chosen = override.get()
    strategy = _strategy if chosen is None else (chosen or None)
    if strategy is None or not server_hostname:
        return None
    host = server_hostname.lower().rstrip(".")
    domains = [d.lower() for d in _domains()]
    if not _matches(host, domains):
        return None
    sni = server_hostname
    if strategy.sni_parent:
        m = _KWS.match(host)
        if m and m.group(1) in domains:
            sni = m.group(1)
    return sni, strategy


class _HelloSplitter:
    """Transport wrapper that sends the first ClientHello as `strategy` says."""

    def __init__(self, transport, strategy: Strategy):
        self._transport = transport
        self._strategy = strategy
        self._first = strategy.shapes_hello

    def write(self, data):
        if self._first:
            self._first = False
            segments = shape(bytes(data), self._strategy)
            if segments is not None:
                for chunk, oob in segments:
                    self._transport.write(chunk)
                    if oob:
                        self._send_oob()
                return
        self._transport.write(data)

    def _send_oob(self) -> None:
        # Only when everything before it has left; otherwise it would jump the queue.
        if self._transport.get_write_buffer_size():
            return
        sock = self._transport.get_extra_info("socket")
        if sock is None:
            return
        try:
            # A second socket object on the same descriptor; detach() leaves it open.
            raw = socket.socket(sock.family, sock.type, sock.proto, fileno=sock.fileno())
        except OSError as exc:
            log.debug("TLS bypass: OOB byte not sent: %r", exc)
            return
        try:
            raw.send(b"a", socket.MSG_OOB)
        except OSError as exc:
            log.debug("TLS bypass: OOB byte not sent: %r", exc)
        finally:
            raw.detach()

    def __getattr__(self, name):
        return getattr(self._transport, name)


_installed = False

# How long closing an outgoing TLS connection waits for the server's close_notify
# (asyncio's default is 30 s). Stopping the core waits for its connections, and
# one CF HTTP/2 connection held a restart for 30 s on 18 Nov 2026; by then
# everything has been sent, so waiting longer gains nothing.
SSL_SHUTDOWN_TIMEOUT_S = 3.0


def _install() -> None:
    global _installed
    if _installed:
        return
    cls = asyncio.sslproto.SSLProtocol
    orig_init, orig_made = cls.__init__, cls.connection_made
    signature = inspect.signature(orig_init)

    def __init__(self, *args, **kwargs):
        bound = signature.bind(self, *args, **kwargs)
        self._andm_strategy = None
        if not bound.arguments.get("server_side", False):
            planned = plan(bound.arguments.get("server_hostname"))
            if planned is not None:
                bound.arguments["server_hostname"], self._andm_strategy = planned
            if bound.arguments.get("ssl_shutdown_timeout") is None:
                bound.arguments["ssl_shutdown_timeout"] = SSL_SHUTDOWN_TIMEOUT_S
        orig_init(*bound.args, **bound.kwargs)

    def connection_made(self, transport):
        strategy = getattr(self, "_andm_strategy", None)
        if strategy is not None and strategy.shapes_hello:
            transport = _HelloSplitter(transport, strategy)
        orig_made(self, transport)

    cls.__init__ = __init__
    cls.connection_made = connection_made
    _installed = True
