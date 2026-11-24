# Apace Launcher

**Apace Launcher runs the [Apace](https://github.com/KotPasztet/Apace) Minecraft server control panel entirely on an ARM64 Android phone — no Termux, no Docker, no root.** The app installs an Ubuntu ARM64 rootfs under PRoot, adds OpenJDK 17, ASP.NET Core Runtime 10 and Apace, then shows the original web panel in a full-screen immersive WebView at `http://127.0.0.1:5000`.

## Requirements

| Requirement | Value |
|---|---|
| CPU | ARM64 (`arm64-v8a`) phone |
| Android | 8.0+ (minSdk 26) |
| Download | ~3 GB on first install |
| Free space | ~3.5 GB |
| RAM | 6 GB+ recommended |
| x86 emulator | **NO** — ARM64 only |

## Install

1. Download `ApaceLauncher.apk` from **GitHub Releases** (tag builds) or from the **Actions artifacts** of the latest workflow run.
2. Allow "install unknown apps" for your browser/file manager in Android settings.
3. Open the app and press **Install Apace**.
4. The first install asks for a **channel**:
   - **Main — stable release (recommended)** — tracks the GitHub Releases of [KotPasztet/Apace](https://github.com/KotPasztet/Apace/releases);
   - **Dev — CI build** — requires a CI manifest URL; the workflow that produces the manifest is [`apacemobile/docs/main-channel.yml`](apacemobile/docs/main-channel.yml) (run it in your Apace fork).

Step-by-step instructions for the phone: [`apacemobile/docs/INSTALL.md`](apacemobile/docs/INSTALL.md).

## Behaviour

- The server **auto-starts when you open the app** (if it is not already running): the screen shows an "Apace loading…" spinner (with the last log line) until the panel answers on `127.0.0.1:5000`.
- When ready, the panel **fills the whole screen** — no status bar, no navigation bar (immersive mode). This is the original Apace web UI, unmodified.
- **Inside the panel**, the Android Back button opens the **app menu**: Stop server / Console (logs) / Backup / Restore / Update / Settings / Licenses. Back again minimizes the app.
- **Outside the panel** (install and progress screens) the same menu is available from a corner **⋮** button.
- A foreground service with a wake lock keeps the server alive after you minimize the app; the notification carries **Open panel** / **Stop** actions.
- Ports: panel **5000** (TCP), API **1808** (TCP), Minecraft **Bedrock 19132/UDP** — players must be on the same Wi-Fi as the host.

## Update channels

| Channel | Source | Notes |
|---|---|---|
| **Main** | GitHub Releases of KotPasztet/Apace | stable, recommended |
| **Dev** | CI manifest `{version, url, sha256}` | paste the manifest URL in ⋮ → Update Apace |

On the desktop, the equivalent is switching `ghcr.io/kotpasztet/apace:main` ↔ `:dev` in your `docker-compose.yml`. User data (worlds, accounts) is preserved on every update — the same exclusions as Apace's `install-termux.sh`: `launcher/Data`, `launcher/persistent_fabric`, `launcher/config.json`, `data/`, `components/api_config.json`.

## Build

**GitHub Actions** builds the APK on every push to `main` and on every `v*` tag — workflow: [`.github/workflows/build.yml`](.github/workflows/build.yml), artifact name **ApaceLauncher**.

**Local build** (no Gradle — plain `javac`/`aapt`/`d8`/`apksigner`):

```bash
bash scripts/build_apk.sh
```

Requires JDK 17 and an Android SDK with **platform-28** and **build-tools 33** (33.0.2). All paths can be overridden with env vars — `PROJ`, `JDK` (or `JAVA_HOME`), `SDK` (or `ANDROID_HOME`/`ANDROID_SDK_ROOT`), plus `BT`, `ANDROID_JAR` and `OUT`; defaults and layout are documented in the script header. Example:

```bash
SDK="$HOME/android-sdk" PROJ="$PWD/apacemobile" bash scripts/build_apk.sh
```

Result: `apacemobile/build/ApaceLauncher.apk`. Details: [`apacemobile/docs/BUILD.md`](apacemobile/docs/BUILD.md).

> The keystore `apacemobile/keystore/apacemobile.jks` is **intentionally committed** (self-signed, store/key password `apacemobile`, alias `apace`) so that CI builds and your local builds produce APKs signed with the same key — upgrade-compatible, no forced uninstall.

## Technical notes

- **targetSdk 28 on purpose** (like Termux): since Android 10 (API 29), SELinux `W^X` blocks `exec()` of files stored in app data for apps targeting SDK 29+, and PRoot and the whole Ubuntu/.NET/Java environment must run from the app's private storage. Play Store requires targetSdk 34+, which is why distribution goes through GitHub Releases.
- Foreground service + wake lock keep the server running in the background; a boot receiver can start it after reboot (Settings).
- Data survives updates and Dev/Main switches via the same exclusion list as Apace's `install-termux.sh`: `launcher/Data`, `launcher/persistent_fabric`, `launcher/config.json`, `data/`, `components/api_config.json`.
- The embedded PRoot binaries come from the Termux packages (`proot`, `libtalloc`, `libandroid-shmem`) — see `assets/NOTICE.txt` and `scripts/extract_debs.py` for how to refresh them.

## Repository structure

```
apace-launcher/
├── .github/workflows/build.yml   # CI: builds ApaceLauncher.apk on push + tags
├── apacemobile/
│   ├── AndroidManifest.xml       # minSdk 26, targetSdk 28 (intentional)
│   ├── src/org/apacemobile/      # 16 Java sources, plain javac (no Gradle)
│   ├── res/                      # icons, strings, network security config
│   ├── assets/                   # NOTICE.txt + PRoot binaries (proot/loader/libs)
│   ├── keystore/apacemobile.jks  # self-signed, committed on purpose
│   └── docs/                     # INSTALL.md, BUILD.md, main-channel.yml, AUDIT-ETAP0.md
└── scripts/
    ├── build_apk.sh              # the whole build (aapt → javac → d8 → zipalign → apksigner)
    ├── gen_icons.py              # launcher icon generator
    └── extract_debs.py           # extract PRoot binaries from Termux .deb packages
```

## Credits & licenses

- **Apace** — by [KotPasztet](https://github.com/KotPasztet/Apace), the server control panel this app installs and runs.
- **PRoot** — GPL-2.0-or-later; the ARM64 binaries embedded in the APK are built from the [Termux packages](https://github.com/termux/termux-packages) (upstream: proot-me/proot).
- **Ubuntu Base** — rootfs by Canonical.
- **.NET 10 / ASP.NET Core Runtime 10** — MIT.
- **OpenJDK 17** — GPL-2.0 with Classpath Exception.

Full notice with all embedded components: [`apacemobile/assets/NOTICE.txt`](apacemobile/assets/NOTICE.txt).

The app UI and documentation are **in English by design** (user requirement).
