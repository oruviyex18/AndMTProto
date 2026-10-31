"""
Android entry point for the upstream tg-ws-proxy core.

Mirrors the proxy lifecycle in utils/tray_common.py (apply_proxy_config,
_run_proxy_thread, stop_proxy) without the desktop-only dependencies.
Review this file whenever upstream changes ProxyConfig, _run() or the
tray lifecycle code.

Kotlin (ProxyCore.kt) calls only start(), stop(), status() and proxy_link();
config and status travel as JSON strings. Errors reach Kotlin as
{"code", "detail", "suggested_port"} so the UI can show them in its own
language. Codes: bad_secret, bad_port, bad_dc_ip, bad_fake_tls,
port_in_use, permission, address, unknown.
"""
from __future__ import annotations

import asyncio
import collections
import errno
import json
import logging
import logging.handlers
import os
import functools
import socket
import ssl
import struct
import sys
import threading
import time
from typing import Any, Deque, Dict, List, Optional, Tuple

from proxy import __version__
from proxy.config import coerce_domain_list, parse_dc_ip_list, proxy_config
from proxy.stats import stats
from proxy import tg_ws_proxy
from proxy import utils as proxy_utils
from proxy.tg_ws_proxy import _run
from proxy.utils import DomainCensorFilter, get_link_host

log = logging.getLogger('tg-mtproto-proxy')

# Same keys and defaults as utils/default_config.py, plus fake_tls_domain.
DEFAULT_CONFIG: Dict[str, Any] = {
    "port": 1443,
    "host": "127.0.0.1",
    "dc_ip": ["2:149.154.167.220", "4:149.154.167.220"],
    "verbose": False,
    "log_max_mb": 5,
    "buf_kb": 256,
    "pool_size": 4,
    "cfproxy": True,
    "h2": True,
    "cfproxy_user_domain_enabled": False,
    "cfproxy_user_domain": [],
    "cfproxy_worker_enabled": False,
    "cfproxy_worker_domain": [],
    "force_test_dc": False,
    "no_secure": False,
    "fake_tls_domain": "",
}

# How far above a busy port to look for a free one.
_PORT_SEARCH_RANGE = 50
# How long stop() waits for a clean shutdown before dropping client connections.
_GRACEFUL_STOP_S = 2.0

_lock = threading.Lock()
_proxy_thread: Optional[threading.Thread] = None
# Set by stop(); the proxy thread checks it when it publishes _async_stop, so a
# stop() that arrives before the event loop exists is not lost.
_stop_requested: Optional[threading.Event] = None
# The running loop and its stop event, published by the proxy thread.
_async_stop: Optional[Tuple[asyncio.AbstractEventLoop, asyncio.Event]] = None
_last_error: Optional[Dict[str, Any]] = None
# (monotonic time, stats.connections_total) samples for the last-hour count;
# the first one is the newest sample at or before the start of the hour.
_HOUR_S = 3600.0
_SAMPLE_S = 10.0
_samples: Deque[Tuple[float, int]] = collections.deque()
_samples_lock = threading.Lock()
_log_handler: Optional[logging.Handler] = None


class ConfigError(ValueError):
    def __init__(self, code: str, detail: Any = None):
        super().__init__(code if detail is None else f"{code}: {detail}")
        self.code = code
        self.detail = detail


def _error(code: str, detail: Any = None,
           suggested_port: Optional[int] = None) -> Dict[str, Any]:
    return {
        "code": code,
        "detail": None if detail is None else str(detail),
        "suggested_port": suggested_port,
    }


def _merged(cfg_json: str) -> Dict[str, Any]:
    cfg = dict(DEFAULT_CONFIG)
    cfg.update(json.loads(cfg_json) if cfg_json else {})
    return cfg


def _secret(cfg: Dict[str, Any]) -> str:
    secret = str(cfg["secret"]).strip()
    try:
        if len(secret) != 32:
            raise ValueError
        bytes.fromhex(secret)
    except ValueError:
        raise ConfigError("bad_secret") from None
    return secret


def _port(cfg: Dict[str, Any]) -> int:
    try:
        port = int(cfg["port"])
        if not 1 <= port <= 65535:
            raise ValueError
    except (TypeError, ValueError):
        raise ConfigError("bad_port", cfg["port"]) from None
    return port


