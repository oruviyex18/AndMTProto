"""
Android entry point for the upstream tg-ws-proxy core.

Mirrors the proxy lifecycle in utils/tray_common.py (apply_proxy_config,
_run_proxy_thread, stop_proxy) without the desktop-only dependencies.
Review this file whenever upstream changes ProxyConfig, _run() or the
tray lifecycle code.

Kotlin (ProxyCore.kt) calls only start(), stop(), status(), note(),
probe_cf(), forget_cf_pick(), set_pool_paused(), check_tls_strategy() and
proxy_link();
config and status travel as JSON strings. Errors reach Kotlin as
{"code", "detail", "suggested_port"} so the UI can show them in its own
language. Codes: bad_secret, bad_port, bad_dc_ip, bad_fake_tls,
bad_tls_strategy, port_in_use, permission, address, unknown.
"""
from __future__ import annotations

import asyncio
import collections
import errno
import json
import logging
import logging.handlers
import os
import random
import re
import functools
import ipaddress
import socket
import ssl
import struct
import sys
import threading
import time
from typing import Any, Deque, Dict, List, Optional, Tuple

from proxy import __version__
from proxy.balancer import balancer
from proxy.config import (CFPROXY_DEFAULT_DOMAINS, coerce_domain_list,
                          parse_dc_ip_list, proxy_config)
from proxy._aes import Cipher, algorithms, modes
from proxy import raw_websocket
from proxy.raw_websocket import RawWebSocket
from proxy.stats import stats
from proxy import tg_ws_proxy
from proxy import utils as proxy_utils
from proxy.tg_ws_proxy import _run
from proxy.utils import DomainCensorFilter, get_link_host

import cf_outcomes
import tls_bypass
import ws_pool_control

# Counts the core's CF attempts for the CF recheck and the app's reasons line.
cf_outcomes.install()

# ws_pool.get() opens WebSockets on demand when the pool has none to give.
ws_pool_control.install()

log = logging.getLogger('tg-mtproto-proxy')

