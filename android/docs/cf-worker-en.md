# Your own Cloudflare Worker for AndMTProto

Your own Worker is a free backup route to Telegram through Cloudflare: AndMTProto connects to it, and the Worker connects to Telegram's servers. It helps when the shared CF proxy is overloaded or doesn't answer, and for data centres 1, 3 and 5, which often don't load through the shared CF proxy. All you need is a free Cloudflare account; the free plan allows 100,000 connections a day.

This Worker connects **only to Telegram's servers** (their official address list). Even if someone learns your Worker's address, they can't use it to reach other sites or spend your quota. It also works with the desktop tg-ws-proxy.

## Setup (about 5 minutes)

1. If the Cloudflare site doesn't open for you, or the code page doesn't load, turn on a VPN or a DPI bypass for `cloudflare.com`, `cloudflare.dev` and `workers.dev` while you set it up. AndMTProto itself doesn't need that afterwards.
2. Sign up at [dash.cloudflare.com](https://dash.cloudflare.com/) (or sign in) and **confirm your email** with the link you receive.
3. On the left, choose **Compute** → **Workers & Pages**.
4. Click **Create application** → **Start with Hello World!** → **Deploy**.
5. Click **Edit code** at the top right. On the left, delete all the code and paste the Worker's code:
   - open [worker.js](https://github.com/oruviyex18/AndMTProto/blob/main/android/cf-worker/worker.js) and press **Copy raw file** (the copy icon above the code), or open [the plain file](https://raw.githubusercontent.com/oruviyex18/AndMTProto/main/android/cf-worker/worker.js), select all and copy.
6. Click **Deploy** at the top right.
7. Copy the Worker's address from the field on the right, e.g. `random-name-1234.username.workers.dev`.
8. In AndMTProto: **Settings** → **Route** section → turn on **Use CF Worker** and paste the address into **CF Worker domains**. Separate several addresses with commas. Go back to the main screen: the settings are saved and the proxy restarts.

## Checking it

Open AndMTProto's log: connections through the Worker show lines like `CF worker … for …`. If the Worker answers with error 403, make sure you pasted the code from this page: it refuses connections to anything but Telegram.

## If something's wrong

- **Error 1101 or "Worker threw exception"**: the code wasn't pasted in full. Repeat steps 5–6.
- **Quota.** The free plan allows 100,000 connections a day per account; it resets at 00:00 UTC. When it runs out, AndMTProto skips that Worker until the reset and uses the shared CF proxy.
- **Updating the code.** When AndMTProto ships a new Worker version, repeat steps 5–6 for the same Worker; its address stays the same.
