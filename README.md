<p align="center">
  <img src="apacemobile/res/mipmap-xxxhdpi/ic_launcher.png" alt="Apace Launcher" width="120">
</p>

<p align="center">
  <a href="./LICENSE"><img src="https://img.shields.io/badge/license-MIT-green?style=flat-square" alt="License"></a>
  <img src="https://img.shields.io/github/stars/KotPasztet/apace-launcher?style=flat-square" alt="GitHub Stars">
  <img src="https://img.shields.io/github/downloads/KotPasztet/apace-launcher/total?style=flat-square" alt="GitHub Downloads">
  <a href="https://github.com/KotPasztet/apace-launcher/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/KotPasztet/apace-launcher/build.yml?style=flat-square" alt="Build APK"></a>
  <a href="https://discord.gg/5K8HJukMSX"><img src="https://img.shields.io/badge/Discord-join_us-5865F2?logo=discord&logoColor=white&style=flat-square" alt="Discord"></a>
</p>

# Apace Launcher

**Run the [Apace](https://github.com/KotPasztet/Apace) Minecraft server control panel entirely on your phone.** The app installs an Ubuntu ARM64 rootfs under PRoot, adds OpenJDK 17, ASP.NET Core Runtime 10 and Apace, then shows the original web panel in a full-screen immersive WebView at `http://127.0.0.1:5000`. No Termux, no Docker, no root.

> [!NOTE]
> **Requirements:** ARM64 phone (`arm64-v8a`) · Android 8.0+ · ~3 GB first-install download · ~3.5 GB free space.

> [!TIP]
> ### 📦 Download
> - **[Actions → Build APK](https://github.com/KotPasztet/apace-launcher/actions/workflows/build.yml)** — the latest run's **artifact `ApaceLauncher`** contains the APK (GitHub wraps it in a zip — unzip and install)
> - **[Actions → Release APK](https://github.com/KotPasztet/apace-launcher/actions/workflows/release.yml)** — manual workflow: run it with a tag (e.g. `v1.1.0`) to publish a proper [Release](https://github.com/KotPasztet/apace-launcher/releases) with the `.apk` attached
>
> The APK is signed with the same key on every build, so it installs over an older version as a normal update.

## How it works

| Step | What happens |
|---|---|
| First run | Pick the channel: **Main** (stable, Apace GitHub Releases) or **Dev** (the Apace `dev` branch — the rolling [`dev-build`](https://github.com/KotPasztet/Apace/releases/tag/dev-build) prerelease, or the one published by this repo's **Build Apace dev-build** workflow; works out of the box, nothing to paste) |
| Install | 10 stages: PRoot → Ubuntu ARM64 → libraries → Java 17 → .NET 10 → Apace → smoke test, with a live console |
| Daily use | The server **auto-starts** when you open the app ("Apace loading…" spinner), then the panel fills the whole screen |
| In the panel | Android **Back** opens the app menu: Stop / Console / Backup / Restore / Update / Settings / Licenses |

Ports: `5000/tcp` panel · `1808/tcp` ApiPort · `19132/udp` Bedrock.

## Build

GitHub Actions builds the APK on every push and tag (`.github/workflows/build.yml`).

```bash
# locally (JDK 17 + Android SDK: platform-28, build-tools 33)
bash scripts/build_apk.sh
```

`targetSdk 28` is intentional (like Termux): higher targetSdk blocks `exec()` from app storage on Android 10+ — PRoot would not start.

## Links

- **Apace server:** [github.com/KotPasztet/Apace](https://github.com/KotPasztet/Apace)
- **Discord:** [discord.gg/5K8HJukMSX](https://discord.gg/5K8HJukMSX)
- Docs: [INSTALL.md](apacemobile/docs/INSTALL.md) · [BUILD.md](apacemobile/docs/BUILD.md)

## Disclaimer

Apace Launcher is an independent, community-driven project, **not affiliated with Microsoft or Mojang Studios**. Minecraft Earth™ is a trademark of Microsoft Corporation. Third-party components (PRoot, Ubuntu Base, .NET, OpenJDK) remain under their own licenses — see [NOTICE.txt](apacemobile/assets/NOTICE.txt).
