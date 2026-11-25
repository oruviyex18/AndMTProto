"""Tests for Cloudflare name resolution (android/app/src/main/python/cf_dns.py)."""
import asyncio
import os
import socket
import sys

import pytest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, 'android', 'app', 'src', 'main', 'python'))

import cf_dns  # noqa: E402

CF = "kws2.example-cf.com"


@pytest.fixture
def dns(monkeypatch):
    """The system's DNS and the DNS-over-HTTPS servers, as the test sets them."""
    state = {"system": {}, "doh": {}, "asked": []}

    def system(host, port, family=0, type=0, proto=0, flags=0):
        state["asked"].append(("system", host))
        answer = state["system"].get(host)
        if answer is None:
            raise socket.gaierror(socket.EAI_NONAME, "not found")
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, port)) for ip in answer]

    def fetch(url):
        server = url.split("/")[2]
        state["asked"].append(("doh", server))
        answer = state["doh"].get(server)
        if answer is None:
            raise OSError("blocked")
        return {"Status": 0, "Answer": [{"type": 1, "TTL": 300, "data": ip} for ip in answer]}

    monkeypatch.setattr(cf_dns, "_original_getaddrinfo", system)
    monkeypatch.setattr(cf_dns, "_fetch_json", fetch)
    original = socket.getaddrinfo
    cf_dns.configure(lambda: ["example-cf.com"])
    yield state
    socket.getaddrinfo = original


def _ips(results):
    return [r[4][0] for r in results]


def test_other_hosts_and_ip_literals_go_to_the_system(dns):
    dns["system"]["telegram.org"] = ["149.154.167.99"]
    assert _ips(socket.getaddrinfo("telegram.org", 443)) == ["149.154.167.99"]
    assert dns["asked"] == [("system", "telegram.org")]


def test_the_system_answer_is_used_when_it_is_public(dns):
    dns["system"][CF] = ["104.21.1.1"]
    assert _ips(socket.getaddrinfo(CF, 443)) == ["104.21.1.1"]
    assert [a for a in dns["asked"] if a[0] == "doh"] == []


def test_doh_answers_when_the_system_fails_or_lies(dns):
    dns["doh"]["1.1.1.1"] = ["104.21.2.2"]
    assert _ips(socket.getaddrinfo(CF, 443)) == ["104.21.2.2"]
    # A provider's DNS that answers with a private address is no better than none.
    dns["system"]["kws4.example-cf.com"] = ["10.10.34.35"]
    assert _ips(socket.getaddrinfo("kws4.example-cf.com", 443, socket.AF_INET)) == ["104.21.2.2"]


def test_another_doh_server_answers_when_one_is_blocked(dns):
    dns["doh"]["8.8.8.8"] = ["172.67.3.3"]
    assert _ips(socket.getaddrinfo(CF, 443)) == ["172.67.3.3"]
    # All asked at once: a blocked one doesn't hold the others up.
    assert {a[1] for a in dns["asked"] if a[0] == "doh"} >= {"1.1.1.1", "8.8.8.8"}


def test_doh_answers_are_cached(dns):
    dns["doh"]["1.1.1.1"] = ["104.21.2.2"]
    socket.getaddrinfo(CF, 443)
    cf_dns._pool.submit(lambda: None).result()  # let the other servers' replies land
    __import__("time").sleep(0.1)
    doh = lambda: [x for x in dns["asked"] if x[0] == "doh"]  # noqa: E731
    asked = len(doh())
    socket.getaddrinfo(CF, 443)
    assert len(doh()) == asked


def test_unreachable_doh_is_remembered_so_lookups_dont_wait(dns):
    """Always-DoH on a network that blocks it: the first lookup finds out, the next
    ones use the system's DNS at once instead of waiting for DoH again."""
    cf_dns.configure(lambda: ["example-cf.com"], always_doh=True)
    dns["system"][CF] = ["104.21.1.1"]
    assert _ips(socket.getaddrinfo(CF, 443)) == ["104.21.1.1"]
    asked = len([a for a in dns["asked"] if a[0] == "doh"])
    assert asked == len(cf_dns.DOH_SERVERS)
    dns["system"]["kws4.example-cf.com"] = ["104.21.4.4"]
    assert _ips(socket.getaddrinfo("kws4.example-cf.com", 443)) == ["104.21.4.4"]
    assert len([a for a in dns["asked"] if a[0] == "doh"]) == asked


def test_a_hanging_doh_server_costs_no_more_than_the_budget(dns, monkeypatch):
    import threading
    import time
    release = threading.Event()

    def hang(url):
        release.wait(5)
        raise OSError("timed out")
    monkeypatch.setattr(cf_dns, "_fetch_json", hang)
    monkeypatch.setattr(cf_dns, "DOH_BUDGET_S", 0.3)
    dns["system"][CF] = ["10.0.0.1"]  # a provider's unusable answer: DoH is asked
    started = time.monotonic()
    try:
        assert _ips(socket.getaddrinfo(CF, 443)) == ["10.0.0.1"]  # nothing better came
        assert time.monotonic() - started < 2
    finally:
        release.set()


