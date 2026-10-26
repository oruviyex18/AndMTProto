"""
Android entry point for the upstream tg-ws-proxy core.

Mirrors the proxy lifecycle in utils/tray_common.py (apply_proxy_config,
_run_proxy_thread, stop_proxy) without the desktop-only dependencies.
Review this file whenever upstream changes ProxyConfig, _run() or the
tray lifecycle code.

Kotlin talks to this module only through start(), stop(), status(),
proxy_link() and new_secret(); config and status travel as JSON strings.
"""
from __future__ import annotations

import asyncio
import json
import logging
import logging.handlers
import os
import sys
import threading
from typing import Any, Dict, Optional, Tuple

from proxy import __version__
from proxy.config import coerce_domain_list, parse_dc_ip_list, proxy_config
from proxy.stats import stats
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

_lock = threading.Lock()
_proxy_thread: Optional[threading.Thread] = None
_async_stop: Optional[Tuple[asyncio.AbstractEventLoop, asyncio.Event]] = None
_last_error: Optional[str] = None
_log_handler: Optional[logging.Handler] = None


def _merged(cfg_json: str) -> Dict[str, Any]:
    cfg = dict(DEFAULT_CONFIG)
    cfg.update(json.loads(cfg_json) if cfg_json else {})
    return cfg


def _apply_config(cfg: Dict[str, Any]) -> None:
    """Port of tray_common.apply_proxy_config; raises ValueError on bad input."""
    secret = str(cfg["secret"]).strip()
    if len(secret) != 32:
        raise ValueError("Secret must be exactly 32 hex characters")
    bytes.fromhex(secret)

    pc = proxy_config
    pc.port = int(cfg["port"])
    pc.host = cfg["host"]
    pc.secret = secret
    pc.dc_redirects = parse_dc_ip_list(cfg["dc_ip"])
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
    pc.fake_tls_domain = str(cfg["fake_tls_domain"]).strip()


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


def _run_proxy_thread() -> None:
    global _async_stop, _last_error
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    stop_ev = asyncio.Event()
    _async_stop = (loop, stop_ev)
    try:
        loop.run_until_complete(_run(stop_event=stop_ev))
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
        _async_stop = None


def _describe_error(exc: BaseException) -> str:
    import errno
    if isinstance(exc, OSError):
        if exc.errno == errno.EADDRINUSE:
            return f"Port {proxy_config.port} is already in use"
        if exc.errno == errno.EACCES:
            return f"No permission to listen on port {proxy_config.port}"
        if exc.errno == errno.EADDRNOTAVAIL:
            return f"Address {proxy_config.host} is not available"
    return repr(exc)


def _is_running() -> bool:
    return _proxy_thread is not None and _proxy_thread.is_alive()


def start(cfg_json: str, log_dir: str) -> Optional[str]:
    """Start the proxy in a background thread. Returns an error or None."""
    global _proxy_thread, _last_error
    with _lock:
        if _is_running():
            return None
        cfg = _merged(cfg_json)
        _setup_logging(log_dir, cfg)
        try:
            _apply_config(cfg)
        except ValueError as exc:
            _last_error = str(exc)
            log.error("Bad config: %s", exc)
            return _last_error
        _last_error = None
        log.info("AndMTProto: core %s, Python %s",
                 __version__, sys.version.split()[0])
        log.info("Starting proxy on %s:%d ...",
                 proxy_config.host, proxy_config.port)
        _proxy_thread = threading.Thread(
            target=_run_proxy_thread, daemon=True, name="proxy")
        _proxy_thread.start()
        return None


def stop(timeout: float = 5.0) -> None:
    global _proxy_thread
    with _lock:
        stop_state = _async_stop
        if stop_state:
            loop, stop_ev = stop_state
            try:
                loop.call_soon_threadsafe(stop_ev.set)
            except RuntimeError:
                pass  # loop already closed
        if _proxy_thread:
            _proxy_thread.join(timeout=timeout)
            if _proxy_thread.is_alive():
                log.warning("Proxy thread did not stop within timeout")
        _proxy_thread = None
        log.info("Proxy stopped")


def status() -> str:
    return json.dumps({
        "running": _is_running(),
        "error": _last_error,
        "core_version": __version__,
        "active": stats.connections_active,
        "total": stats.connections_total,
        "bytes_up": stats.bytes_up,
        "bytes_down": stats.bytes_down,
        "summary": stats.summary(),
    })


def proxy_link(cfg_json: str) -> str:
    cfg = _merged(cfg_json)
    host = get_link_host(cfg["host"])
    secret = str(cfg["secret"]).strip()
    ftls = str(cfg["fake_tls_domain"]).strip()
    if ftls:
        secret = "ee" + secret + ftls.encode('ascii').hex()
    else:
        secret = "dd" + secret
    return f"tg://proxy?server={host}&port={cfg['port']}&secret={secret}"


def new_secret() -> str:
    return os.urandom(16).hex()


def default_config() -> str:
    return json.dumps(DEFAULT_CONFIG)
