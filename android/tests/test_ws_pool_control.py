"""Tests for the screen-off WebSocket pool and on-demand WebSockets
(android/app/src/main/python/ws_pool_control.py)."""
import asyncio
import json
import os
import sys
import threading
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, 'android', 'app', 'src', 'main', 'python'))

import android_entry  # noqa: E402  (installs ws_pool_control's get())
import ws_pool_control  # noqa: E402
from core_helpers import _cfg, _listening, _wait  # noqa: E402


def _unpause_pool_after_test():
    android_entry.set_pool_paused(False)


def _record_pool_changes(monkeypatch):
    """Replaces _apply_pool_state; returns the _pool_paused values it ran with."""
    calls = []

    async def record():
        calls.append(ws_pool_control._pool_paused)
    monkeypatch.setattr(ws_pool_control, "_apply_pool_state", record)
    return calls


def test_core_started_with_the_screen_off_has_no_pool(tmp_path, monkeypatch):
    calls = _record_pool_changes(monkeypatch)
    android_entry.set_pool_paused(True)  # before start, as ProxyService does
    cfg = _cfg(pool_size=2)
    port = json.loads(cfg)["port"]
    try:
        assert android_entry.start(cfg, str(tmp_path)) is None
        assert _wait(lambda: _listening(port))
        assert android_entry.proxy_config.pool_size == 0  # no warmup, so no network
        android_entry.set_pool_paused(False)
        assert android_entry.proxy_config.pool_size == 2
        assert _wait(lambda: calls == [False])  # the running core fills its pools
        android_entry.set_pool_paused(False)  # no change: nothing more to do
        time.sleep(0.2)
        assert calls == [False]
    finally:
        android_entry.stop()
        _unpause_pool_after_test()


def test_pool_size_zero_is_never_paused_or_resumed(tmp_path, monkeypatch):
    calls = _record_pool_changes(monkeypatch)
    cfg = _cfg(pool_size=0)
    port = json.loads(cfg)["port"]
    try:
        assert android_entry.start(cfg, str(tmp_path)) is None
        assert _wait(lambda: _listening(port))
        android_entry.set_pool_paused(True)
        android_entry.set_pool_paused(False)
        time.sleep(0.2)
        assert calls == [] and android_entry.proxy_config.pool_size == 0
    finally:
        android_entry.stop()
        _unpause_pool_after_test()


def test_no_pool_change_reaches_a_stopping_core(monkeypatch):
    class _Loop:
        def __init__(self):
            self.callbacks = []

        def call_soon_threadsafe(self, callback):
            self.callbacks.append(callback)

    class _ClosedLoop:
        def call_soon_threadsafe(self, callback):
            raise RuntimeError("Event loop is closed")

    calls = _record_pool_changes(monkeypatch)
    monkeypatch.setattr(ws_pool_control, "_pool_size", 4)
    stopping = threading.Event()
    stopping.set()
    loop = _Loop()
    try:
        monkeypatch.setattr(android_entry, "_async_stop", (loop, stopping))
        android_entry.set_pool_paused(True)
        for callback in loop.callbacks:
            callback()  # runs in the loop: the core is stopping, so no task
        assert loop.callbacks and calls == []
        monkeypatch.setattr(android_entry, "_async_stop", (_ClosedLoop(), stopping))
        android_entry.set_pool_paused(False)  # no error, no coroutine left unawaited
    finally:
        monkeypatch.setattr(android_entry, "_async_stop", None)
        _unpause_pool_after_test()


class _PooledWs:
    def __init__(self):
        self.closed = False

    async def close(self):
        self.closed = True


