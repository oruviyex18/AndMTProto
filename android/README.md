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
| `app/src/main/java/.../ProxyTileService.kt` | Quick Settings tile that shows and toggles the proxy. |
| `app/src/main/java/.../BootReceiver.kt` | Starts the proxy after boot when "Start on boot" is on. |
| `tools/make_icons.py` | Generates launcher and notification icons from upstream `icon.ico`. |
| `release-notes-template.md`, `tools/release_notes.py` | Release notes template (Russian, with install and setup guide) and the script that fills it in. |
| `app/requirements.txt` | Python packages for the core, shared by Chaquopy and CI. |
| `gradle/libs.versions.toml` | Versions of Gradle plugins and Android libraries. |
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

### Releasing

Release notes are in Russian, with the installation and setup guide, and an English
summary at the end. They come from [`release-notes-template.md`](release-notes-template.md);
only the summary and the list of changes are written for each release.

1. Bump `versionCode` and `androidRevision` in `app/build.gradle.kts` and merge to `main`.
2. Generate the notes from the repository root. This fills in the version, the previous release
   and the core version, and writes `android/build/release-notes.md` (not committed):
   ```bash
   python android/tools/release_notes.py
   ```
3. In that file, replace `{{SUMMARY}}` and `{{CHANGES}}` (Russian) and `{{SUMMARY_EN}}` and
   `{{CHANGES_EN}}` (English). If the installation or setup steps changed, update the template too.
4. Create an **annotated** tag with the notes and push it. `--cleanup=verbatim` keeps the
   `##` headings, which git would otherwise strip as comments:
   ```bash
   git tag -a android-v1.11.2-android.5 --cleanup=verbatim -F android/build/release-notes.md
   git push origin android-v1.11.2-android.5
   ```
   The workflow refuses to build a tag whose message is empty or still contains `{{...}}`,
   and publishes the message as the GitHub release notes.

Run the adapter tests locally:

```bash
pip install -r android/app/requirements.txt pytest
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
   `app/requirements.txt` (Chaquopy and CI both install from it). A native package needs an
   Android wheel from Chaquopy's index.
4. **`icon.ico` changed:** regenerate the launcher and notification icons with
   `pip install Pillow && python android/tools/make_icons.py`, then commit `android/app/src/main/res`.
5. **Only desktop files changed** (`windows.py`, `ui/`, `utils/`, packaging): nothing to do.
6. Install the APK, connect Telegram, and check that chats and media load. Then bump
   `versionCode` and `androidRevision` in `app/build.gradle.kts` and merge.
   `versionName` follows upstream's `__version__` automatically.

One-time setup on the fork: enable Actions, and allow GitHub Actions to create pull
requests (Settings → Actions → General).
