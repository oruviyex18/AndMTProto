"""
The core's WebSocket pools while the screen is off (AndMTProto only).

The core keeps pool_size WebSockets to each of DC2, DC4 and their media servers
open ahead of time (upstream pool.py), from the start on, whether or not Telegram
uses the proxy, and replaces them every 2 minutes: with the default 4, 16 TLS
connections, 8 new ones a minute, which keep the radio busy all day. Where the
network blocks them, each refill fails (432 failed connections in 11 minutes on
Beeline Wi-Fi, 22 Nov 2026, with Telegram closed). The pool is also the core's
only way to a direct WebSocket: when ws_pool.get() has none, the connection goes
to the CF Worker, CF proxy or TCP fallback. So ProxyService pauses it with the
screen off (set_paused): nothing is opened ahead of time, and a new connection
opens its WebSocket itself (_ws_on_demand, through the get() that install()
puts on ws_pool).

This module reaches into pool.py's internals (_refilling, _idle,
_refill_after, try_fronting_first, _quiet_close): review _pause_pool,
_ws_on_demand and install() whenever upstream changes pool.py. Upstream code
is untouched.
"""
from __future__ import annotations

import asyncio
import logging
import time
import weakref
from typing import Any, Dict, Optional, Set, Tuple

from proxy.config import proxy_config
from proxy.pool import ws_pool
from proxy.raw_websocket import RawWebSocket
from proxy.stats import stats
from proxy.utils import WS_PATH, WS_PATH_TEST, ws_domains

log = logging.getLogger('tg-mtproto-proxy')

# Upstream's default; android_entry.DEFAULT_CONFIG uses it too.
DEFAULT_POOL_SIZE = 4
# The size the user set (configure()), and whether ProxyService paused the pool.
_pool_size = DEFAULT_POOL_SIZE
_pool_paused = False
# The tries (a domain, with or without fronting) of an on-demand WebSocket, and
# all of them for one connection. A working route opens in well under a second,
# 3-6 s through a slow VPN: the mode that last worked gets long enough for that,
# a mode nothing has shown to work a shorter try, and the budget keeps Telegram
# from waiting much longer than before for the fallback when both fail. Each
# mode gets its own try: on MTS Wi-Fi only fronting gets through, and upstream
# tries the other mode first after every core start (pool.reset() clears
# try_fronting_first) and waits 8 s for it.
_ON_DEMAND_FIRST_TRY_S = 6.0
_ON_DEMAND_TRY_S = 4.0
_ON_DEMAND_BUDGET_S = 10.0
# The SNI upstream's pool fronts with (pool._connect_one).
_FRONTING_SNI = "sprinthost.ru"
# Per route (dc, is_media, is_test_dc), reset at each core start: when a direct
# WebSocket last opened (a pool hit or on demand), and the back-off after
# on-demand attempts where every try failed (how many failures, when the last
# was counted, until when). The pool's own back-off stays its own, so failures
# with the screen off don't hold its refills back afterwards.
_route_ok_at: Dict[Tuple[int, bool, bool], float] = {}
_on_demand_failures: Dict[Tuple[int, bool, bool], int] = {}
_on_demand_failed_at: Dict[Tuple[int, bool, bool], float] = {}
_on_demand_after: Dict[Tuple[int, bool, bool], float] = {}
# Whether on-demand attempts try fronting first, with the long try; kept across
# core starts unlike the pool's mode. A WebSocket that opens sets it to its mode
# (on demand, or the pool's mode on a pool hit, so what the pool learned on a new
# network wins over what worked on the old one). None until anything has worked
# since the app started (e.g. on boot): upstream's default is then only a guess
# and gets the short try.
_next_fronted: Optional[bool] = None
# When an on-demand WebSocket last opened, or a pool hit changed _next_fronted.
_next_fronted_at = float("-inf")
# Per route, reset at each core start: after an attempt where every try failed,
# the mode that had the short try, which gets the long one next unless a
# WebSocket opened since (_next_fronted_at). So after a network change each mode
# gets it in turn, as a slow VPN needs, and a route blocked in both modes
# doesn't change what the other routes try first.
_route_first: Dict[Tuple[int, bool, bool], bool] = {}
# One lock per event loop: pause and resume changes apply one at a time.
_pool_locks: "weakref.WeakKeyDictionary[asyncio.AbstractEventLoop, asyncio.Lock]" = (
    weakref.WeakKeyDictionary())
# Pause and resume tasks: the loop keeps only weak references to tasks.
_pool_tasks: Set["asyncio.Task[None]"] = set()