def test_pause_closes_idle_websockets_and_keeps_the_backoff():
    from collections import deque
    from proxy.pool import _CfWorkerPool, _WsPool

    async def scenario(pool, key, item):
        refill = asyncio.ensure_future(asyncio.sleep(3600))
        pool._refilling[key] = refill
        pool._idle[key] = deque([item])
        backoff_until = time.monotonic() + 256
        pool._refill_after[key] = backoff_until
        await ws_pool_control._pause_pool(pool)
        assert refill.cancelled()
        assert not pool._idle[key]
        # Resuming then skips this key until its back-off ends: no burst at screen-on.
        assert pool._refill_after[key] == backoff_until

    ws = _PooledWs()
    asyncio.run(scenario(_WsPool(), (2, False, False), (ws, time.monotonic())))
    assert ws.closed
    worker_ws = _PooledWs()
    asyncio.run(scenario(_CfWorkerPool(), 2, (worker_ws, time.monotonic(), "w.example")))
    assert worker_ws.closed


def test_resume_warms_up_both_pools(monkeypatch):
    from proxy import pool
    warmed = []
    for name in ("ws_pool", "cf_worker_pool"):
        async def warmup(name=name):
            warmed.append(name)
        monkeypatch.setattr(getattr(pool, name), "warmup", warmup)
    monkeypatch.setattr(ws_pool_control, "_pool_paused", False)
    asyncio.run(ws_pool_control._apply_pool_state())
    assert warmed == ["ws_pool", "cf_worker_pool"]


def test_resume_right_after_a_pause_keeps_its_websockets(monkeypatch):
    """The screen comes on while the pause still waits for a refill to stop:
    the resume runs after it, so the pause can't close what the resume pooled."""
    from proxy import pool
    order = []

    async def slow_pause(p):
        order.append("pause starts")
        await asyncio.sleep(0.05)
        order.append("pause ends")

    async def warmup():
        order.append("warmup")
    monkeypatch.setattr(ws_pool_control, "_pause_pool", slow_pause)
    monkeypatch.setattr(pool.ws_pool, "warmup", warmup)
    monkeypatch.setattr(pool.cf_worker_pool, "warmup", warmup)

    async def scenario():
        monkeypatch.setattr(ws_pool_control, "_pool_paused", True)
        pause = asyncio.ensure_future(ws_pool_control._apply_pool_state())
        await asyncio.sleep(0.01)  # the pause is under way
        monkeypatch.setattr(ws_pool_control, "_pool_paused", False)
        resume = asyncio.ensure_future(ws_pool_control._apply_pool_state())
        await asyncio.gather(pause, resume)
    asyncio.run(scenario())
    assert order[-2:] == ["warmup", "warmup"]
    assert order.index("pause ends") < order.index("warmup")


def _fresh_routes(monkeypatch, paused=True, pool_size=4):
    """A clean on-demand state with DC2 routed, the pool paused or not."""
    from proxy import pool
    monkeypatch.setattr(ws_pool_control, "_route_ok_at", {})
    monkeypatch.setattr(ws_pool_control, "_on_demand_failures", {})
    monkeypatch.setattr(ws_pool_control, "_on_demand_failed_at", {})
    monkeypatch.setattr(ws_pool_control, "_on_demand_after", {})
    monkeypatch.setattr(ws_pool_control, "_next_fronted", None)
    monkeypatch.setattr(ws_pool_control, "_route_first", {})
    monkeypatch.setattr(ws_pool_control, "_next_fronted_at", float("-inf"))
    monkeypatch.setattr(ws_pool_control, "_ON_DEMAND_FIRST_TRY_S", 0.1)
    monkeypatch.setattr(ws_pool_control, "_ON_DEMAND_TRY_S", 0.05)
    monkeypatch.setattr(ws_pool_control, "_pool_paused", paused)
    monkeypatch.setattr(ws_pool_control, "_pool_size", pool_size)
    monkeypatch.setattr(android_entry.proxy_config, "pool_size", 0 if paused else pool_size)
    monkeypatch.setattr(android_entry.proxy_config, "dc_redirects", {2: "149.154.167.220"})
    monkeypatch.setattr(pool.ws_pool, "_refill_after", {})
    monkeypatch.setattr(pool.ws_pool, "_refill_failures", {})
    monkeypatch.setattr(pool.ws_pool, "try_fronting_first", False)
    return pool.ws_pool