def _fake_tls_domain(cfg: Dict[str, Any]) -> str:
    """The Fake TLS domain as ASCII; IDN domains are converted to punycode."""
    domain = str(cfg["fake_tls_domain"]).strip()
    if not domain:
        return ""
    try:
        return domain.encode("idna").decode("ascii")
    except UnicodeError:
        raise ConfigError("bad_fake_tls", domain) from None


def _apply_config(cfg: Dict[str, Any]) -> None:
    """Port of tray_common.apply_proxy_config; raises ConfigError on bad input."""
    secret, port, fake_tls = _secret(cfg), _port(cfg), _fake_tls_domain(cfg)
    try:
        dc_redirects = parse_dc_ip_list(cfg["dc_ip"])
    except ValueError as exc:
        raise ConfigError("bad_dc_ip", getattr(exc, "entry", None)) from None

    pc = proxy_config
    pc.port = port
    pc.host = cfg["host"]
    pc.secret = secret
    pc.dc_redirects = dc_redirects
    pc.buffer_size = max(4, int(cfg["buf_kb"])) * 1024
    pc.pool_size = max(0, int(cfg["pool_size"]))
    pc.fallback_cfproxy = bool(cfg["cfproxy"])
    pc.cfproxy_h2_media = bool(cfg["h2"])
    user_domains = coerce_domain_list(cfg["cfproxy_user_domain"])
    worker_domains = coerce_domain_list(cfg["cfproxy_worker_domain"])
    pc.cfproxy_user_domains = (
        user_domains if cfg["cfproxy_user_domain_enabled"] else [])
    pc.cfproxy_worker_domains = (
        worker_domains if cfg["cfproxy_worker_enabled"] else [])
    pc.force_test_dc = bool(cfg["force_test_dc"])
    pc.disable_secure = bool(cfg["no_secure"])
    pc.fake_tls_domain = fake_tls


def _setup_logging(log_dir: str, cfg: Dict[str, Any]) -> None:
    global _log_handler
    root = logging.getLogger()
    root.setLevel(logging.DEBUG if cfg["verbose"] else logging.INFO)
    if _log_handler is not None:
        return

    fmt = logging.Formatter('%(asctime)s  %(levelname)-5s  %(message)s',
                            datefmt='%H:%M:%S')
    os.makedirs(log_dir, exist_ok=True)
    fh = logging.handlers.RotatingFileHandler(
        os.path.join(log_dir, 'proxy.log'),
        maxBytes=int(float(cfg["log_max_mb"]) * 1024 * 1024),
        backupCount=1, encoding='utf-8')
    # Chaquopy forwards stderr to logcat.
    console = logging.StreamHandler(sys.stderr)
    for h in (fh, console):
        h.setFormatter(fmt)
        h.addFilter(DomainCensorFilter())
        root.addHandler(h)
    for name in ('asyncio', 'httpx', 'httpcore'):
        logging.getLogger(name).setLevel(logging.WARNING)
    _log_handler = fh


def _run_proxy_thread(stop_requested: threading.Event) -> None:
    global _async_stop, _last_error
    # The loop is created in this thread: a Windows proactor loop created in
    # another thread may not wake up for call_soon_threadsafe().
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    stop_ev = asyncio.Event()
    with _lock:
        _async_stop = (loop, stop_ev)
        if stop_requested.is_set():
            stop_ev.set()  # stop() came before the loop existed
    try:
        loop.run_until_complete(_run_until_stopped(stop_ev))
    except Exception as exc:
        log.error("Proxy thread crashed: %s", repr(exc))
        _last_error = _describe_error(exc)
    finally:
        pending = [t for t in asyncio.all_tasks(loop) if not t.done()]
        for task in pending:
            task.cancel()
        if pending:
            loop.run_until_complete(
                asyncio.gather(*pending, return_exceptions=True))
        loop.run_until_complete(loop.shutdown_asyncgens())
        loop.close()
        with _lock:
            if _async_stop is not None and _async_stop[0] is loop:
                _async_stop = None


def _reset_stats(now: float) -> None:
    """Counters start from zero on every start, not once per app process."""
    stats.__init__()  # the core keeps references to this one object
    with _samples_lock:
        _samples.clear()
        _samples.append((now, 0))


