// AndMTProto Cloudflare Worker: carries the proxy's connections to Telegram.
// (The app tells its own Worker from another by the first three words above:
// keep them, or change WorkerSetup.SCRIPT_MARKER and its test along with them.)
//
// The proxy opens a WebSocket to https://<worker>/apiws?dst=<Telegram DC IP>&dc=<N>
// and this Worker opens a TCP connection to that address on port 443 and copies
// bytes both ways. Unlike a plain relay it only connects to Telegram's own
// networks (https://core.telegram.org/resources/cidr.txt), so a leaked Worker
// address can't be used to reach anything else on your free quota.
//
// Same requests as the Worker in tg-ws-proxy's guide (docs/EN/CfWorker.md), so
// the desktop tg-ws-proxy can use it too. MIT licence, part of AndMTProto:
// https://github.com/oruviyex18/AndMTProto

import { connect } from "cloudflare:sockets";

// Telegram's networks, from https://core.telegram.org/resources/cidr.txt (Nov 2026).
export const TELEGRAM_NETWORKS = [
  "91.105.192.0/23",
  "91.108.4.0/22",
  "91.108.8.0/22",
  "91.108.12.0/22",
  "91.108.16.0/22",
  "91.108.20.0/22",
  "91.108.56.0/22",
  "149.154.160.0/20",
  "185.76.151.0/24",
  "2001:67c:4e8::/48",
  "2001:b28:f23c::/48",
  "2001:b28:f23d::/48",
  "2001:b28:f23f::/48",
  "2a0a:f280::/32",
];

const TELEGRAM_PORT = 443;
// How long Telegram gets to accept the TCP connection.
const CONNECT_TIMEOUT_MS = 10000;
// Bytes from the proxy waiting to go out to Telegram; past this the connection
// is closed (1013, "try again later") instead of filling the Worker's memory.
export const MAX_PENDING_BYTES = 8 * 1024 * 1024;

/** An IPv4 address as a BigInt, or null if `text` isn't one. */
function parseIpv4(text) {
  const parts = text.split(".");
  if (parts.length !== 4) return null;
  let value = 0n;
  for (const part of parts) {
    if (!/^\d{1,3}$/.test(part)) return null;
    const byte = Number(part);
    if (byte > 255) return null;
    value = (value << 8n) | BigInt(byte);
  }
  return value;
}

/** An IPv6 address (with or without brackets) as a BigInt, or null. */
function parseIpv6(text) {
  let s = text;
  if (s.startsWith("[") && s.endsWith("]")) s = s.slice(1, -1);
  if (!s.includes(":") || s.includes("%")) return null;
  const halves = s.split("::");
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  const missing = 8 - head.length - tail.length;
  if (halves.length === 1 ? missing !== 0 : missing < 1) return null;
  const groups = [...head, ...Array(halves.length === 2 ? missing : 0).fill("0"), ...tail];
  let value = 0n;
  for (const group of groups) {
    if (!/^[0-9a-fA-F]{1,4}$/.test(group)) return null;
    value = (value << 16n) | BigInt(parseInt(group, 16));
  }
  return value;
}

/** [bits, value] for an address, or null if it's neither IPv4 nor IPv6. */
function parseAddress(text) {
  const v4 = parseIpv4(text);
  if (v4 !== null) return [32, v4];
  const v6 = parseIpv6(text);
  if (v6 !== null) return [128, v6];
  return null;
}

const PARSED_NETWORKS = TELEGRAM_NETWORKS.map((cidr) => {
  const [base, prefix] = cidr.split("/");
  const [bits, value] = parseAddress(base);
  return { bits, value, prefix: BigInt(prefix) };
});

/** True if `dst` is an IP address inside one of Telegram's networks. Names aren't accepted. */
export function isTelegramAddress(dst) {
  if (typeof dst !== "string" || dst.length > 64) return false;
  const parsed = parseAddress(dst.trim());
  if (parsed === null) return false;
  const [bits, value] = parsed;
  return PARSED_NETWORKS.some((net) => {
    if (net.bits !== bits) return false;
    const shift = BigInt(bits) - net.prefix;
    return value >> shift === net.value >> shift;
  });
}

function toBytes(data) {
  if (data instanceof Uint8Array) return Promise.resolve(data);
  if (data instanceof ArrayBuffer) return Promise.resolve(new Uint8Array(data));
  if (typeof data === "string") return Promise.resolve(new TextEncoder().encode(data));
  if (data && typeof data.arrayBuffer === "function") {
    return data.arrayBuffer().then((ab) => new Uint8Array(ab));
  }
  return Promise.resolve(new Uint8Array());
}

function closeQuietly(ws, code, reason) {
  try {
    ws.close(code, reason);
  } catch {}
}

function withTimeout(promise, ms) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error("connect timed out")), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/** Copies bytes between the proxy's WebSocket and a TCP connection to Telegram. */
async function relay(server, dst) {
  let socket;
  try {
    socket = connect({ hostname: dst, port: TELEGRAM_PORT });
  } catch {
    closeQuietly(server, 1011, "telegram unreachable");
    return;
  }
  const opened = withTimeout(socket.opened, CONNECT_TIMEOUT_MS);

  const writer = socket.writable.getWriter();
  let pending = 0;
  // The proxy sends its first bytes right after the upgrade, while the TCP
  // connect to Telegram is still under way: the listeners below are added at
  // once, and the first write waits for the connect.
  let writes = opened.catch(() => {});
  let closed = false;

  const shutDown = (code, reason) => {
    if (closed) return;
    closed = true;
    closeQuietly(server, code, reason);
    writer.close().catch(() => {});
    try {
      socket.close();
    } catch {}
  };

  // Writes go out in order; a slow Telegram connection must not let the queue grow without end.
  server.addEventListener("message", (event) => {
    if (closed) return;
    // Counted on arrival, so the limit sees everything still waiting in line.
    const queued = toBytes(event.data).then((bytes) => {
      pending += bytes.byteLength;
      if (pending > MAX_PENDING_BYTES) shutDown(1013, "too much data queued");
      return bytes;
    });
    writes = writes.then(async () => {
      const bytes = await queued;
      if (closed) return;
      try {
        await writer.write(bytes);
      } catch {
        shutDown(1011, "telegram write failed");
      } finally {
        pending -= bytes.byteLength;
      }
    });
  });
  server.addEventListener("close", () => shutDown(1000, "closed"));
  server.addEventListener("error", () => shutDown(1011, "websocket error"));

  try {
    await opened;
  } catch {
    shutDown(1011, "telegram unreachable");
    return;
  }
  const reader = socket.readable.getReader();
  try {
    while (!closed) {
      const { value, done } = await reader.read();
      if (done) break;
      if (value && value.byteLength) server.send(value);
    }
    shutDown(1000, "telegram closed");
  } catch {
    shutDown(1011, "telegram read failed");
  }
}

export default {
  async fetch(request) {
    if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      return new Response("AndMTProto worker: expected a WebSocket", { status: 426 });
    }
    const url = new URL(request.url);
    if (url.pathname !== "/apiws") {
      return new Response("Not found", { status: 404 });
    }
    const dst = url.searchParams.get("dst");
    if (!isTelegramAddress(dst)) {
      return new Response("Only Telegram's addresses are allowed", { status: 403 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();
    relay(server, dst.trim());
    return new Response(null, { status: 101, webSocket: client });
  },
};
