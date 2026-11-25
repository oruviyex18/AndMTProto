"""
Name resolution for the Cloudflare hosts the core connects to (AndMTProto only).

The core opens the CF proxy and the user's own CF domains and Workers by name,
through the system's DNS. Some providers' DNS doesn't answer for them, or
answers with a private or unusable address. So for those hosts:

- a chosen Cloudflare IP (configure(cf_ip=...)) is used as is: any Cloudflare
  edge serves any Cloudflare site, and the name still goes in the TLS SNI and
  the Host header, so this helps where only some of Cloudflare's addresses are
  blocked;
- otherwise the system's DNS is asked first, and when it fails or gives only
  non-public addresses, DNS over HTTPS (Cloudflare's 1.1.1.1, then Google's
  8.8.8.8 and 1.0.0.1) answers instead; with always_doh, DNS over HTTPS is
  asked first.

It replaces socket.getaddrinfo for the app's Python, which asyncio calls for
each connection; every other host goes straight to the original.
"""
from __future__ import annotations

import concurrent.futures
import ipaddress
import json
import logging
import socket
import threading
import time
import urllib.request
from typing import Any, Callable, Dict, Iterable, List, Optional, Tuple

log = logging.getLogger('tg-mtproto-proxy')

# JSON DNS APIs, asked by IP so they need no DNS themselves.
DOH_SERVERS = (
    "https://1.1.1.1/dns-query",
    "https://8.8.8.8/resolve",
    "https://1.0.0.1/dns-query",
)
# All servers at once, within this; a server's own request gives up then too.
DOH_BUDGET_S = 2.5
DOH_TIMEOUT_S = DOH_BUDGET_S
# How long DoH counts as unreachable after no server answered.
DOH_RETRY_S = 60.0
_MIN_TTL_S, _MAX_TTL_S = 60, 600

_original_getaddrinfo = socket.getaddrinfo
_hosts: Callable[[], Iterable[str]] = lambda: ()
_always_doh = False
_cf_ip: Optional[str] = None
_cache: Dict[Tuple[str, int], Tuple[float, List[str]]] = {}
_lock = threading.Lock()
_doh_down_until = 0.0
# Lookups of one name share a query (_inflight), so a few names need few threads.
_pool = concurrent.futures.ThreadPoolExecutor(max_workers=12, thread_name_prefix="doh")
_inflight: Dict[Tuple[str, int], "concurrent.futures.Future[List[str]]"] = {}
# Names the system's DNS failed for or gave no public address for, until when:
# meanwhile DoH is asked first (a DNS that doesn't answer costs seconds each time).
_system_bad: Dict[str, float] = {}


def configure(hosts: Callable[[], Iterable[str]], always_doh: bool = False,
              cf_ip: Optional[str] = None) -> None:
    """Hosts under `hosts()` get this resolution; see the module's notes."""
    global _hosts, _always_doh, _cf_ip, _doh_down_until
    _hosts, _always_doh, _cf_ip = hosts, bool(always_doh), (cf_ip or None)
    with _lock:
        _cache.clear()
        _system_bad.clear()
        _doh_down_until = 0.0
    socket.getaddrinfo = _getaddrinfo


def check_ip(text: str) -> str:
    """'' if `text` is empty or an IP address, else `text` (for an error)."""
    text = text.strip()
    if not text:
        return ""
    try:
        ipaddress.ip_address(text)
    except ValueError:
        return text
    return ""


def _is_ip(name: str) -> bool:
    try:
        ipaddress.ip_address(name.strip("[]"))
    except ValueError:
        return False
    return True


def _ours(name: str) -> bool:
    host = name.lower().rstrip(".")
    return any(host == d or host.endswith("." + d)
               for d in (str(d).lower().rstrip(".") for d in _hosts()) if d)


def _usable(results: List[Any]) -> bool:
    """Some answer is a public address (not a provider's private or blank one)."""
    for result in results:
        try:
            if ipaddress.ip_address(result[4][0]).is_global:
                return True
        except (ValueError, IndexError, TypeError):
            continue
    return False


def _port_number(port: Any) -> int:
    """A port as the system's getaddrinfo takes it: a number, a numeric string,
    a service name ("https") or None."""
    if port in (None, "", b""):
        return 0
    text = port.decode("ascii", "ignore") if isinstance(port, bytes) else str(port)
    if text.isdigit():
        return int(text)
    try:
        return socket.getservbyname(text, "tcp")
    except OSError:
        raise socket.gaierror(socket.EAI_SERVICE, "unknown service") from None


def _results(ips: List[str], port: Any, family: int, type_: int, proto: int) -> List[Any]:
    port_number = _port_number(port)
    out = []
    for ip in ips:
        fam = socket.AF_INET6 if ":" in ip else socket.AF_INET
        if family not in (0, socket.AF_UNSPEC, fam):
            continue
        address = (ip, port_number, 0, 0) if fam == socket.AF_INET6 else (ip, port_number)
        out.append((fam, type_ or socket.SOCK_STREAM, proto or socket.IPPROTO_TCP, "", address))
    if not out:
        raise socket.gaierror(socket.EAI_NONAME, "no address of that family")
    return out


_ssl_context: Any = None