def reset_routes() -> None:
    """A new core usually means a new network: what worked before says nothing."""
    _route_ok_at.clear()
    _on_demand_failures.clear()
    _on_demand_failed_at.clear()
    _on_demand_after.clear()
    _route_first.clear()


def configure(pool_size: int) -> None:
    """The pool size the user set; the core gets 0 while the pool is paused."""
    global _pool_size
    _pool_size = max(0, pool_size)
    proxy_config.pool_size = 0 if _pool_paused else _pool_size


def set_paused(paused: bool) -> bool:
    """Pauses the core's WebSocket pools (screen off) or fills them again;
    False if nothing changed. android_entry.set_pool_paused calls it under its
    lock and then apply_to_core().

    Paused, the pools open nothing ahead of time; a new connection opens its
    own direct WebSocket (_ws_on_demand) and CF Worker connection (upstream
    already does that one on demand). Pausing stops the refills under way and
    closes the idle pooled WebSockets, and keeps each pool's back-off after
    failed refills, so a network that blocks them isn't tried again at every
    screen-on. Also works before the core starts: it then starts paused.
    """
    global _pool_paused
    if paused == _pool_paused:
        return False
    _pool_paused = paused
    proxy_config.pool_size = 0 if paused else _pool_size
    return True


def apply_to_core(core: Optional[Tuple[asyncio.AbstractEventLoop, Any]],
                  paused: bool) -> None:
    """Pauses or fills the running core's pools after set_paused(paused)
    changed the state; the task applies whatever state is current when it
    runs. `core` is the core's loop and stop event, or None when none runs."""
    if core is None or not _pool_size:
        return
    log.info("AndMTProto: WebSocket pool %s",
             "paused (screen off)" if paused else "resumed")
    loop, stop_ev = core

    def schedule() -> None:
        # A core that is stopping gets no new task; the next one starts with
        # the current state (configure()).
        if not stop_ev.is_set():
            task = asyncio.ensure_future(_apply_pool_state())
            _pool_tasks.add(task)
            task.add_done_callback(_pool_tasks.discard)
    try:
        loop.call_soon_threadsafe(schedule)
    except RuntimeError:
        pass  # loop already closed


async def _apply_pool_state() -> None:
    """Pauses or fills the pools as _pool_paused says when it runs: changes
    queued behind each other apply one at a time, and the last one wins, so a
    resume never has its fresh WebSockets closed by an earlier pause."""
    from proxy.pool import cf_worker_pool
    lock = _pool_locks.setdefault(asyncio.get_running_loop(), asyncio.Lock())
    async with lock:
        paused = _pool_paused
        for pool in (ws_pool, cf_worker_pool):
            try:
                if paused:
                    await _pause_pool(pool)
                else:
                    await pool.warmup()  # it skips keys still backing off
            except Exception as exc:  # pool internals changed upstream
                log.warning("Cannot %s the WebSocket pool: %r",
                            "pause" if paused else "resume", exc)


async def _pause_pool(pool: Any) -> None:
    """Stops `pool`'s refills and closes its idle WebSockets. Its rotation tasks
    end by themselves once they see pool_size 0; pool.reset() would also forget
    the back-off after failed refills."""
    refills = list(pool._refilling.values())
    for task in refills:
        task.cancel()  # its own finally closes what it opened and hadn't pooled
    await asyncio.gather(*refills, return_exceptions=True)
    closing = [pool._quiet_close(item[0])
               for bucket in pool._idle.values() for item in bucket]
    for bucket in pool._idle.values():
        bucket.clear()
    await asyncio.gather(*closing, return_exceptions=True)


async def _open_ws(target_ip: str, domain: str, path: str, fronted: bool) -> RawWebSocket:
    """One WebSocket the way upstream's pool opens it (pool._connect_one)."""
    return await RawWebSocket.connect(  # _ws_on_demand cuts each try shorter
        target_ip, domain, timeout=_ON_DEMAND_FIRST_TRY_S, path=path,
        sni=_FRONTING_SNI if fronted else None)