def test_nothing_answers_keeps_the_system_error(dns):
    with pytest.raises(socket.gaierror):
        socket.getaddrinfo(CF, 443)


def test_always_doh_asks_it_first_and_falls_back_to_the_system(dns):
    cf_dns.configure(lambda: ["example-cf.com"], always_doh=True)
    dns["system"][CF] = ["104.21.1.1"]
    dns["doh"]["1.1.1.1"] = ["104.21.9.9"]
    assert _ips(socket.getaddrinfo(CF, 443)) == ["104.21.9.9"]
    assert ("system", CF) not in dns["asked"]
    cf_dns.configure(lambda: ["example-cf.com"], always_doh=True)
    del dns["doh"]["1.1.1.1"]
    assert _ips(socket.getaddrinfo(CF, 443)) == ["104.21.1.1"]  # DoH blocked: the system's


def test_a_chosen_cloudflare_ip_wins(dns):
    cf_dns.configure(lambda: ["example-cf.com"], cf_ip="104.16.0.1")
    results = socket.getaddrinfo(CF, 443, socket.AF_UNSPEC, socket.SOCK_STREAM)
    assert results == [(socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", ("104.16.0.1", 443))]
    assert dns["asked"] == []


def test_asyncio_uses_it(dns):
    dns["doh"]["1.1.1.1"] = ["104.21.2.2"]

    async def resolve():
        return await asyncio.get_running_loop().getaddrinfo(CF, 443, type=socket.SOCK_STREAM)
    assert _ips(asyncio.run(resolve())) == ["104.21.2.2"]


def test_check_ip():
    assert cf_dns.check_ip("") == ""
    assert cf_dns.check_ip(" 104.16.0.1 ") == ""
    assert cf_dns.check_ip("2606:4700::1") == ""
    assert cf_dns.check_ip("cloudflare.com") == "cloudflare.com"


def test_lookups_of_one_name_share_a_query(dns, monkeypatch):
    """The CF check opens ~18 connections to 3 names at once: one query per name."""
    import threading
    import time
    asked = []

    def slow(url):
        asked.append(url)
        time.sleep(0.2)
        return {"Status": 0, "Answer": [{"type": 1, "TTL": 300, "data": "104.21.2.2"}]}
    monkeypatch.setattr(cf_dns, "_fetch_json", slow)
    results = []
    threads = [threading.Thread(target=lambda: results.append(_ips(socket.getaddrinfo(CF, 443))))
               for _ in range(10)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert results == [["104.21.2.2"]] * 10
    assert len(asked) == len(cf_dns.DOH_SERVERS)


def test_requests_that_never_left_the_queue_dont_mark_doh_down(dns, monkeypatch):
    """A lookup whose requests waited behind others' and ran out of time says
    nothing about DoH: it isn't marked unreachable."""
    import concurrent.futures
    import threading
    release = threading.Event()
    busy = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    busy.submit(release.wait, 5)  # the only worker is taken
    monkeypatch.setattr(cf_dns, "_pool", busy)
    monkeypatch.setattr(cf_dns, "DOH_BUDGET_S", 0.2)
    try:
        dns["system"][CF] = ["10.0.0.1"]
        assert _ips(socket.getaddrinfo(CF, 443)) == ["10.0.0.1"]
        assert cf_dns._doh_down_until == 0.0
    finally:
        release.set()
        busy.shutdown(wait=True)


def test_a_failing_system_dns_is_skipped_for_a_while(dns):
    """A provider's DNS that doesn't answer costs seconds per lookup: after it
    failed once for a name, DoH is asked first for that name."""
    dns["doh"]["1.1.1.1"] = ["104.21.2.2"]
    assert _ips(socket.getaddrinfo(CF, 443)) == ["104.21.2.2"]
    assert dns["asked"].count(("system", CF)) == 1
    assert _ips(socket.getaddrinfo(CF, 443)) == ["104.21.2.2"]
    assert dns["asked"].count(("system", CF)) == 1  # not asked again


def test_the_system_is_asked_again_when_doh_fails_too(dns):
    dns["system"]["kws4.example-cf.com"] = ["10.10.34.35"]  # unusable, then DoH blocked
    assert _ips(socket.getaddrinfo("kws4.example-cf.com", 443)) == ["10.10.34.35"]
    # Skipped as first choice now, but still the answer when DoH has none.
    assert _ips(socket.getaddrinfo("kws4.example-cf.com", 443)) == ["10.10.34.35"]


def test_ports_by_number_or_service_name(dns):
    cf_dns.configure(lambda: ["example-cf.com"], cf_ip="104.16.0.1")
    for port, number in ((443, 443), ("443", 443), ("https", 443), (None, 0)):
        assert socket.getaddrinfo(CF, port)[0][4] == ("104.16.0.1", number)
    with pytest.raises(socket.gaierror):
        socket.getaddrinfo(CF, "no-such-service")
