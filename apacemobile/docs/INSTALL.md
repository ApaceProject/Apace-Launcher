# Installation and first start (user guide)

## 1. Installing the APK

1. Copy `ApaceLauncher.apk` to your phone (ARM64, Android 8.0+).
2. In Android settings, allow "install unknown apps" for the file manager or browser you used to transfer the file.
3. Install the APK. Android will warn that the app does not come from the Play Store — this is expected (sideload distribution, like Termux from F-Droid).

> **targetSdk 28 is intentional**: since Android 10, SELinux blocks running binaries from app storage at higher targetSdk levels, and PRoot requires exactly that. The Play Store requires targetSdk 34+, which is why distribution goes through GitHub Releases / Actions artifacts.

## 2. First run — the environment installer

Open the app → press **Install Apace**. Confirm. On the first install the app also asks for an **update channel**:

- **Main — stable release (recommended)** — tracks the GitHub Releases of ApaceProject/Apace;
- **Dev — latest dev build** — pulled anonymously from the OCI image `ghcr.io/apaceproject/apace:dev` (the same image `docker-compose.dev.yml` uses; published by the *docker-image-dev* workflow on every push to `dev`). The **full image filesystem** is installed: the image's system layers become the rootfs (Ubuntu 24.04 + Java 17 + .NET 10 + `aapt2`/`zipalign` + the Android framework for the patcher), and the `/app` layers become the Apace program files. Version shown = the commit the image was built from. Layers are cached by digest, so later dev updates download only the changed layers. Works out of the box — nothing to configure.

The installer then downloads (~2.5–3 GB in total, first run):

| Step | What | Size |
|---|---|---|
| PRoot + libraries | from APK assets (instant) | 0.3 MB |
| **Dev channel**: full `ghcr.io/apaceproject/apace:dev` image | system layers (Ubuntu 24.04 + Java 17 + .NET 10 + aapt2/zipalign + android-framework) + `/app` layers | ~600 MB |
| *(or Main channel)* Ubuntu Base 24.04.5 arm64 | glibc rootfs | ~55 MB |
| *(Main)* apt: libicu, libstdc++, ca-certificates, curl, unzip, aapt, zipalign | through proot | ~35 MB |
| *(Main)* OpenJDK 17 JRE | through apt | ~250 MB |
| *(Main)* .NET 10 ASP.NET Core Runtime 10.0.15 | to `/opt/dotnet` | ~100 MB |
| Apace release (Main) | from GitHub Releases | ~120 MB |

Progress is shown in the notification and on the app screen (stage label + progress bar). **You can minimize the app** — installation runs in a foreground service. You need: Wi-Fi, ~3.5 GB of free space, ~3 GB RAM. Time: depending on phone and network, 10–25 minutes. **After installation the server starts automatically.**

> If the installation fails (e.g. a GitHub API limit, an interrupted download), the screen shows the error, and full logs are in the menu **⋮ → Console (logs)**. Run the installer again — the stages are idempotent (PRoot is always overwritten, Ubuntu is reinstalled, Apace is unpacked with data protection).

## 3. Starting the server

After installation the server starts by itself: the screen shows the **"Apace loading…"** spinner (with the last log line), and when the panel answers on `127.0.0.1:5000` (max 5 minutes) it automatically switches to the **original Apace panel in the WebView**. When the server is stopped, the screen shows a **"Start Apace"** button (menu **⋮ → Start server** does the same). The system notification keeps the server alive after you minimize the app.