def test_paused_pool_opens_websockets_on_demand(monkeypatch):
    """With the pool paused a connection still gets a direct WebSocket; without
    this, upstream's get() returns None for pool size 0 and every connection
    takes the CF/TCP fallback."""
    ws_pool = _fresh_routes(monkeypatch)
    opened = _PooledWs()
    tries = []

    async def open_ws(target_ip, domain, path, fronted):
        tries.append((target_ip, domain, path, fronted))
        return opened
    monkeypatch.setattr(ws_pool_control, "_open_ws", open_ws)
    assert asyncio.run(ws_pool.get(2, False)) is opened
    assert tries == [("149.154.167.220", "kws2.web.telegram.org", "/apiws", False)]
    assert asyncio.run(ws_pool.get(4, False)) is None  # no route to DC4 configured
    assert len(tries) == 1


def test_each_fronting_mode_gets_its_own_try(monkeypatch):
    """On MTS Wi-Fi only fronting gets through, and after a core start upstream
    tries the other mode first: that try must not use up the whole wait."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(ws_pool_control, "_ON_DEMAND_TRY_S", 0.05)
    opened = _PooledWs()
    tries = []

    async def open_ws(target_ip, domain, path, fronted):
        tries.append(fronted)
        if not fronted:
            await asyncio.sleep(3600)  # unfronted never answers here
        return opened
    monkeypatch.setattr(ws_pool_control, "_open_ws", open_ws)
    assert asyncio.run(ws_pool.get(2, False)) is opened
    assert tries == [False, True]
    # Fronting worked, so it goes first next time, even after a core start.
    assert ws_pool_control._next_fronted is True
    ws_pool_control.reset_routes()
    assert asyncio.run(ws_pool.get(2, False)) is opened
    assert tries == [False, True, True]


def test_blocked_route_backs_off_on_its_own(monkeypatch):
    """Every try fails: the next connection goes straight to the fallback. The
    pool's back-off stays untouched, so a screen-on refill isn't held back by
    what happened with the screen off."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(ws_pool_control, "_ON_DEMAND_TRY_S", 0.02)
    tries = []

    async def blocked(target_ip, domain, path, fronted):
        tries.append(domain)
        await asyncio.sleep(3600)
    monkeypatch.setattr(ws_pool_control, "_open_ws", blocked)
    assert asyncio.run(ws_pool.get(2, True)) is None
    assert len(tries) == 4  # both media domains, both modes
    assert asyncio.run(ws_pool.get(2, True)) is None
    assert len(tries) == 4  # backing off: no tries
    assert ws_pool_control._on_demand_failures == {(2, True, False): 1}
    assert ws_pool._refill_after == {} and ws_pool._refill_failures == {}


def test_on_demand_respects_the_pools_back_off(monkeypatch):
    ws_pool = _fresh_routes(monkeypatch)

    async def open_ws(*args):
        raise AssertionError("the pool's refills keep failing on this route")
    monkeypatch.setattr(ws_pool_control, "_open_ws", open_ws)
    ws_pool._refill_after[(2, False, False)] = time.monotonic() + 60
    assert asyncio.run(ws_pool.get(2, False)) is None


def test_late_failure_doesnt_back_off_a_route_that_worked_meanwhile(monkeypatch):
    """Several connections to one DC at once: a slow one that fails after a
    fast one got through mustn't send the next connections to the fallback."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(ws_pool_control, "_ON_DEMAND_TRY_S", 0.05)
    opened = _PooledWs()
    slow_task = []

    async def open_ws(target_ip, domain, path, fronted):
        if asyncio.current_task() in slow_task:  # every try of the slow connection hangs
            await asyncio.sleep(3600)
        return opened

    async def scenario():
        slow = asyncio.ensure_future(ws_pool.get(2, False))
        slow_task.append(slow)
        await asyncio.sleep(0.01)
        fast = await ws_pool.get(2, False)
        return fast, await slow
    monkeypatch.setattr(ws_pool_control, "_open_ws", open_ws)
    fast, slow = asyncio.run(scenario())
    assert fast is opened and slow is None
    assert ws_pool_control._on_demand_after == {}
    assert asyncio.run(ws_pool.get(2, False)) is opened


def test_empty_pool_opens_on_demand_once_the_route_worked(monkeypatch):
    """Screen on, pool not refilled yet (or emptied by a burst): a route that
    worked on this core gets a WebSocket on demand instead of the fallback; an
    unknown one keeps upstream's behaviour."""
    from proxy import pool
    ws_pool = _fresh_routes(monkeypatch, paused=False)
    opened = _PooledWs()
    tries = []

    async def open_ws(target_ip, domain, path, fronted):
        tries.append(fronted)
        return opened

    async def no_refill(*args):
        return None  # upstream's own refill, kept off the network
    monkeypatch.setattr(ws_pool_control, "_open_ws", open_ws)
    monkeypatch.setattr(pool.ws_pool, "_connect_one", no_refill)
    assert asyncio.run(ws_pool.get(2, False)) is None
    assert tries == []
    ws_pool_control._route_ok_at[(2, False, False)] = time.monotonic()
    assert asyncio.run(ws_pool.get(2, False)) is opened
    assert tries == [False]


