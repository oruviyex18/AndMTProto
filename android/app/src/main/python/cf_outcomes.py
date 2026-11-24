"""
How the core's Cloudflare attempts go (AndMTProto only), for the automatic CF
recheck and the app's plain-language line about why Cloudflare fails.

- cf_failed: each CF proxy host the core tried that didn't open a WebSocket,
  plus one per connection that tried none because all were on cooldown.
- cf_recent: CF proxy outcomes over the last two minutes, by reason.
- worker_trouble: why the user's own Workers fail, when every one tried does.

install() hooks the core's connect code without editing it: the RawWebSocket
that cf_websocket.py and pool.py imported, and bridge.connect_cf_domains.
Review it whenever upstream changes cf_websocket.py, pool.py or
_cfproxy_fallback in bridge.py.
"""
from __future__ import annotations

import asyncio
import collections
import contextvars
import socket
import ssl
import threading
import time
from typing import Any, Deque, Dict, List, Optional, Tuple

from proxy.config import proxy_config
from proxy.raw_websocket import RawWebSocket, WsHandshakeError


class _CfFailureCounter:
    """Failed CF proxy attempts (cf_failed in status()); the core counts only
    the connections that got a WebSocket (stats.connections_cfproxy). One per
    CF proxy host the core tried that didn't open, and one per connection
    that got none without trying a host: since 1.12.0 a host and TLS mode that
    timed out sit out 30 s, and a connection that finds them all sitting out
    fails at once. See _install_cf_failure_count."""

    def __init__(self) -> None:
        self.count = 0


_cf_failures = _CfFailureCounter()

# Recent CF proxy attempts for the app's plain-language line (status()["cf_recent"]):
# (time.monotonic(), route "cf", "ok" or a _cf_failure_reason).
_CF_RECENT_S = 120.0
_cf_events: Deque[Tuple[float, str, str]] = collections.deque(maxlen=4000)
_cf_events_lock = threading.Lock()


def _cf_failure_reason(exc: BaseException) -> str:
    """Why a CF attempt failed, in the few kinds the app can explain to the user."""
    if isinstance(exc, WsHandshakeError):
        code = exc.status_code
        if code == 0:
            return "reset"  # the connection closed before any HTTP answer
        if code == 429:
            return "limit"  # a Worker's daily request limit (Cloudflare's free plan)
        if code == 403:
            return "forbidden"
        if code >= 500:
            return "server_error"  # e.g. a Worker whose code throws (error 1101)
        return "http"
    if isinstance(exc, (asyncio.TimeoutError, TimeoutError)):
        return "timeout"
    if isinstance(exc, ssl.SSLCertVerificationError):
        return "tls"  # a certificate that isn't Cloudflare's: someone answers in its place
    if isinstance(exc, ssl.SSLEOFError):
        return "reset"  # cut off in the middle of the TLS handshake
    if isinstance(exc, ssl.SSLError):
        return "tls"
    if isinstance(exc, socket.gaierror):
        return "dns"
    if isinstance(exc, ConnectionRefusedError):
        return "refused"
    if isinstance(exc, (ConnectionResetError, ConnectionAbortedError, BrokenPipeError,
                        asyncio.IncompleteReadError, EOFError)):
        return "reset"
    if isinstance(exc, OSError):
        return "network"
    return "other"


def _record_cf(route: str, outcome: str) -> None:
    with _cf_events_lock:
        _cf_events.append((time.monotonic(), route, outcome))


# The user's own Workers, by domain: [last outcome, failures in a row]. The core
# pauses a Worker after a failure (60 s, or until the daily reset after HTTP 429)
# and doesn't try it meanwhile, so a time window would see too few attempts.
_worker_outcomes: Dict[str, List[Any]] = {}


def _record_worker(domain: str, outcome: str) -> None:
    with _cf_events_lock:
        last = _worker_outcomes.setdefault(domain, ["ok", 0])
        last[0] = outcome
        last[1] = 0 if outcome == "ok" else last[1] + 1


def _worker_trouble() -> Optional[str]:
    """The commonest reason when every Worker tried since start is failing (twice in
    a row, or once for the daily limit, which lasts until the reset), else None."""
    with _cf_events_lock:
        # Copied inside the lock: _record_worker sets outcome and row as two steps.
        tried = [(outcome, row) for outcome, row in _worker_outcomes.values()]
    if not tried:
        return None
    failing = [outcome for outcome, row in tried if row >= 2 or (row >= 1 and outcome == "limit")]
    if len(failing) < len(tried):
        return None
    return max(sorted(set(failing)), key=failing.count)


