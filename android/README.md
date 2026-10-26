# AndMTProto: tg-ws-proxy for Android

Runs the unmodified [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) Python core
(`../proxy/`) on Android through [Chaquopy](https://chaquo.com/chaquopy/). The core
listens on `127.0.0.1:1443`, and the Telegram app connects to it as an MTProto proxy.

## Layout

| Path | What it is |
|---|---|
| `../proxy/` | Upstream core, copied into the APK at build time (`syncUpstreamProxy` task). **Never edit it here.** |
| `app/src/main/python/android_entry.py` | Adapter: config mapping, start/stop thread, status, `tg://` link. Mirrors `utils/tray_common.py`. |
| `app/src/main/java/.../ProxyCore.kt` | Kotlin → Python calls. |
| `app/src/main/java/.../ProxyService.kt` | Foreground service that owns the proxy thread, with notification actions. |
| `app/src/main/java/.../MainActivity.kt` | Compose screen: status, start/stop, Telegram link, settings, logs. |
| `app/src/main/java/.../ProxySettings.kt` | Settings, stored with the same JSON keys as the desktop tray config. |
| `tests/test_android_entry.py` | Desktop pytest for the adapter against the real `proxy/`. |

## Build

Requirements: JDK 17+, Android SDK (platform 37), and **Python 3.13** on `PATH`
(`py -3.13` on Windows). Chaquopy requires the build Python's major.minor to match
the app's Python version. If 3.13 isn't on `PATH`, pass its path explicitly:

```bash
cd android
./gradlew assembleDebug -Pandmtproto.buildPython=/path/to/python3.13
```

The APK is written to `app/build/outputs/apk/debug/`. CI (`.github/workflows/android.yml`)
runs the upstream core tests and the adapter tests, then builds debug and release APKs.
Pushing a tag `android-vX.Y.Z` publishes a GitHub release.

### Release signing

Release APKs are signed with the AndMTProto release key. Without it they fall back to the
debug key, which is fine for local testing but must never be published: Android refuses
updates signed with a different key.

- **CI:** the repository secrets `ANDROID_KEYSTORE_BASE64` (base64 of the PKCS12 keystore),
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. The
  workflow prints the signer and fails on an `android-v*` tag if the APK is debug-signed.
- **Locally:** `./gradlew assembleRelease -Pandmtproto.signing=/path/to/keystore.properties`,
  where the file has `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

Keep the keystore and its passwords out of the repository, with a backup. If the key is
lost, existing installs can't be updated.

Run the adapter tests locally:

```bash
pip install "cryptography==42.0.8" "httpx[http2]==0.28.1" certifi pytest
python -m pytest android/tests
```

## Keeping up with upstream

`.github/workflows/upstream-sync.yml` runs daily (or manually). It merges
`Flowseal/tg-ws-proxy` `main` into `upstream-sync/<sha>`, runs tests and the APK
build, and opens a PR. The PR lists any changes to the files the adapter mirrors.

When reviewing a sync PR:

1. **`proxy/config.py` (`ProxyConfig`) or CLI flags in `proxy/tg_ws_proxy.py` changed:**
   update `DEFAULT_CONFIG` and `_apply_config` in `android_entry.py`, then
   `ProxySettings.kt`, the settings form in `MainActivity.kt`, and both `strings.xml`.
2. **`_run()` / `stop_event` or the lifecycle in `utils/tray_common.py` changed:**
   update `start()`/`stop()` in `android_entry.py`.
3. **New import or version pin in `pyproject.toml` that `proxy/` uses:** add it to
   `chaquopy { pip { } }` in `app/build.gradle.kts` and to `android.yml`. A native
   package needs an Android wheel from Chaquopy's index.
4. **Only desktop files changed** (`windows.py`, `ui/`, `utils/`, packaging): nothing to do.
5. Install the APK, connect Telegram, and check that chats and media load. Then bump
   `versionCode` and `androidRevision` in `app/build.gradle.kts` and merge.
   `versionName` follows upstream's `__version__` automatically.

One-time setup on the fork: enable Actions, and allow GitHub Actions to create pull
requests (Settings → Actions → General).