# Same keys and defaults as utils/default_config.py, plus fake_tls_domain.
DEFAULT_CONFIG: Dict[str, Any] = {
    "port": 1443,
    "host": "127.0.0.1",
    "dc_ip": ["2:149.154.167.220", "4:149.154.167.220"],
    "verbose": False,
    "log_max_mb": 5,
    "buf_kb": 256,
    "pool_size": ws_pool_control.DEFAULT_POOL_SIZE,
    "cfproxy": True,
    "h2": True,
    "cfproxy_user_domain_enabled": False,
    "cfproxy_user_domain": [],
    "cfproxy_worker_enabled": False,
    "cfproxy_worker_domain": [],
    "force_test_dc": False,
    "no_secure": False,
    "fake_tls_domain": "",
    # AndMTProto only: see tls_bypass.py.
    "tls_bypass": True,
    "tls_strategy": tls_bypass.AUTO,
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
# (monotonic time, stats.connections_total, stats.connections_bad) samples for
# the last-hour count; the first one is the newest sample at or before the
# start of the hour.
_HOUR_S = 3600.0
_SAMPLE_S = 10.0
# Window for counting rejected handshakes, which the UI turns into a hint.
_BAD_WINDOW_S = 600.0
_samples: Deque[Tuple[float, int, int]] = collections.deque()
_samples_lock = threading.Lock()
_log_handler: Optional[logging.Handler] = None


class SecretCensorFilter(logging.Filter):
    """Hides the proxy secret, which the core logs at start (alone and in the
    tg:// links). The link stays available through proxy_link()."""

    def filter(self, record):
        message = record.getMessage()
        secret = proxy_config.secret
        if secret:
            message = re.sub(re.escape(secret), "<secret>", message,
                             flags=re.IGNORECASE)
        record.msg = message
        record.args = ()
        return True


# How the core logs the secret; versions up to 1.11.4-android.1 kept it in the log.
_LOGGED_SECRET = re.compile(r"(Secret:\s+|secret=(?:dd|ee))[0-9a-fA-F]{32}")


def _scrub_logged_secrets(path: str) -> None:
    """Hide secrets that older versions wrote to an existing log file."""
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
        scrubbed = _LOGGED_SECRET.sub(r"\1<secret>", text)
        if scrubbed != text:
            with open(path, "w", encoding="utf-8") as f:
                f.write(scrubbed)
    except OSError:
        pass


def _is_loopback(host: str) -> bool:
    """True if only apps on this device can connect to `host`."""
    host = host.strip()
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


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
    strategy = _tls_strategy(cfg)
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
    ws_pool_control.configure(int(cfg["pool_size"]))
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
    global _probe_strategy, _auto_pick_on, _picked
    _probe_strategy = strategy
    _picked = _UNPICKED
    _auto_pick_on = strategy is None and bool(cfg["tls_bypass"])
    if strategy is None:  # automatic: the usual strategy until _auto_pick() has checked
        strategy = tls_bypass.parse(tls_bypass.DEFAULT_STRATEGY)
    tls_bypass.configure(strategy if cfg["tls_bypass"] else None, _bypass_domains)


# The strategy probe_cf tries, set by start() even while the bypass is off;
# None: automatic, i.e. try every candidate (see _best_strategy).
_probe_strategy: Optional["tls_bypass.Strategy"] = None
# The bypass is on with the automatic strategy: picks change what the core uses.
_auto_pick_on = False
# What _use_pick last applied or reported since start: a strategy, False for
# plain TLS, None when nothing got through, or _UNPICKED before the first check.
_UNPICKED = object()
_picked: Any = _UNPICKED


def _is_auto(text: str) -> bool:
    return not text.strip() or text.strip().lower() == tls_bypass.AUTO


def _tls_strategy(cfg: Dict[str, Any]) -> Optional["tls_bypass.Strategy"]:
    """The configured strategy; None for the automatic one."""
    text = str(cfg["tls_strategy"])
    if _is_auto(text):
        return None
    try:
        return tls_bypass.parse(text)
    except tls_bypass.StrategyError as exc:
        if cfg["tls_bypass"]:
            raise ConfigError("bad_tls_strategy", exc.token) from None
        return None


def _bypass_domains() -> List[str]:
    """Hosts under these get the TLS bypass: the CF proxy, CF workers and
    Telegram's own WebSocket hosts, whose TLS the same DPI cuts by name."""
    pc = proxy_config
    return (list(CFPROXY_DEFAULT_DOMAINS) + list(balancer.domains or [])
            + list(pc.cfproxy_user_domains) + list(pc.cfproxy_worker_domains)
            + ["web.telegram.org"])


def check_tls_strategy(text: str) -> str:
    """'' if `text` is a valid bypass strategy ('auto' or empty: automatic),
    else the token that is not."""
    if _is_auto(text):
        return ""
    try:
        tls_bypass.parse(text)
    except tls_bypass.StrategyError as exc:
        return exc.token or text.strip()
    return ""


def _setup_logging(log_dir: str, cfg: Dict[str, Any]) -> None:
    global _log_handler
    root = logging.getLogger()
    root.setLevel(logging.DEBUG if cfg["verbose"] else logging.INFO)
    if _log_handler is not None:
        return

    fmt = logging.Formatter('%(asctime)s  %(levelname)-5s  %(message)s',
                            datefmt='%H:%M:%S')
    # The file keeps the date too: it is shared and read days later; logcat has its own.
    file_fmt = logging.Formatter('%(asctime)s  %(levelname)-5s  %(message)s',
                                 datefmt='%Y-%m-%d %H:%M:%S')
    os.makedirs(log_dir, exist_ok=True)
    for name in ('proxy.log', 'proxy.log.1'):
        _scrub_logged_secrets(os.path.join(log_dir, name))
    fh = logging.handlers.RotatingFileHandler(
        os.path.join(log_dir, 'proxy.log'),
        maxBytes=int(float(cfg["log_max_mb"]) * 1024 * 1024),
        backupCount=1, encoding='utf-8')
    # Chaquopy forwards stderr to logcat.
    console = logging.StreamHandler(sys.stderr)
    fh.setFormatter(file_fmt)
    console.setFormatter(fmt)
    for h in (fh, console):
        h.addFilter(DomainCensorFilter())
        h.addFilter(SecretCensorFilter())
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
    cf_outcomes.reset()
    with _samples_lock:
        _samples.clear()
        _samples.append((now, 0, 0))


def _record_sample(now: float) -> None:
    with _samples_lock:
        _samples.append((now, stats.connections_total, stats.connections_bad))
        while len(_samples) > 1 and _samples[1][0] <= now - _HOUR_S:
            _samples.popleft()


def _connections_last_hour() -> int:
    """Connections opened in about the last hour (to within _SAMPLE_S)."""
    with _samples_lock:
        baseline = _samples[0][1] if _samples else 0
    return stats.connections_total - baseline


def _bad_handshakes_recent(now: float) -> int:
    """Handshakes rejected (wrong secret or protocol) in about the last
    _BAD_WINDOW_S. A steady stream means Telegram keeps checking a proxy entry
    with an old secret, or an ee link after Fake TLS was turned off."""
    baseline = 0
    with _samples_lock:
        for sampled_at, _, bad in _samples:
            if sampled_at > now - _BAD_WINDOW_S:
                break
            baseline = bad
    return stats.connections_bad - baseline


async def _sample_connections() -> None:
    while True:
        await asyncio.sleep(_SAMPLE_S)
        _record_sample(time.monotonic())


def set_pool_paused(paused: bool) -> None:
    """Pauses the core's WebSocket pools (screen off) or fills them again; see
    ws_pool_control.set_paused. Also works before start(): the next core
    starts paused."""
    with _lock:
        if not ws_pool_control.set_paused(paused):
            return
        core = _async_stop
    ws_pool_control.apply_to_core(core, paused)


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
    picker = asyncio.ensure_future(_auto_pick()) if _auto_pick_on else None
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
        if picker is not None:
            picker.cancel()


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
    _wait_for_stopping_thread()
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
        if proxy_config.disable_secure:
            log.info("AndMTProto: CF proxy without TLS (port 80)")
        elif cfg["tls_bypass"] and _probe_strategy is None:
            log.info("AndMTProto: TLS-blocking bypass on, automatic strategy; "
                     "using %s until the check picks one", tls_bypass.DEFAULT_STRATEGY)
        elif cfg["tls_bypass"]:
            log.info("AndMTProto: TLS-blocking bypass on: %s", _probe_strategy.text)
        else:
            log.info("AndMTProto: plain TLS, no blocking bypass")
        _reset_stats(time.monotonic())
        ws_pool_control.reset_routes()
        log.info("Starting proxy on %s:%d ...",
                 proxy_config.host, proxy_config.port)
        if not _is_loopback(proxy_config.host):
            log.warning("%s is not a loopback address: other devices on the "
                        "network can connect, and anyone with the secret can "
                        "use the proxy", proxy_config.host)
        _stop_requested = threading.Event()
        _proxy_thread = threading.Thread(
            target=_run_proxy_thread, args=(_stop_requested,),
            daemon=True, name="proxy")
        _proxy_thread.start()
        return None


# How long start() waits for a core that stop() left still shutting down.
_STOPPING_WAIT_S = 10.0


def _wait_for_stopping_thread() -> None:
    """A restart's stop() can return while the old core still closes connections;
    starting then would be skipped as "already running" and leave nothing running
    once that thread ends. Wait for it a little longer instead."""
    thread, stopping = _proxy_thread, _stop_requested
    if thread is None or stopping is None or not stopping.is_set() or not thread.is_alive():
        return
    thread.join(_STOPPING_WAIT_S)
    if thread.is_alive():
        log.warning("The previous proxy core is still stopping; not starting a new one")


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
        "bad_recent": _bad_handshakes_recent(time.monotonic()),
        # Routes since start, for the UI's route chips; "routed" > 0 means a
        # client got past the handshake, i.e. Telegram uses this proxy.
        "ws": stats.connections_ws,
        "cf": stats.connections_cfproxy + stats.connections_h2,
        "routed": (stats.connections_ws + stats.connections_cfproxy
                   + stats.connections_h2 + stats.connections_tcp_fallback),
        "bytes_up": stats.bytes_up,
        "bytes_down": stats.bytes_down,
        # CF proxy WebSockets that opened; cf_outcomes adds the ones that didn't.
        "cf_ok": stats.connections_cfproxy,
        **cf_outcomes.status_fields(),
    })