def test_failures_in_one_burst_back_off_once(monkeypatch):
    """Telegram opens several connections at once; when they all fail together
    (the network isn't up yet) the route backs off as after one failure, not
    one doubling per connection."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(ws_pool_control, "_ON_DEMAND_TRY_S", 0.02)
    # A back-off that ends before the burst's last connections give up.
    monkeypatch.setattr(ws_pool, "REFILL_BACKOFF_INITIAL", 0.001)

    async def blocked(target_ip, domain, path, fronted):
        await asyncio.sleep(3600)

    async def one(delay):
        await asyncio.sleep(delay)
        return await ws_pool.get(2, False)

    async def burst():
        return await asyncio.gather(*(one(i * 0.005) for i in range(8)))
    monkeypatch.setattr(ws_pool_control, "_open_ws", blocked)
    assert asyncio.run(burst()) == [None] * 8
    assert ws_pool_control._on_demand_failures == {(2, False, False): 1}


def test_pool_hit_clears_the_on_demand_back_off(monkeypatch):
    """A back-off left from the screen-off time mustn't keep an emptied pool's
    connections on the fallback once pool hits show the route works."""
    from collections import deque
    from proxy import pool
    ws_pool = _fresh_routes(monkeypatch, paused=False)
    key = (2, False, False)
    pooled = _PooledWs()
    for name in ("_refilling", "_rotating"):
        monkeypatch.setattr(pool.ws_pool, name, {})
    monkeypatch.setattr(pool.ws_pool, "_idle", {key: deque([(pooled, time.monotonic())])})
    monkeypatch.setattr(pool.ws_pool, "_is_stale", lambda *args: False)

    async def no_refill(*args):
        return None
    monkeypatch.setattr(pool.ws_pool, "_connect_one", no_refill)
    ws_pool_control._on_demand_failures[key] = 12
    ws_pool_control._on_demand_after[key] = time.monotonic() + 3600
    assert asyncio.run(ws_pool.get(2, False)) is pooled
    assert ws_pool_control._on_demand_failures == {} and ws_pool_control._on_demand_after == {}
    assert key in ws_pool_control._route_ok_at


def test_the_mode_that_worked_gets_a_longer_try(monkeypatch):
    """Through a slow VPN a working route takes several seconds: the mode
    that last worked gets as long as upstream's pool would give it."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(ws_pool_control, "_ON_DEMAND_FIRST_TRY_S", 0.5)
    monkeypatch.setattr(ws_pool_control, "_next_fronted", True)
    opened = _PooledWs()
    tries = []

    async def slow(target_ip, domain, path, fronted):
        tries.append(fronted)
        await asyncio.sleep(0.15)  # longer than the other mode's 0.05 s try
        return opened
    monkeypatch.setattr(ws_pool_control, "_open_ws", slow)
    assert asyncio.run(ws_pool.get(2, False)) is opened
    assert tries == [True]


