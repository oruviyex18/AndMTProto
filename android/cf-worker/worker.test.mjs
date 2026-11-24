// Tests for worker.js under Node: node --test android/cf-worker
// cloudflare:sockets, WebSocketPair and the 101 Response are Cloudflare's; small stand-ins below.
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { pathToFileURL } from "node:url";

// Node can't load cloudflare:sockets: test a copy of worker.js that imports the stand-in.
const stubUrl = new URL("./test-sockets-stub.mjs", import.meta.url).href;
const source = readFileSync(new URL("./worker.js", import.meta.url), "utf8");
const importLine = 'import { connect } from "cloudflare:sockets";';
assert.ok(source.includes(importLine), "worker.js imports cloudflare:sockets");
const copy = join(mkdtempSync(join(tmpdir(), "andmtproto-worker-")), "worker.mjs");
writeFileSync(copy, source.replace(importLine, `import { connect } from ${JSON.stringify(stubUrl)};`));

const sockets = await import(stubUrl);
const { default: worker, isTelegramAddress, MAX_PENDING_BYTES } = await import(pathToFileURL(copy).href);

class FakeWebSocket {
  constructor() {
    this.listeners = {};
    this.sent = [];
    this.closedWith = null;
  }
  accept() {}
  addEventListener(type, fn) {
    (this.listeners[type] ||= []).push(fn);
  }
  emit(type, event = {}) {
    for (const fn of this.listeners[type] || []) fn(event);
  }
  send(data) {
    this.sent.push(data);
  }
  close(code, reason) {
    this.closedWith ??= { code, reason };
  }
}

globalThis.WebSocketPair = class {
  constructor() {
    const server = new FakeWebSocket();
    globalThis.lastServer = server;
    return [new FakeWebSocket(), server];
  }
};
const NodeResponse = globalThis.Response;
globalThis.Response = class extends NodeResponse {
  constructor(body, init = {}) {
    const upgrade = init.status === 101;
    super(body, upgrade ? { ...init, status: 200 } : init);
    if (upgrade) Object.defineProperty(this, "status", { value: 101 });
    this.webSocket = init.webSocket;
  }
};

function wsRequest(path, headers = { Upgrade: "websocket" }) {
  return new Request(`https://w.example.workers.dev${path}`, { headers });
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 5));

test("only Telegram's own addresses are allowed", () => {
  for (const ip of ["149.154.167.51", "149.154.175.50", "91.108.56.100", "91.105.192.100",
                    "185.76.151.1", "2001:67c:4e8:f002::a", "[2001:b28:f23d:f001::a]", "2a0a:f280::1"]) {
    assert.equal(isTelegramAddress(ip), true, ip);
  }
  for (const bad of ["1.1.1.1", "149.154.176.1", "91.108.24.1", "example.com", "kws2.web.telegram.org",
                     "149.154.167.51:443", "149.154.167.256", "149.154.167", "", null, undefined,
                     "2001:67c:4e9::1", "::ffff:149.154.167.51", "2001:67c:4e8::1::2", "fe80::1%eth0"]) {
    assert.equal(isTelegramAddress(bad), false, String(bad));
  }
});

test("requests that aren't WebSockets to /apiws for a Telegram address are refused", async () => {
  assert.equal((await worker.fetch(wsRequest("/apiws?dst=149.154.167.51", {}))).status, 426);
  assert.equal((await worker.fetch(wsRequest("/other?dst=149.154.167.51"))).status, 404);
  assert.equal((await worker.fetch(wsRequest("/apiws?dst=1.1.1.1"))).status, 403);
  assert.equal((await worker.fetch(wsRequest("/apiws"))).status, 403);
  assert.equal(sockets.opened.length, 0, "nothing was dialled");
});

test("relays both ways to the DC on port 443 and closes with Telegram", async () => {
  sockets.reset();
  const response = await worker.fetch(wsRequest("/apiws?dst=149.154.167.51&dc=2"));
  assert.equal(response.status, 101);
  const server = globalThis.lastServer;
  await tick();
  const tcp = sockets.opened[0];
  assert.deepEqual(tcp.address, { hostname: "149.154.167.51", port: 443 });

  server.emit("message", { data: new Uint8Array([1, 2, 3]).buffer });
  server.emit("message", { data: new Uint8Array([4]) });
  await tick();
  assert.deepEqual(tcp.written, [1, 2, 3, 4]);

  tcp.reply(new Uint8Array([9, 9]));
  await tick();
  assert.deepEqual([...server.sent[0]], [9, 9]);

  tcp.end();
  await tick();
  assert.equal(server.closedWith.code, 1000);
});

test("an unreachable DC closes the WebSocket", async () => {
  sockets.reset({ failOpen: true });
  await worker.fetch(wsRequest("/apiws?dst=149.154.175.50"));
  const server = globalThis.lastServer;
  await tick();
  assert.equal(server.closedWith.code, 1011);
});

test("too much queued for a stalled DC closes the connection instead of filling memory", async () => {
  sockets.reset({ stallWrites: true });
  await worker.fetch(wsRequest("/apiws?dst=149.154.167.91"));
  const server = globalThis.lastServer;
  await tick();
  const chunk = new Uint8Array(1024 * 1024);
  for (let i = 0; i <= MAX_PENDING_BYTES / chunk.byteLength; i++) server.emit("message", { data: chunk });
  await tick();
  assert.equal(server.closedWith.code, 1013);
});