def _cf_recent(now: float) -> Dict[str, Dict[str, int]]:
    """{"cf": {"ok": 3, "timeout": 12}} over the last _CF_RECENT_S."""
    recent: Dict[str, Dict[str, int]] = {}
    with _cf_events_lock:
        for at, route, outcome in _cf_events:
            if now - at <= _CF_RECENT_S:
                counts = recent.setdefault(route, {})
                counts[outcome] = counts.get(outcome, 0) + 1
    return recent


# The failed tries of the CF proxy connection running in this task.
_cf_tries: "contextvars.ContextVar[Optional[List[int]]]" = contextvars.ContextVar(
    "andmtproto_cf_tries", default=None)


def _install_cf_failure_count() -> None:
    """Counts the core's failed CF proxy attempts without editing it:
    cf_websocket.connect_cf_domains opens each host through the RawWebSocket
    it imported, so that name gets a subclass whose connect() counts the
    tries that fail (a timeout or an error, not a cancelled connection), and
    bridge.connect_cf_domains a wrapper that counts a connection that got no
    WebSocket and tried no host. Review this whenever upstream changes
    cf_websocket.py or _cfproxy_fallback in bridge.py."""
    from proxy import bridge, cf_websocket
    if getattr(bridge, "_andmtproto_cf_count", False):
        return

    class _CountedRawWebSocket(RawWebSocket):
        @staticmethod
        async def connect(*args: Any, **kwargs: Any) -> RawWebSocket:
            try:
                ws = await RawWebSocket.connect(*args, **kwargs)
            except Exception as exc:
                _cf_failures.count += 1
                _record_cf("cf", _cf_failure_reason(exc))
                tries = _cf_tries.get()
                if tries is not None:
                    tries[0] += 1
                raise
            _record_cf("cf", "ok")
            return ws

    connect_domains = bridge.connect_cf_domains

    async def counted(*args: Any, **kwargs: Any) -> Any:
        tries = [0]
        token = _cf_tries.set(tries)
        try:
            result = await connect_domains(*args, **kwargs)
        finally:
            _cf_tries.reset(token)
        if result is None and not tries[0]:
            _cf_failures.count += 1  # every host was sitting out a timeout
            _record_cf("cf", "timeout")
        return result

    cf_websocket.RawWebSocket = _CountedRawWebSocket
    bridge.connect_cf_domains = counted
    bridge._andmtproto_cf_count = True
    _install_worker_outcomes()


def _install_worker_outcomes() -> None:
    """Records how connections to the user's own CF Workers went: the core's
    pool.py opens them (cf_worker_pool.connect_one) through the RawWebSocket it
    imported, which also opens the direct WebSockets, so only hosts that are
    one of the configured Worker domains count. Review with pool.py."""
    from proxy import pool

    class _WorkerAwareRawWebSocket(RawWebSocket):
        @staticmethod
        async def connect(host: str, *args: Any, **kwargs: Any) -> RawWebSocket:
            workers = {d.lower() for d in proxy_config.cfproxy_worker_domains}
            if host.lower() not in workers:
                return await RawWebSocket.connect(host, *args, **kwargs)
            try:
                ws = await RawWebSocket.connect(host, *args, **kwargs)
            except Exception as exc:
                _record_worker(host.lower(), _cf_failure_reason(exc))
                raise
            _watch_first_read(ws, host.lower())
            return ws

    pool.RawWebSocket = _WorkerAwareRawWebSocket


def _watch_first_read(ws: RawWebSocket, domain: str) -> None:
    """A Worker that accepts the WebSocket can still fail to reach Telegram: ours
    then closes it (1011) before any data. So the Worker counts as working only
    once data comes back, and as "worker_closed" when the first read gets none.
    (Pooled WebSockets that are never used count as neither.)"""
    read = ws.recv

    async def first_recv() -> Optional[bytes]:
        ws.recv = read  # later reads go straight through
        try:
            data = await read()
        except asyncio.CancelledError:
            raise
        except Exception:
            _record_worker(domain, "worker_closed")
            raise
        _record_worker(domain, "ok" if data else "worker_closed")
        return data

    ws.recv = first_recv  # type: ignore[method-assign]


def install() -> None:
    """Hooks the core's CF and Worker connect code; idempotent."""
    _install_cf_failure_count()


def reset() -> None:
    """From zero at each core start."""
    _cf_failures.count = 0
    with _cf_events_lock:
        _cf_events.clear()
        _worker_outcomes.clear()


def status_fields() -> Dict[str, Any]:
    """The keys android_entry.status() reports for ProxyService and the app."""
    return {
        # CF proxy WebSockets that didn't open (each host tried counts), so
        # ProxyService can tell when the CF connection broke.
        "cf_failed": _cf_failures.count,
        # How CF proxy attempts went lately, by reason (_cf_failure_reason), and
        # why the user's own Workers all fail, if they do.
        "cf_recent": _cf_recent(time.monotonic()),
        "worker_trouble": _worker_trouble(),
    }