def test_an_unproven_mode_gets_the_short_try(monkeypatch):
    """Before any mode has worked, upstream's default (unfronted after a core
    start) is only a guess: on MTS Wi-Fi it hangs, and fronting must not wait
    the long try for it."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(ws_pool_control, "_ON_DEMAND_FIRST_TRY_S", 3600)
    opened = _PooledWs()
    tries = []

    async def open_ws(target_ip, domain, path, fronted):
        tries.append(fronted)
        if not fronted:
            await asyncio.sleep(3600)
        return opened
    monkeypatch.setattr(ws_pool_control, "_open_ws", open_ws)
    assert asyncio.run(asyncio.wait_for(ws_pool.get(2, False), 5)) is opened
    assert tries == [False, True]


def test_unproven_modes_take_turns_at_the_long_try(monkeypatch):
    """After a boot with the screen off nothing has worked yet; through a slow
    VPN only the long try gets through, so each mode must get it in turn, or
    no mode ever works and the long try is never reached."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(ws_pool_control, "_ON_DEMAND_FIRST_TRY_S", 0.5)
    monkeypatch.setattr(ws_pool, "REFILL_BACKOFF_INITIAL", 0.001)
    opened = _PooledWs()
    tries = []

    async def slow_unfronted(target_ip, domain, path, fronted):
        tries.append(fronted)
        await asyncio.sleep(0.15 if not fronted else 3600)  # fronting blocked
        return opened
    monkeypatch.setattr(ws_pool_control, "_open_ws", slow_unfronted)

    async def scenario():
        first = await ws_pool.get(2, False)  # unfronted: short try, fronted: long
        await asyncio.sleep(0.01)  # the back-off ends
        return first, await ws_pool.get(2, False)  # unfronted long: gets through
    first, second = asyncio.run(scenario())
    assert first is None and second is opened
    assert tries == [False, True, False]
    assert ws_pool_control._next_fronted is False


def test_a_proven_mode_hands_over_the_long_try_on_a_new_network(monkeypatch):
    """Fronting worked on the old network; on the new one it hangs and the
    other mode needs the long try (a slow VPN). After a failed attempt the
    other mode gets it, or nothing would ever open until the screen came on."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(ws_pool_control, "_next_fronted", True)
    monkeypatch.setattr(ws_pool_control, "_ON_DEMAND_FIRST_TRY_S", 0.5)
    monkeypatch.setattr(ws_pool, "REFILL_BACKOFF_INITIAL", 0.001)
    opened = _PooledWs()
    tries = []

    async def slow_unfronted(target_ip, domain, path, fronted):
        tries.append(fronted)
        await asyncio.sleep(0.15 if not fronted else 3600)
        return opened
    monkeypatch.setattr(ws_pool_control, "_open_ws", slow_unfronted)

    async def scenario():
        first = await ws_pool.get(2, False)  # fronted long, unfronted short
        await asyncio.sleep(0.01)
        return first, await ws_pool.get(2, False)  # unfronted long
    first, second = asyncio.run(scenario())
    assert first is None and second is opened
    assert tries == [True, False, False]
    assert ws_pool_control._next_fronted is False
    assert ws_pool_control._route_first == {}  # the success teaches every route


def test_a_route_blocked_in_both_modes_keeps_its_swaps_to_itself(monkeypatch):
    """DC2 media fails whatever the mode; that mustn't make DC2's main route,
    where fronting works, start with the other mode."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(ws_pool_control, "_next_fronted", True)
    opened = _PooledWs()
    tries = []

    media_blocked = True

    async def open_ws(target_ip, domain, path, fronted):
        tries.append((domain, fronted))
        if media_blocked:
            await asyncio.sleep(3600)
        return opened
    monkeypatch.setattr(ws_pool_control, "_open_ws", open_ws)
    assert asyncio.run(ws_pool.get(2, True)) is None  # media: every try hangs
    assert ws_pool_control._next_fronted is True
    assert ws_pool_control._route_first == {(2, True, False): False}
    tries.clear()
    media_blocked = False
    assert asyncio.run(ws_pool.get(2, False)) is opened
    assert tries == [("kws2.web.telegram.org", True)]


