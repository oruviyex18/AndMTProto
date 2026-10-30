<div align="center">

**[🇷🇺 Русский](./README.md) • 🇬🇧 English**

</div>

<div align="center">
	<br />
	<p>
		<img width="1729" height="910" alt="tgwsproxy" src="./docs/images/workflow.png" />
	</p>
</div>

> [!NOTE]
>
> ### This is an Android fork
>
> **AndMTProto** is a fork of [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) made to run on Android.
> The proxy core (`proxy/`) is taken from the original project unchanged and runs on the phone inside an Android app.
> Please report Android issues in [this repository](https://github.com/oruviyex18/AndMTProto/issues), not in the original one.

> [!TIP]
>
> ### [🎉 Support the author of the original project](./docs/EN/Funding.md)
>
> **USDT (TRC20)**: `TXPnKs2Ww1RD8JN6nChFUVmi5r2hqrWjuu`  
> **BTC**: `bc1qr8vd6jelkyyry3m4mq6z5txdx4pl856fu6ss0w`  
> **ETH**: `0x1417878fdc5047E670a77748B34819b9A49C72F1`  

> [!IMPORTANT]
>
> ### Google Play Protect
>
> AndMTProto has passed Google Play Protect's review. Android may still warn that the app comes from outside Google Play or hasn't been scanned yet; it does this for apps installed outside the store. Tap **More details** (or the down arrow) → **Install anyway**. If Android offers to send the app for a scan, you can accept.
>
> If Google Play Protect calls AndMTProto a **harmful app**, it's most likely an old version, or the file didn't come from here or was modified. Delete it and download the latest version from the releases page.
>
> AndMTProto collects no data and talks to no servers other than Telegram, Cloudflare (the fallback route through the shared CF proxy or your own domains and Worker) and GitHub (where the current list of CF proxy domains is downloaded from). The proxy listens only on `127.0.0.1`, so only Telegram on the same phone can connect to it. All the code is open in this repository, and the APKs are built from it by GitHub Actions.
>
> **Download the APK only from [this repository's releases page](https://github.com/oruviyex18/AndMTProto/releases).** Every release since `1.11.2-android.2` is signed with the same AndMTProto key (certificate SHA-256 starts with `14:03:0C:3F`), so Android won't install a tampered build over it.

# AndMTProto (TG WS Proxy for Android)

**A local MTProto proxy** for the Telegram app on Android that **speeds up Telegram** by routing its traffic through WebSocket connections. Data stays encrypted exactly as before, and no third-party servers are needed. The proxy only serves Telegram on the same phone.

## Contents

- **🚀 [Quick start](#quick-start)**
- [Setting up a Cloudflare Worker (a free alternative to the CF proxy)](./docs/EN/CfWorker.md)
- [Setting up a Cloudflare domain (CF proxy)](./docs/EN/CfProxy.md)
- [Telegram test environment (test DCs)](./docs/EN/TestDc.md)
- [Fake TLS + upstream in Nginx](./docs/EN/FakeTlsNginx.md)
- [Building the Android app and syncing with the original project](./android/README.md)

## Quick start

**Requirements:** Android 7.0 or newer, a 64-bit processor (ARM64 or x86_64), and the Telegram app.

1. Go to the [releases page](https://github.com/oruviyex18/AndMTProto/releases) and download the latest `AndMTProto-android-v….apk` from **Assets**.
2. Open the file. If Android asks, allow installing apps from this source (your browser or file manager) and go back.
3. Tap **Install**.

New versions install over old ones and keep your settings. The exceptions are version `1.11.2-android.1` and debug builds you made yourself: they're signed with a different key, so uninstall them first.

### Features

- **Start / Stop**: the proxy runs in the background as a service with a notification
- **Open in Telegram**: sets up the proxy automatically through a `tg://proxy` link
- **Copy link**: copies the connection link
- **Settings**: port, secret, DC → IP, CF proxy and CF Worker, Fake TLS and more
- **Log**: the latest proxy log lines right in the app
- **Check for updates**: opens the latest release page, where you can compare its version with the one shown in the app and download the new APK if needed
- **Quick Settings tile**: turns the proxy on and off from the notification shade
- **Start on boot**: the proxy starts by itself after a restart (after the first unlock)
- Hints for background work: battery optimisation and Autostart on Xiaomi (MIUI / HyperOS)

### Setting up Telegram

**Automatic**

1. Open AndMTProto and tap **Start**. On Android 13 and newer, allow notifications.
2. Tap **Open in Telegram**, then **Connect proxy** in Telegram.

If Telegram doesn't open, tap **Copy link**, send the link to your Saved Messages in Telegram and tap it.

**Manual**

1. Telegram → **Settings** → **Data and Storage** → **Proxy Settings** → **Add Proxy**
2. Add a proxy:
   - **Type:** MTProto
   - **Server:** `127.0.0.1` (or your own)
   - **Port:** `1443` (or your own)
   - **Secret:** `dd` + the secret from AndMTProto's settings (copying the link is easier)

> [!WARNING]
> While Telegram is set to use this proxy, it only works when AndMTProto is running. If you turn the proxy off, turn it off in Telegram too: **Data and Storage** → **Proxy Settings**.

### Background work

To keep the system from stopping the proxy:

- Tap **Allow background work** in AndMTProto. In the list that opens, find AndMTProto and turn battery optimisation off for it (the wording depends on the phone).
- **Xiaomi, Redmi, POCO (MIUI / HyperOS):** tap **Allow background work**, pick AndMTProto, open its background activity control and choose "No restrictions". Then tap **Open Autostart settings** and turn on Autostart for AndMTProto.

## How it works

```
Telegram (Android) → MTProto Proxy (127.0.0.1:1443) → WebSocket → Telegram DC
```

1. The app starts an MTProto proxy on `127.0.0.1:1443`
2. Intercepts connections to Telegram IP addresses
3. Extracts the DC ID from the MTProto obfuscation init packet
4. Opens a WebSocket (TLS) connection to the matching DC through Telegram domains
5. If WS is unavailable (302 redirect), falls back to CfProxy / a direct TCP connection

The proxy core is written in Python and runs inside the app through [Chaquopy](https://chaquo.com/chaquopy/); the interface is Kotlin and Jetpack Compose.

> [!IMPORTANT] 
> ### Photos or videos don't load?
> **In AndMTProto's settings, remove everything from DC → IP except `4:149.154.167.220`**  
> **If that doesn't help, clear the field completely**  
> This mostly happens on accounts without Premium  
> If it still doesn't help, set up your own domain: [CfProxy.md](./docs/EN/CfProxy.md)

> [!TIP]
> ### Port already in use?
> If another proxy on the phone already uses port `1443`, AndMTProto shows an error with a **Use port N** button. Tap it, then **Open in Telegram** → **Connect proxy** again.

## Automated builds

The Android app lives in [`android/`](./android/). The GitHub Actions workflow ([`.github/workflows/android.yml`](./.github/workflows/android.yml)) runs the tests, builds the APK and publishes a signed release when an `android-v*` tag is pushed. The [`.github/workflows/upstream-sync.yml`](./.github/workflows/upstream-sync.yml) workflow checks the original project for changes daily and opens a pull request to bring them in.

Supported devices for current builds:

- Android 7.0+ (API 24), ARM64 (`arm64-v8a`): most phones
- Android 7.0+ (API 24), x86_64: emulators and some tablets

## License

[MIT License](./LICENSE)