async def _ws_on_demand(pool: Any, dc: int, is_media: bool,
                        is_test_dc: bool) -> Optional[RawWebSocket]:
    """A direct WebSocket for one connection, or None for the fallback.

    Tries each domain with the route's or _next_fronted's mode first, for up
    to _ON_DEMAND_FIRST_TRY_S, then the other mode for up to _ON_DEMAND_TRY_S,
    all of them within _ON_DEMAND_BUDGET_S; before anything has worked,
    upstream's guess gets the short try and the other mode the long one.
    When every try fails the route backs off, so on a network that blocks it
    the next connections go straight to the fallback; not when another
    connection opened one on it meanwhile. Skipped while either back-off runs."""
    global _next_fronted, _next_fronted_at
    target_ip = proxy_config.dc_redirects.get(dc)
    if not target_ip:
        return None
    key = (dc, is_media, is_test_dc)
    started = time.monotonic()
    if started < max(pool._refill_after.get(key, 0), _on_demand_after.get(key, 0)):
        return None
    path = WS_PATH_TEST if is_test_dc else WS_PATH
    first = _next_fronted
    if key in _route_first and _on_demand_failed_at.get(key, float("-inf")) > _next_fronted_at:
        first = _route_first[key]
    long_first = first is not None
    if first is None:  # a guess: upstream's default after pool.reset()
        first = pool.try_fronting_first
    tries_s = ((_ON_DEMAND_FIRST_TRY_S, _ON_DEMAND_TRY_S) if long_first
               else (_ON_DEMAND_TRY_S, _ON_DEMAND_FIRST_TRY_S))
    deadline = started + _ON_DEMAND_BUDGET_S
    for domain in ws_domains(dc, is_media):
        for fronted, try_s in ((first, tries_s[0]), (not first, tries_s[1])):
            left = deadline - time.monotonic()
            if left <= 0:
                break
            try:
                ws = await asyncio.wait_for(_open_ws(target_ip, domain, path, fronted),
                                            min(try_s, left))
            except Exception as exc:
                stats.ws_errors += 1
                log.debug("On-demand WebSocket %s%s via %s (fronting=%s): %r",
                          domain, path, target_ip, fronted, exc)
                continue
            if fronted:
                stats.connections_fronting += 1
            _next_fronted = pool.try_fronting_first = fronted
            _next_fronted_at = time.monotonic()
            _route_worked(key)
            # Upstream then logs "-> WS pool hit" for it, as for any WebSocket get() returns.
            log.info("DC%d%s WebSocket opened on demand (%s; the core logs it"
                     " as a WS pool hit)",
                     dc, " media" if is_media else "",
                     "pool paused" if _pool_paused else "pool empty")
            return ws
    now = time.monotonic()
    # Nobody got through meanwhile, and no other connection failing alongside
    # this one has counted a failure since it started: a burst of connections
    # that fail together counts once, as one failed refill does upstream.
    if (_route_ok_at.get(key, float("-inf")) < started
            and _on_demand_failed_at.get(key, float("-inf")) < started):
        _route_ok_at.pop(key, None)
        failures = _on_demand_failures.get(key, 0) + 1
        _on_demand_failures[key] = failures
        _on_demand_failed_at[key] = now
        # The mode that had the short try gets the long one next on this route.
        _route_first[key] = (not first) if long_first else first
        _on_demand_after[key] = now + min(
            pool.REFILL_BACKOFF_INITIAL * (2 ** min(failures - 1, 12)),
            pool.REFILL_BACKOFF_MAX)
    return None


def _route_worked(key: Tuple[int, bool, bool]) -> None:
    """A direct WebSocket just opened on this route (a pool hit or on demand):
    whatever failed before, the next on-demand try needn't wait."""
    _route_ok_at[key] = time.monotonic()
    _on_demand_failures.pop(key, None)
    _on_demand_failed_at.pop(key, None)
    _on_demand_after.pop(key, None)
    _route_first.pop(key, None)  # _next_fronted now says what works


def install() -> None:
    """Makes ws_pool.get() open a WebSocket itself when it has none to give:
    always while the pool is paused, and with the screen on once the route has
    worked on this core (a burst emptied the pool, or the screen just came on
    and the refill isn't done). Upstream returns None then, which sends the
    connection to the fallback. A pool size of 0 that the user set still means
    no direct WebSockets."""
    if getattr(ws_pool, "_andmtproto_on_demand", False):
        return
    upstream_get = ws_pool.get

    async def get(dc: int, is_media: bool, *, is_test_dc: bool = False
                  ) -> Optional[RawWebSocket]:
        global _next_fronted, _next_fronted_at
        if not _pool_size:
            return await upstream_get(dc, is_media, is_test_dc=is_test_dc)
        if _pool_paused:
            return await _ws_on_demand(ws_pool, dc, is_media, is_test_dc)
        ws = await upstream_get(dc, is_media, is_test_dc=is_test_dc)
        key = (dc, is_media, is_test_dc)
        if ws is not None:
            if _next_fronted is not ws_pool.try_fronting_first:
                # The mode its refills last used; repeated hits don't count as news.
                _next_fronted = ws_pool.try_fronting_first
                _next_fronted_at = time.monotonic()
            _route_worked(key)
            return ws
        if key in _route_ok_at:
            return await _ws_on_demand(ws_pool, dc, is_media, is_test_dc)
        return None

    ws_pool.get = get
    ws_pool._andmtproto_on_demand = True
