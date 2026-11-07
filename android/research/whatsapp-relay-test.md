# WhatsApp through a local relay: feasibility test

**Date:** 2026-11-02
**Question:** can the AndMTProto approach (a local proxy on the phone) restore WhatsApp where the provider blocks it?
**Short answer:** for **text chat, yes**, and without Cloudflare: WhatsApp's own proxy setting accepts `127.0.0.1`, and a small relay that removes the proxy TLS and forwards to `g.whatsapp.net:5222` got past Beeline's DPI from the phone's own connection. **Media** was not resolved. **Calls** can't work this way (UDP).

## Setup

| | |
|---|---|
| Phone | Redmi Note 10 Pro, Android 13, MIUI 14 |
| WhatsApp | 2.26.42.74 |
| Network | Home Wi-Fi via Beeline (AS8402), where WhatsApp is blocked |
| Relay | [`wa_relay.py`](wa_relay.py) on the PC, reached from the phone over USB (`adb reverse`) |
| PC network | Default route through a VPN tunnel (not blocked) |

## Results

### 1. How Beeline blocks WhatsApp

Checked from the phone with `curl` (no proxy):

| Target | TCP connect | TLS | Conclusion |
|---|---|---|---|
| `ya.ru:443` | 0.03 s | OK | not blocked |
| `www.cloudflare.com:443` | 0.03 s | OK | not blocked |
| `web.whatsapp.com:443` | 0.05 s | hangs | **DPI: blocked by TLS server name (SNI)** |
| `g.whatsapp.net:443` | 0.05 s | hangs | same |
| `mmg.whatsapp.net:443` | — | hangs | same |
| `web.telegram.org:443` | no connection | — | IP block (for comparison) |

WhatsApp is blocked by inspecting the TLS handshake, not by IP address. The servers themselves are reachable.

### 2. WhatsApp's built-in proxy accepts a local address

- The setting is at WhatsApp → Settings → **Data and storage → Proxy** (RU: «Данные и хранилище → Прокси-сервер»). It can be opened directly with
  `adb shell am start -n com.whatsapp/.settings.ui.SettingsDataUsageActivity`.
- Proxy host `127.0.0.1` was accepted. Defaults: chat port **443** with **"Use TLS"** on, media port **587**.
- `https://wa.me/proxy?host=…&chatPort=…&mediaPort=…` links did **not** work ("Couldn't open link").
- WhatsApp shows the proxy state under the entry: "Connecting…", "Chat: available / unavailable", "Media: available / unavailable".

### 3. What WhatsApp sends to the proxy (chat port)

- It opens **TLS to the proxy** with server name `localhost`.
- It **accepts a self-signed certificate** (TLS 1.3 completed; no certificate check).
- Inside the TLS, WhatsApp's own protocol starts with the bytes `45 44 00 01` (`ED\x00\x01`). This is end-to-end encrypted; the relay only passes it on.
- Forwarding the inner stream unchanged to `g.whatsapp.net:5222` works. No PROXY-protocol header is needed.

### 4. Chat through the relay: two paths

| Path after the relay | WhatsApp status | Duration |
|---|---|---|
| PC's VPN → `g.whatsapp.net:5222` | **Chat: available** | 105 s, until switched off |
| **Phone's own Beeline Wi-Fi** → `g.whatsapp.net:5222` (163.70.151.61) | **Chat: available** | > 2.5 min, until switched off |

The second path proves the point: the DPI blocks WhatsApp's normal TLS but let the inner protocol to port 5222 through. The phone's connection list (`/proc/net/tcp`) showed the established connection from the phone's Wi-Fi address to 163.70.151.61:5222.

How the second path was wired: WhatsApp → `127.0.0.1:18555` (phone) → `adb reverse` → relay on the PC (removes TLS) → `127.0.0.1:18522` → `adb forward` → `nc -L -s 127.0.0.1 -p 18522 nc g.whatsapp.net 5222` on the phone → internet.

### 5. Media: not resolved

WhatsApp never opened a connection to the media port in either test (a manual request to that port did arrive, so the forwarding worked). The status stayed "Media: unavailable". Finding out what triggers media traffic would have required opening chats, which was not done.

### 6. Cloudflare Worker: not tested

No Cloudflare Worker was available (AndMTProto's Worker setting is empty). Upstream's Worker script ([`docs/EN/CfWorker.md`](../../docs/EN/CfWorker.md)) relays a WebSocket to any `dst` host on port 443. WhatsApp's chat servers accept the same protocol on 443, so that Worker should carry WhatsApp unchanged. That makes it a fallback if port 5222 gets blocked too.

## What a WhatsApp app would need

1. A local listener for the chat port that completes TLS with a generated self-signed certificate.
2. Forward the decrypted stream to `g.whatsapp.net:5222` directly; optionally fall back to a Cloudflare Worker (WebSocket → `g.whatsapp.net:443`).
3. Media: still to be investigated.
4. Calls: not possible with this approach (UDP).

The Android parts of AndMTProto (foreground service, settings, boot start, release process) would carry over; the Telegram proxy core would not.

## Open questions

- Does the DPI keep letting port 5222 through for hours, and on mobile data?
- What protocol does WhatsApp use on the media port, and what triggers it?
- Does Meta accept connections from Cloudflare Worker addresses?

## State after the test

- WhatsApp's proxy is switched **off**. An inactive `127.0.0.1:18555` entry remains in its proxy list (can be removed in WhatsApp → Data and storage → Proxy).
- Relay, `nc` on the phone and USB forwarding are stopped. AndMTProto and Telegram were not touched.

## Reproducing

```bash
pip install cryptography
python wa_relay.py relay.log 18555 18587 g.whatsapp.net 5222
adb reverse tcp:18555 tcp:18555
adb reverse tcp:18587 tcp:18587
```

Then set WhatsApp's proxy to `127.0.0.1`, chat port `18555` (TLS on), media port `18587`. For the direct-path test, run `adb shell "nohup nc -L -s 127.0.0.1 -p 18522 nc g.whatsapp.net 5222 &"`, `adb forward tcp:18522 tcp:18522`, and start the relay with upstream `127.0.0.1 18522`.