_PROBE_DOMAINS = 3
_PROBE_TIMEOUT_S = 10.0  # a reply via the CF proxy took 3-6 s through a VPN


_PROBE_DC = 2  # the probe hosts are kws2.<domain>
_REQ_PQ_MULTI = 0xBE7E8EF1

# Some ISPs (an MTS network on 18 Nov 2026) let connections to Cloudflare
# carry about 16 KB and then freeze them: the small check passes, but
# Telegram gets almost nothing. The large check moves more than that each way,
# to Cloudflare only (nothing of it reaches Telegram, so thousands of phones
# checking don't look like a flood from the shared CF proxy).
_LARGE_BYTES = 24 * 1024
_LARGE_UPLOAD = 25 * 1024  # a POST body, before the requests below
_TRACE_REQUESTS = 50       # x ~0.8 KB /cdn-cgi/trace answers, served by every Cloudflare host
_LARGE_HOSTS = 2           # at once: one overloaded shared domain doesn't fail a way
_LARGE_TIMEOUT_FACTOR = 2  # x the small check's: about 50 KB must cross a slow link too


def _probe_stream(dc: int = _PROBE_DC) -> Tuple[bytes, Any]:
    """The obfuscated start Telegram sends first for `dc`, and the encryptor
    for what follows it on that connection."""
    start = tg_ws_proxy._generate_relay_init(proxy_utils.PROTO_TAG_ABRIDGED, dc)
    key_end = proxy_utils.SKIP_LEN + proxy_utils.PREKEY_LEN
    encryptor = Cipher(algorithms.AES(start[proxy_utils.SKIP_LEN:key_end]),
                       modes.CTR(start[key_end:key_end + proxy_utils.IV_LEN])).encryptor()
    encryptor.update(start)  # the stream goes on after the 64-byte start
    return start, encryptor


