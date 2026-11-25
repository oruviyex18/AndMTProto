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


def test_the_next_doh_server_when_one_is_blocked(dns):
    dns["doh"]["8.8.8.8"] = ["172.67.3.3"]
    assert _ips(socket.getaddrinfo(CF, 443)) == ["172.67.3.3"]
    assert [a[1] for a in dns["asked"] if a[0] == "doh"] == ["1.1.1.1", "8.8.8.8"]


def test_doh_answers_are_cached(dns):
    dns["doh"]["1.1.1.1"] = ["104.21.2.2"]
    socket.getaddrinfo(CF, 443)
    socket.getaddrinfo(CF, 443)
    assert [a for a in dns["asked"] if a[0] == "doh"] == [("doh", "1.1.1.1")]


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