def _record_sample(now: float) -> None:
    with _samples_lock:
        _samples.append((now, stats.connections_total))
        while len(_samples) > 1 and _samples[1][0] <= now - _HOUR_S:
            _samples.popleft()


def _connections_last_hour() -> int:
    """Connections opened in about the last hour (to within _SAMPLE_S)."""
    with _samples_lock:
        baseline = _samples[0][1] if _samples else 0
    return stats.connections_total - baseline


async def _sample_connections() -> None:
    while True:
        await asyncio.sleep(_SAMPLE_S)
        _record_sample(time.monotonic())


async def _run_until_stopped(stop_ev: asyncio.Event) -> None:
    """Runs upstream's _run(), cancelling it if stop comes before it listens.

    _run() only watches stop_ev once its server is up, but since 1.11.3 it
    first probes the Fake TLS site for up to 5 s, as long as stop()'s whole
    budget. Before the listener exists there is nothing to shut down cleanly,
    so cancelling is safe and keeps stop() and restarts fast.
    """
    run = asyncio.ensure_future(_run(stop_event=stop_ev))
    stopped = asyncio.ensure_future(stop_ev.wait())
    sampler = asyncio.ensure_future(_sample_connections())
    try:
        await asyncio.wait({run, stopped}, return_when=asyncio.FIRST_COMPLETED)
        if not run.done() and tg_ws_proxy._server_instance is None:
            log.info("Stopping before the proxy started listening")
            run.cancel()
        try:
            await run
        except asyncio.CancelledError:
            if not stop_ev.is_set():
                raise
    finally:
        stopped.cancel()
        sampler.cancel()


def _describe_error(exc: BaseException) -> Dict[str, Any]:
    port, host = proxy_config.port, proxy_config.host
    if isinstance(exc, OSError):
        if exc.errno == errno.EADDRINUSE:
            return _error("port_in_use", port, _find_free_port(host, port))
        if exc.errno == errno.EACCES:
            return _error("permission", port)
        if exc.errno == errno.EADDRNOTAVAIL:
            return _error("address", host)
    return _error("unknown", repr(exc))


def _find_free_port(host: str, busy: int) -> Optional[int]:
    """First port above `busy` that can be bound on `host`, or None."""
    for port in range(busy + 1, min(busy + _PORT_SEARCH_RANGE, 65535) + 1):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind((host, port))
            except OSError:
                continue
            return port
    return None


def _is_running() -> bool:
    return _proxy_thread is not None and _proxy_thread.is_alive()


_TLS_GROUP_X25519 = 0x001d
_EXT_SUPPORTED_GROUPS = 0x000a


def offered_tls_groups(client_hello: bytes) -> List[int]:
    """The supported_groups list of a TLS ClientHello record, [] if absent."""
    pos = 5 + 4 + 2 + 32  # record and handshake headers, version, random
    pos += 1 + client_hello[pos]  # session id
    pos += 2 + struct.unpack_from(">H", client_hello, pos)[0]  # cipher suites
    pos += 1 + client_hello[pos]  # compression methods
    end = pos + 2 + struct.unpack_from(">H", client_hello, pos)[0]
    pos += 2
    while pos + 4 <= end:
        ext_type, ext_len = struct.unpack_from(">HH", client_hello, pos)
        pos += 4
        if ext_type == _EXT_SUPPORTED_GROUPS:
            count = struct.unpack_from(">H", client_hello, pos)[0] // 2
            return list(struct.unpack_from(f">{count}H", client_hello, pos + 2))
        pos += ext_len
    return []


def client_hello(context: ssl.SSLContext) -> bytes:
    """The first ClientHello `context` would send, built without a network."""
    outgoing = ssl.MemoryBIO()
    tls = context.wrap_bio(ssl.MemoryBIO(), outgoing, server_hostname="example.com")
    try:
        tls.do_handshake()
    except ssl.SSLWantReadError:
        pass
    return outgoing.read()


@functools.lru_cache(maxsize=1)
def _check_core_tls() -> None:
    """Logs an error if the core's TLS offers more than X25519.

    Some ISPs' DPI stalls the large post-quantum ClientHello that OpenSSL 3.5
    sends by default (android.6). Upstream limits its contexts to X25519 since
    1.11.3 but ignores failures, so check the result on the device itself.
    """
    try:
        groups = offered_tls_groups(client_hello(proxy_utils.create_ssl_context()))
    except Exception as exc:
        log.error("Cannot check the core's TLS key exchange: %r", exc)
        return
    if groups != [_TLS_GROUP_X25519]:
        log.error("Core TLS offers key exchange groups %s, not X25519 only; "
                  "connections may stall on ISPs that block large handshakes",
                  [f"0x{g:04x}" for g in groups])


