"""Feasibility relay: terminates the TLS that WhatsApp opens to its proxy.

Test tool for whatsapp-relay-test.md, not part of the app.

argv: log_file chat_port media_port upstream_host upstream_port [pp]
The chat port speaks TLS with a throwaway self-signed certificate (like the
official WhatsApp proxy); the decrypted stream goes to upstream_host:upstream_port,
preceded by a PROXY protocol v1 header when the last argument is "pp". The media
port logs what arrives and forwards it to mmg.whatsapp.net:443.
"""
import asyncio
import datetime
import os
import ssl
import sys
import tempfile
import time

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

LOG = open(sys.argv[1], "a", buffering=1, encoding="utf-8")
CHAT_PORT, MEDIA_PORT = int(sys.argv[2]), int(sys.argv[3])
UP_HOST, UP_PORT = sys.argv[4], int(sys.argv[5])
PROXY_PROTOCOL = len(sys.argv) > 6 and sys.argv[6] == "pp"


def log(msg):
    LOG.write(f"{time.strftime('%H:%M:%S')} {msg}\n")


def self_signed_context() -> ssl.SSLContext:
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=30))
            .sign(key, hashes.SHA256()))
    d = tempfile.mkdtemp()
    cp, kp = os.path.join(d, "c.pem"), os.path.join(d, "k.pem")
    with open(cp, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    with open(kp, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM,
                                  serialization.PrivateFormat.PKCS8,
                                  serialization.NoEncryption()))
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cp, kp)
    return ctx


async def pipe(reader, writer, counter, key):
    try:
        while data := await reader.read(65536):
            counter[key] += len(data)
            writer.write(data)
            await writer.drain()
    except (ConnectionError, OSError, ssl.SSLError):
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def relay(reader, writer, first, host, port, tag, header=b""):
    try:
        ur, uw = await asyncio.wait_for(asyncio.open_connection(host, port), 10)
    except Exception as exc:
        log(f"{tag} upstream {host}:{port} failed: {exc!r}")
        writer.close()
        return
    uw.write(header + first)
    counter = {"up": len(first), "down": 0}
    t0 = time.monotonic()
    await asyncio.gather(pipe(reader, uw, counter, "up"),
                         pipe(ur, writer, counter, "down"))
    log(f"{tag} closed {host}:{port} up={counter['up']} down={counter['down']} "
        f"in {time.monotonic() - t0:.1f}s")


async def chat(reader, writer):
    peer = writer.get_extra_info("peername")
    tls = writer.get_extra_info("ssl_object")
    try:
        first = await asyncio.wait_for(reader.read(4096), 15)
    except Exception as exc:
        log(f"[chat] {peer} no data after TLS: {exc!r}")
        writer.close()
        return
    log(f"[chat] {peer} TLS {tls.version() if tls else None} inner first="
        f"{first[:16].hex()} ascii={first[:16]!r} len={len(first)}")
    header = b""
    if PROXY_PROTOCOL:
        header = f"PROXY TCP4 {peer[0]} 127.0.0.1 {peer[1]} {UP_PORT}\r\n".encode()
    await relay(reader, writer, first, UP_HOST, UP_PORT, "[chat]", header)


async def media(reader, writer):
    peer = writer.get_extra_info("peername")
    log(f"[media] {peer} connected")
    try:
        first = await asyncio.wait_for(reader.read(4096), 15)
    except asyncio.TimeoutError:
        log(f"[media] {peer} sent nothing in 15s")
        writer.close()
        return
    if not first:
        log(f"[media] {peer} closed without data")
        return
    log(f"[media] {peer} first={first[:24].hex()} ascii={first[:40]!r} len={len(first)}")
    host = "mmg.whatsapp.net"
    await relay(reader, writer, first, host, 443, "[media]")


async def main():
    ctx = self_signed_context()
    s1 = await asyncio.start_server(chat, "127.0.0.1", CHAT_PORT, ssl=ctx)
    s2 = await asyncio.start_server(media, "127.0.0.1", MEDIA_PORT)
    log(f"v2 chat TLS :{CHAT_PORT} -> {UP_HOST}:{UP_PORT} pp={PROXY_PROTOCOL}; "
        f"media :{MEDIA_PORT}")
    await asyncio.gather(s1.serve_forever(), s2.serve_forever())


asyncio.run(main())