def test_a_newer_success_elsewhere_beats_a_routes_old_swap(monkeypatch):
    """DC4 failed while the network came up, then DC2 opened with fronting:
    DC4's next attempt goes by DC2's newer success, not by its own swap."""
    ws_pool = _fresh_routes(monkeypatch)
    monkeypatch.setattr(android_entry.proxy_config, "dc_redirects",
                        {2: "149.154.167.220", 4: "149.154.167.91"})
    monkeypatch.setattr(ws_pool_control, "_next_fronted", True)
    opened = _PooledWs()
    tries = []
    network_up = False

    async def open_ws(target_ip, domain, path, fronted):
        tries.append((domain, fronted))
        if not network_up:
            await asyncio.sleep(3600)
        return opened
    monkeypatch.setattr(ws_pool_control, "_open_ws", open_ws)
    assert asyncio.run(ws_pool.get(4, False)) is None
    assert ws_pool_control._route_first == {(4, False, False): False}
    network_up = True
    assert asyncio.run(ws_pool.get(2, False)) is opened
    ws_pool_control._on_demand_after.clear()  # DC4's back-off has ended
    tries.clear()
    assert asyncio.run(ws_pool.get(4, False)) is opened
    assert tries == [("kws4.web.telegram.org", True)]


def test_pool_hit_teaches_the_mode_to_on_demand(monkeypatch):
    """Fronting worked on the old network, the pool connects without it on
    the new one: once the pool has served a connection, on-demand WebSockets
    try the pool's mode first instead of losing a try on the old one."""
    from collections import deque
    from proxy import pool
    ws_pool = _fresh_routes(monkeypatch, paused=False)
    monkeypatch.setattr(ws_pool_control, "_next_fronted", True)
    key = (2, False, False)
    for name in ("_refilling", "_rotating"):
        monkeypatch.setattr(pool.ws_pool, name, {})
    monkeypatch.setattr(pool.ws_pool, "_idle", {key: deque([(_PooledWs(), time.monotonic())])})
    monkeypatch.setattr(pool.ws_pool, "_is_stale", lambda *args: False)
    opened = _PooledWs()
    tries = []

    async def no_refill(*args):
        return None

    async def open_ws(target_ip, domain, path, fronted):
        tries.append(fronted)
        return opened
    monkeypatch.setattr(pool.ws_pool, "_connect_one", no_refill)
    monkeypatch.setattr(ws_pool_control, "_open_ws", open_ws)
    ws_pool.try_fronting_first = False  # what the pool's refills learned

    async def scenario():
        hit = await ws_pool.get(2, False)
        return hit, await ws_pool.get(2, False)  # the pool is empty now
    hit, on_demand = asyncio.run(scenario())
    assert hit is not opened and on_demand is opened
    assert tries == [False] and ws_pool_control._next_fronted is False


def test_pool_size_zero_set_by_the_user_still_skips_websockets(monkeypatch):
    ws_pool = _fresh_routes(monkeypatch, pool_size=0)

    async def open_ws(*args):
        raise AssertionError("pool size 0 means no direct WebSockets")
    monkeypatch.setattr(ws_pool_control, "_open_ws", open_ws)
    assert asyncio.run(ws_pool.get(2, False)) is None


def test_pool_change_tasks_are_kept_until_done(monkeypatch):
    """The loop keeps only weak references to tasks; set_pool_paused's must not
    be collected half-way."""
    calls = _record_pool_changes(monkeypatch)
    monkeypatch.setattr(ws_pool_control, "_pool_size", 4)

    async def scenario():
        loop = asyncio.get_running_loop()
        monkeypatch.setattr(android_entry, "_async_stop", (loop, asyncio.Event()))
        android_entry.set_pool_paused(True)
        await asyncio.sleep(0)  # the callback runs and creates the task
        assert len(ws_pool_control._pool_tasks) == 1
        await asyncio.sleep(0.01)
        assert not ws_pool_control._pool_tasks and calls == [True]
    try:
        asyncio.run(scenario())
    finally:
        monkeypatch.setattr(android_entry, "_async_stop", None)
        _unpause_pool_after_test()