def _fetch_json(url: str) -> Dict[str, Any]:
    """One DNS-over-HTTPS query; tests replace it."""
    global _ssl_context
    if _ssl_context is None:  # once: it loads certifi's CA bundle
        from proxy.utils import create_ssl_context
        _ssl_context = create_ssl_context()
    request = urllib.request.Request(url, headers={"Accept": "application/dns-json"})
    with urllib.request.urlopen(request, timeout=DOH_TIMEOUT_S, context=_ssl_context) as reply:
        return json.loads(reply.read(65536).decode("utf-8"))


def resolve_doh(name: str, family: int = 0) -> List[str]:
    """Addresses for `name` from DNS over HTTPS (IPv6 only when asked for), cached for
    their TTL; [] when no server answers. The core's connect timeouts (3-10 s)
    include this lookup, so it takes at most DOH_BUDGET_S: the servers are asked
    at once, the first answer wins, and lookups of a name already being asked wait
    for that query instead of sending their own. When every server was asked and
    none answered, DoH counts as unreachable for DOH_RETRY_S, so later lookups go
    straight to the system's answer."""
    rtype = 28 if family == socket.AF_INET6 else 1
    key = (name.lower(), rtype)
    now = time.monotonic()
    with _lock:
        hit = _cache.get(key)
        if hit and hit[0] > now:
            return hit[1]
        if now < _doh_down_until:
            return []
        pending = _inflight.get(key)
        mine = pending is None
        if mine:
            pending = _inflight[key] = concurrent.futures.Future()
    if not mine:
        try:
            return pending.result(timeout=DOH_BUDGET_S)
        except Exception:
            return []
    try:
        ips = _query(name, key, rtype, now)
    except Exception:  # never leave the waiters hanging
        ips = []
    with _lock:
        _inflight.pop(key, None)
    pending.set_result(ips)
    return ips


def _query(name: str, key: Tuple[str, int], rtype: int, now: float) -> List[str]:
    global _doh_down_until
    query = f"?name={name}&type={'AAAA' if rtype == 28 else 'A'}"
    futures = {_pool.submit(_fetch_json, server + query): server for server in DOH_SERVERS}
    reached = False
    try:
        for future in concurrent.futures.as_completed(futures, timeout=DOH_BUDGET_S):
            server = futures[future].split("/")[2]
            try:
                answer = future.result()
            except Exception as exc:
                log.debug("AndMTProto: DNS over HTTPS via %s failed: %r", server, exc)
                continue
            reached = True
            records = [a for a in answer.get("Answer") or [] if a.get("type") == rtype]
            ips = [str(a.get("data", "")) for a in records if _is_ip(str(a.get("data", "")))]
            if not ips:
                continue
            ttl = min([int(a.get("TTL", _MIN_TTL_S)) for a in records] or [_MIN_TTL_S])
            with _lock:
                _cache[key] = (now + max(_MIN_TTL_S, min(ttl, _MAX_TTL_S)), ips)
            log.info("AndMTProto: a Cloudflare name resolved through DNS over HTTPS (%s)", server)
            return ips
    except concurrent.futures.TimeoutError:
        pass
    # A request still queued behind others' was never sent: that says nothing
    # about whether DoH works here.
    all_sent = all(f.done() or f.running() for f in futures)
    for f in futures:
        f.cancel()  # drops those still queued
    with _lock:
        if reached:  # the servers answer, just not for this name: ask again later
            _cache[key] = (now + DOH_RETRY_S, [])
        elif all_sent:
            _doh_down_until = now + DOH_RETRY_S
            log.info("AndMTProto: DNS over HTTPS doesn't answer here; using the system's DNS for %.0f s",
                     DOH_RETRY_S)
    return []


def _getaddrinfo(host: Any, port: Any, family: int = 0, type: int = 0,
                 proto: int = 0, flags: int = 0) -> List[Any]:
    name = host.decode("ascii", "ignore") if isinstance(host, bytes) else host
    if not isinstance(name, str) or not name or _is_ip(name) or not _ours(name):
        return _original_getaddrinfo(host, port, family, type, proto, flags)
    if _cf_ip:
        return _results([_cf_ip], port, family, type, proto)
    system_error: Optional[BaseException] = None
    system_results: Optional[List[Any]] = None
    key = name.lower().rstrip(".")
    with _lock:
        system_first = not _always_doh and _system_bad.get(key, 0.0) <= time.monotonic()
    if system_first:
        try:
            system_results = _original_getaddrinfo(host, port, family, type, proto, flags)
            if _usable(system_results):
                return system_results
            reason = "gave no public address"
        except OSError as exc:  # socket.gaierror
            system_error = exc
            reason = "failed"
        with _lock:
            _system_bad[key] = time.monotonic() + DOH_RETRY_S
        log.info("AndMTProto: the system's DNS %s for a Cloudflare name; asking DNS over HTTPS "
                 "first for %.0f s", reason, DOH_RETRY_S)
    ips = resolve_doh(name, family)
    if ips:
        return _results(ips, port, family, type, proto)
    # DNS over HTTPS didn't answer (blocked here, say): what the system's DNS said.
    if system_results is not None:
        return system_results
    if system_error is not None:
        raise system_error
    return _original_getaddrinfo(host, port, family, type, proto, flags)