def _req_pq_multi() -> bytes:
    """An unencrypted req_pq_multi in abridged framing (41 bytes), which the DC
    answers with resPQ."""
    message_id = int(time.time() * (1 << 32)) & ~3
    body = (bytes(8) + struct.pack("<qI", message_id, 20)
            + struct.pack("<I", _REQ_PQ_MULTI) + os.urandom(16))
    return bytes([len(body) // 4]) + body


def _probe_request(dc: int = _PROBE_DC) -> Tuple[bytes, bytes]:
    """What Telegram sends first through the CF proxy: the obfuscated start
    for `dc`, then a req_pq_multi. Returns (start, request) as two WebSocket
    messages."""
    start, encryptor = _probe_stream(dc)
    return start, encryptor.update(_req_pq_multi())


async def _ws_works(domain: str, secure: bool, timeout: float, bypass: Any = False) -> bool:
    """True if Telegram answers through a WebSocket to `domain`, opened the
    way the core opens it with the TLS bypass `bypass` (a Strategy, or False
    for plain TLS). An open WebSocket isn't enough: on 18 Nov 2026 Beeline LTE
    let the TLS split through but then no data came back."""
    tls_bypass.override.set(bypass)  # this task's own context

    async def exchange() -> bool:
        ws = await RawWebSocket.connect(domain, domain, timeout=timeout, secure=secure)
        try:
            start, request = _probe_request()
            await ws.send(start)
            await ws.send(request)
            return bool(await ws.recv())
        finally:
            ws.writer.close()

    try:
        return await asyncio.wait_for(exchange(), timeout=timeout)
    except Exception:
        return False


async def _answering_options(domains: List[str], timeout: float) -> List[Tuple[Any, List[str]]]:
    """Tries every candidate strategy, then plain TLS (False), on `domains` at
    once. Returns those Telegram answered through, in tls_bypass.CANDIDATES
    order, each with the hosts that answered."""
    hosts = [f"kws2.{d}" for d in domains]
    options: List[Any] = [tls_bypass.parse(t) for t in tls_bypass.CANDIDATES] + [False]
    results = await asyncio.gather(
        *(_ws_works(h, True, timeout, option) for option in options for h in hosts))
    n = len(hosts)
    answering = []
    for i, option in enumerate(options):
        answered = [h for h, ok in zip(hosts, results[i * n:(i + 1) * n]) if ok]
        if answered:
            answering.append((option, answered))
    return answering


def _use_pick(choice: Any) -> None:
    """Applies a _best_strategy choice while the automatic strategy is on."""
    global _picked
    if not _auto_pick_on or choice == _picked:
        return  # the core's pick and probe_cf share one check: log it once
    _picked = choice
    if choice is None:
        current = tls_bypass.active()
        log.warning("AndMTProto: automatic TLS strategy: nothing got through over "
                    "TLS; keeping %s", current.text if current else "plain TLS")
        return
    tls_bypass.set_active(choice or None)
    log.info("AndMTProto: automatic TLS strategy: %s",
             choice.text if choice else "plain TLS (no strategy got through, plain TLS did)")


def _probe_domains(count: int) -> List[str]:
    pool = list(balancer.domains or proxy_config.cfproxy_user_domains
                or CFPROXY_DEFAULT_DOMAINS)
    return random.sample(pool, min(count, len(pool)))


async def _best_strategy(options: List[Tuple[Any, List[str]]], timeout: float) -> Tuple[Any, Any]:
    """Of the strategies Telegram answered through (from _answering_options),
    the first that also carries large data, tried in order, so a strategy the
    network cuts off (an MTS Wi-Fi froze the TLS split at about 12 KB on
    19 Nov 2026) gives way to one it doesn't. Returns (that strategy or None,
    the choice for _use_pick: it, else the first that answered, False if only
    plain TLS did, None if nothing did)."""
    strategies = [(option, hosts) for option, hosts in options if option]
    for option, hosts in strategies:
        if await _large_works(hosts, True, timeout, option):
            return option, option
    return None, (strategies[0][0] if strategies else (False if options else None))


# The last strategy check (see _checked_strategies): (time.monotonic(), the
# answering options, the strategy that carries large data, the choice).
_PICK_FRESH_S = 120.0
_pick_lock = threading.Lock()  # the core's loop and probe_cf's run in different threads
_last_pick: Optional[Tuple[float, List[Tuple[Any, List[str]]], Any, Any]] = None


def forget_cf_pick() -> None:
    """ProxyService calls this when the network changes, the user restarts the
    proxy, or nothing gets through: the next check starts afresh."""
    global _last_pick
    _last_pick = None


async def _checked_strategies(timeout: float) -> Tuple[List[Tuple[Any, List[str]]], Any, Any]:
    """The automatic strategy's check (_answering_options, then
    _best_strategy): returns (options, full strategy, choice). The core's
    start-up pick and ProxyService's check share it: one runs at a time and
    the other takes its result while it is fresh, so a start, or a restart the
    check itself caused, doesn't check twice or race on the active strategy."""
    global _last_pick
    while not _pick_lock.acquire(blocking=False):
        await asyncio.sleep(0.1)
    try:
        last = _last_pick
        if last is not None and time.monotonic() - last[0] < _PICK_FRESH_S:
            return last[1], last[2], last[3]
        options = await _answering_options(_probe_domains(_PROBE_DOMAINS), timeout)
        full, choice = await _best_strategy(options, timeout)
        _last_pick = (time.monotonic(), options, full, choice)
        return options, full, choice
    finally:
        _pick_lock.release()


async def _auto_pick() -> None:
    """At core start, picks the automatic strategy for this network."""
    try:
        _use_pick((await _checked_strategies(_PROBE_TIMEOUT_S))[2])
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        log.warning("AndMTProto: automatic TLS strategy check failed: %r", exc)


async def _transfer_size(domain: str, secure: bool, timeout: float,
                         bypass: Any = False, port: Optional[int] = None) -> int:
    """How many bytes of answers came back within `timeout` on one connection
    to `domain`, opened like the core's (with the TLS bypass `bypass`), for a
    _LARGE_UPLOAD-byte POST and then _TRACE_REQUESTS requests for Cloudflare's
    own /cdn-cgi/trace page, all sent at once. Cloudflare answers those
    requests only after reading the whole POST body, so more than _LARGE_BYTES
    back means large data got through both ways."""
    tls_bypass.override.set(bypass)  # this task's own context
    got = 0

    async def fetch() -> None:
        nonlocal got
        tls = {"ssl": raw_websocket._ssl_ctx, "server_hostname": domain} if secure else {}
        reader, writer = await asyncio.open_connection(
            domain, port or (443 if secure else 80), **tls)
        try:
            writer.write(
                (f"POST /cdn-cgi/trace HTTP/1.1\r\nHost: {domain}\r\n"
                 f"Content-Type: application/octet-stream\r\nContent-Length: {_LARGE_UPLOAD}\r\n"
                 f"Connection: keep-alive\r\n\r\n").encode()
                + os.urandom(_LARGE_UPLOAD)
                + b"".join(
                    (f"GET /cdn-cgi/trace HTTP/1.1\r\nHost: {domain}\r\nConnection: "
                     f"{'close' if i == _TRACE_REQUESTS - 1 else 'keep-alive'}\r\n\r\n").encode()
                    for i in range(_TRACE_REQUESTS)))
            await writer.drain()
            while got <= _LARGE_BYTES:
                chunk = await reader.read(65536)
                if not chunk:
                    break
                got += len(chunk)
        finally:
            writer.close()

    try:
        await asyncio.wait_for(fetch(), timeout=timeout)
    except Exception:
        pass
    return got


async def _large_works(hosts: List[str], secure: bool, timeout: float, bypass: Any) -> bool:
    """The large check on up to _LARGE_HOSTS of `hosts` at once, with
    _LARGE_TIMEOUT_FACTOR x `timeout`: true if one of them passes it. A
    failure goes to the log."""
    hosts = hosts[:_LARGE_HOSTS]
    if not hosts:
        return False
    sizes = await asyncio.gather(*(_transfer_size(h, secure, timeout * _LARGE_TIMEOUT_FACTOR,
                                                  bypass) for h in hosts))
    if any(size > _LARGE_BYTES for size in sizes):
        return True
    way = ((f"TLS with {bypass.text}" if bypass else "TLS") if secure else "no TLS")
    log.info("AndMTProto: large check over %s failed: %s came back of over 24 KB needed",
             way, ", ".join(f"{size / 1024:.1f} KB from {h}" for h, size in zip(hosts, sizes)))
    return False


async def _probe_cf(domains: List[str], timeout: float,
                    strategy: Any) -> Dict[str, bool]:
    """Each way: the small check on `domains`, then the large one on the hosts
    that passed it. With the automatic strategy (`strategy` None), TLS with
    the bypass and plain TLS come from _checked_strategies."""
    hosts = [f"kws2.{d}" for d in domains]

    async def way(secure: bool, bypass: Any) -> Tuple[bool, bool]:
        oks = await asyncio.gather(*(_ws_works(h, secure, timeout, bypass) for h in hosts))
        answered = [h for h, ok in zip(hosts, oks) if ok]
        return bool(answered), await _large_works(answered, secure, timeout, bypass)

    if strategy is not None:
        (tls, tls_full), (bypass, bypass_full), (plain, plain_full) = await asyncio.gather(
            way(True, False), way(True, strategy), way(False, False))
    else:
        checked = asyncio.ensure_future(_checked_strategies(timeout))

        async def tls_way() -> Tuple[bool, bool]:
            options = (await checked)[0]
            answered = next((hs for option, hs in options if option is False), [])
            return bool(answered), await _large_works(answered, True, timeout, False)

        (tls, tls_full), (plain, plain_full), (_, full_strategy, choice) = await asyncio.gather(
            tls_way(), way(False, False), checked)
        _use_pick(choice)
        bypass, bypass_full = bool(choice), full_strategy is not None
    return {"tls": tls, "bypass": bypass, "plain": plain,
            "tls_full": tls_full, "bypass_full": bypass_full, "plain_full": plain_full}


def probe_cf(timeout: float = _PROBE_TIMEOUT_S) -> str:
    """Whether Telegram answers through the CF proxy over plain TLS, over TLS
    with the bypass strategy, and without TLS (port 80).

    Russian DPI cut TLS to the shared CF domains by their name (SNI) on
    16 Nov 2026 while plain HTTP to them kept working; ProxyService uses this
    to tell that apart from a dead network. Tries a few domains at once in its
    own event loop, so it can run next to the proxy thread. Returns JSON
    {"tls": bool, "bypass": bool, "plain": bool, "tls_full": bool,
    "bypass_full": bool, "plain_full": bool}, or {} when the CF proxy is off;
    the *_full ones say whether the large check (more than 24 KB each way on
    one connection, to Cloudflare itself) passed too.
    With the automatic strategy, "bypass" means some candidate got through,
    and the best one becomes the strategy in use.
    """
    if not proxy_config.fallback_cfproxy:
        return json.dumps({})
    domains = _probe_domains(_PROBE_DOMAINS)
    return json.dumps(asyncio.run(_probe_cf(domains, timeout, _probe_strategy)))


def note(message: str) -> None:
    """A line from the Android side (network changes, automatic restarts) in
    the proxy log, so a shared log shows what the app did and why."""
    log.info("AndMTProto: %s", message)


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