**Performance:** PRoot has overhead — comfortably 1–2 players on a phone (as stated in Apace's QUICKINSTALL-TERMUX.md).

## 4a. Idle auto-stop and auto-close (battery saving)

When the app is **not open for 5 minutes**, the game (Java) server is **off**, and **nobody is connected** (no Minecraft Earth client, no panel from another device), the launcher stops the server to save battery — the notification says **"battery saving"**. Then the **app closes itself** (finishes and removes itself from Recents), so nothing keeps the process in memory. Reopening the app starts the server again automatically.

Notes:
- the app closing applies **only** to the idle auto-stop — stopping the server manually (notification button, panel) never closes the app;
- the close reaches the app even when Android has already destroyed the background activity while the service kept running — in that case the launcher removes the task (the Recents entry) through the system ActivityManager, so no stale app window is left behind;
- a running game server, an open Patcher page or any external client connected over Wi-Fi **prevents** the auto-stop (each skip reason is written to the console log);
- auto-close can be disabled by advanced users (SharedPreferences `apacemobile`: `auto_close_app=false`).

## 4. The panel

Once running, **the Apace panel appears by itself** — it is the original Apace page (`http://127.0.0.1:5000`) in a WebView; account, world and server management all happen there (the app does not duplicate any of it).

1. Create an account — **the first account becomes the administrator**.
2. Server Options → enter the phone's Wi-Fi IP (`ifconfig wlan0` in the panel, or in Android settings → Network).
3. Server Status → **Start All**, accept the Minecraft EULA (yellow banner).
4. Patch your original Minecraft Earth APK through the **Patcher** page in the panel — **on a PC** (the build-tools are x86-64; patching on the phone may fail, QUICKINSTALL-TERMUX.md §5).
5. Install the patched APK on the player's phone and log in with the panel account.

Players must be on the **same Wi-Fi network** as the host.

**Ports used:** panel **5000/TCP**, internal API **1808/TCP** (ApiPort in `config.json`), Minecraft **Bedrock 19132/UDP**. If you change the panel/API ports in ⋮ → Settings, restart the server.

## 5. Optional: panel on the LAN

By default the panel listens only on `127.0.0.1` (safer). To let other devices on your Wi-Fi open the panel in a browser: menu **⋮ → Settings → Allow LAN Panel** → start the server. The panel then listens on `0.0.0.0:5000` — open `http://<phone-IP>:5000`.

## 6. Keeping the server from being killed

Android vendors aggressively terminate background processes. Mitigation:

- menu **⋮ → Settings → Disable battery optimization** (asks Android to ignore it for Apace Launcher);
- menu **⋮ → Settings → Wake lock** (enabled by default — the CPU does not sleep while the server runs);
- menu **⋮ → Settings → Start after boot** (optional — the server starts after the phone reboots);
- on some brands (Xiaomi, OPPO, Samsung) additionally disable "battery saver" and "automatic app management" for Apace Launcher in the vendor's settings.

24/7 uptime is **not guaranteed** on every device — the server loads the CPU and the battery.

## 7. Backups (before you uninstall)

Android deletes all app data on uninstall — including game worlds and accounts. Menu **⋮**:

- **"Backup data"** — zips the data (`launcher/Data`, `launcher/persistent_fabric`, `launcher/config.json`, `data/`) and immediately offers an **export (SAF)** outside the app (Download folder, USB drive, cloud).
- **"Restore backup (import zip)"** — overwrites the data with the contents of the selected zip (after reinstalling the APK).

## 8. Updates

Menu **⋮ → Update Apace** (also on the Start screen — an "Update available" button appears automatically, and the check result is always shown as a status line — never silent):

- **Main — stable release (recommended)** — the latest official Apace release (GitHub Releases). "Check for updates" → "Update". The server stops, makes a backup (rollback on error), replaces the program files (data preserved).
- **Dev — latest dev build** — the newest `ghcr.io/apaceproject/apace:dev` image (version = the commit the image was built from). "Check for updates" → "Update" — same backup/rollback flow as Main, plus:
  - the check compares the **image commit** with the **dev branch HEAD** (GitHub API). If CI has not built the newest commit yet, the dialog says so and the launcher **re-checks automatically every 3 minutes**; a failed check (e.g. GitHub API rate limit) is always shown as an error with a retry — never as a silent "up to date";
  - the update applies the **full image filesystem**: when the image's system layers changed (new base image, new apt packages), the rootfs is rebuilt (with a rollback); when only `/app` changed, just the program files are refreshed — unchanged layers are served from the local digest cache (usually only the `/app` layer is downloaded);
  - user data (`launcher/Data`, `persistent_fabric`, `data`, `logs`, `resourcepacks`, `config.json`, `api_config.json`) and ASP.NET data-protection keys are preserved — sessions survive the update.