def start(cfg_json: str, log_dir: str) -> Optional[str]:
    """Start the proxy in a background thread.

    Returns None on success (or when it is already running), or the error as
    JSON (see _error) when the config is invalid. Bind errors happen later in
    the proxy thread and show up in status().
    """
    global _proxy_thread, _stop_requested, _last_error
    with _lock:
        if _is_running():
            return None
        cfg = _merged(cfg_json)
        _setup_logging(log_dir, cfg)
        try:
            _apply_config(cfg)
        except ConfigError as exc:
            _last_error = _error(exc.code, exc.detail)
            log.error("Bad config: %s", exc)
            return json.dumps(_last_error)
        _last_error = None
        log.info("AndMTProto: core %s, Python %s",
                 __version__, sys.version.split()[0])
        _check_core_tls()
        _reset_stats(time.monotonic())
        log.info("Starting proxy on %s:%d ...",
                 proxy_config.host, proxy_config.port)
        _stop_requested = threading.Event()
        _proxy_thread = threading.Thread(
            target=_run_proxy_thread, args=(_stop_requested,),
            daemon=True, name="proxy")
        _proxy_thread.start()
        return None


def stop(timeout: float = 5.0) -> None:
    """Stop the proxy and wait up to `timeout` seconds for it to exit."""
    global _proxy_thread
    with _lock:
        thread = _proxy_thread
        if _stop_requested is not None:
            _stop_requested.set()
        if _async_stop is not None:
            loop, stop_ev = _async_stop
            try:
                loop.call_soon_threadsafe(stop_ev.set)
            except RuntimeError:
                pass  # loop already closed
    if thread is None:
        return
    thread.join(timeout=min(_GRACEFUL_STOP_S, timeout))
    if thread.is_alive():
        _abort_lingering_clients()
        thread.join(timeout=max(0.0, timeout - _GRACEFUL_STOP_S))
    with _lock:
        if thread.is_alive():
            # Keep the reference so status() and start() still see it.
            log.warning("Proxy thread did not stop within %.0fs", timeout)
            return
        if _proxy_thread is thread:
            _proxy_thread = None
    log.info("Proxy stopped")


def _abort_lingering_clients() -> None:
    """Unblock upstream's shutdown when a client connection won't close.

    Since Python 3.12, Server.wait_closed() in _run() waits for every client
    connection; one that never finishes closing keeps the proxy thread alive.
    Upstream builds its desktop apps with Python 3.11, so it doesn't hit this.
    """
    with _lock:
        stop_state = _async_stop
    server = tg_ws_proxy._server_instance
    abort = getattr(server, "abort_clients", None)  # Python 3.13+
    if stop_state is None or abort is None:
        return
    log.warning("Proxy is still closing connections; aborting them")
    try:
        stop_state[0].call_soon_threadsafe(abort)
    except RuntimeError:
        pass  # loop already closed


def status() -> str:
    return json.dumps({
        "running": _is_running(),
        # False while _run() prepares (e.g. the Fake TLS probe) before binding.
        "listening": tg_ws_proxy._server_instance is not None,
        "error": _last_error,
        "core_version": __version__,
        "active": stats.connections_active,
        "total": stats.connections_total,
        "last_hour": _connections_last_hour(),
        "bytes_up": stats.bytes_up,
        "bytes_down": stats.bytes_down,
    })


def proxy_link(cfg_json: str) -> str:
    """tg://proxy link for the config: dd-secret, or ee-secret with Fake TLS."""
    cfg = _merged(cfg_json)
    host = get_link_host(cfg["host"])
    secret = str(cfg["secret"]).strip()
    try:
        fake_tls = _fake_tls_domain(cfg)
    except ConfigError:
        fake_tls = ""  # start() reports it; the plain link still works
    if fake_tls:
        secret = "ee" + secret + fake_tls.encode("ascii").hex()
    else:
        secret = "dd" + secret
    return f"tg://proxy?server={host}&port={cfg['port']}&secret={secret}"
