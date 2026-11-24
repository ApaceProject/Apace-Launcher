# AUDYT TECHNICZNY APACE — ETAP 0 (dla projektu Apace Mobile)

Data: 2026-11-24 • Przedmiot: `github.com/KotPasztet/Apace` @ `main` (v0.1.3) • Metoda: analiza kodu źródłowego (klon) + inspekcja realnego artefaktu `Apace-termux-arm64.zip` z release v0.1.3 (120 MB) + weryfikacja CI (`.github/workflows/release.yml`).

Wszystkie poniższe wnioski są zweryfikowane w kodzie/CI, nie założeniami.

## 1. Co zawiera paczka Release (v0.1.3)

| Cecha | Weryfikacja |
|---|---|
| Asset `Apace-termux-arm64.zip` | istnieje w v0.1.3 (HEAD 302) |
| Zawartość | `launcher/` (374 pliki), `components/` (245), `staticdata/` (186, w tym `server_jars/` — Fabric + buildplate-connector), `run_launcher.ps1` |
| Typ builda | **framework-dependent** (`publish.ps1` → `dotnet publish --no-self-contained -r linux-arm64`) |
| Runtime .NET w zipie | **BRAK** (brak `dotnet`, `libcoreclr`, `createdump` w archiwum — potwierdzone greppem) |
| `Apace-termux-arm64.zip` vs `Apace-linux-arm64.zip` | **identyczne bajty** (`release.yml`: „Add Termux asset alias (same bytes as linux-arm64)”) |
| Launcher | `Apace.LauncherUI` → assembly `Launcher`, ASP.NET Core (`Sdk.Web`, `net10.0`), panel = Blazor; entrypoint: `cd launcher && exec ./Launcher` |

## 2. Znalezisko #1 (bug instalatora Termux w v0.1.3)

`install-termux.sh` instaluje wyłącznie: `ca-certificates, curl, unzip, libicu-dev, libstdc++6, openjdk-17-jre-headless` — **nie instaluje runtime'u .NET**, a Launcher jest framework-dependent. Świeża instalacja z v0.1.3 **nie wystartuje** (apphost bez hostfxr/host runtime). Panel „Terminux działa” jest prawdziwy dla starszych wydań (self-contained) — dla v0.1.3 brakuje kroku.

**Konsekwencja dla Apace Mobile:** własny instalator musi dostarczyć ASP.NET Core Runtime 10.0 (linux-arm64). Zaimplementowano: tarball `aspnetcore-runtime-10.0.15-linux-arm64.tar.gz` (zweryfikowany URL `builds.dotnet.microsoft.com`, release-metadata 2026-11-10) → `/opt/dotnet` w rootfs + `DOTNET_ROOT` + `PATH`; fallback: `dot.net/v1/dotnet-install.sh --channel 10.0 --runtime aspnetcore`.

## 3. PowerShell — NIE jest potrzebny na Androidzie

`pwsh` w Dockerfile służy tylko jako wrapper (`entrypoint.sh` → `pwsh ./run_launcher.ps1`). Ścieżka Termux/Mobile uruchamia `./Launcher` bezpośrednio. Rezygnacja z PowerShell 7.6.1 oszczędza ~180 MB i jedną zależność. Nie instalujemy go.

## 4. Środowisko uruchomieniowe (dokładne env)

Z `install-termux.sh` (run-inside.sh) i `Dockerfile` (ENV):

```
ASPNETCORE_URLS=http://0.0.0.0:5000        # panel (Mobile: 127.0.0.1 domyślnie, 0.0.0.0 przy „Allow LAN”)
DOTNET_SYSTEM_NET_DISABLEIPV6=1
COMPlus_gcServer=0
COMPlus_gcConcurrent=1
DOTNET_GCHeapHardLimit=536870912           # 512 MB; konfigurowalne w Ustawieniach
DOTNET_CLI_TELEMETRY_OPTOUT=1
JAVA_HOME=autodetekcja (JAVA_HOME/bin/java → PATH)
```

Java: `openjdk-17-jre-headless` (fallback 21) — instalowane apt-em w rootfs. JavaLocator sprawdza `JAVA_HOME/bin/java`, potem `java` z PATH.

## 5. Porty (potwierdzone)

| Port | Znaczenie | Źródło |
|---|---|---|
| 5000/TCP | Panel WWW (Blazor Launcher) | `ASPNETCORE_URLS`, install.sh, QUICKINSTALL-TERMUX.md |
| 1808/TCP | ApiPort (ApiServer) | `config.json` default, comment w install.sh |
| 19132/UDP | Bedrock (wspólny, wszystkie instancje) | `EXPOSE` w Dockerfile, CHANGELOG v0.1.0 |
| 5532/TCP | bridge (konfigurowalny) | `EXPOSE` w Dockerfile |

## 6. Ochrona danych przy reinstalacji/aktualizacji (audyt install-termux.sh)

Wykluczenia z `unzip -o`: `launcher/Data/*`, `launcher/logs/*`, `launcher/persistent_fabric/*`, `data/*`, `logs/*` + `launcher/config.json` przywracany po rozpakowaniu. Tworzone katalogi: `launcher/Data`, `launcher/logs`, `launcher/persistent_fabric`, `data`, `logs`, `staticdata/resourcepacks`, `staticdata/server_template_dir/mods`. `chmod +x launcher/Launcher components/*`.

**Apace Mobile 1:1 odtwarza ten model** (ZipExtractor.DATA_EXCLUSIONS + preserve config.json), dodatkowo: pełny backup przez rename `current → staged/pre-update-<ts>` (natychmiastowy), restore danych, smoke test, automatyczny rollback, rotacja do 2 katalogów pre-update. Weryfikacja w testach: realny zip v0.1.3 + istniejący `config.json`/`launcher/Data` → obie rzeczy przetrwały (test zaliczony).

## 7. Kanały aktualizacji

- **Stable**: GitHub Releases (`api.github.com/repos/KotPasztet/Apace/releases/latest`) — dokładnie jak w `install-termux.sh` (pobiera tylko oficjalne Release, nie gałąź main). Asset select: `Apace-termux-arm64.zip` → fallback `Apace-linux-arm64.zip`.
- **Dev**: oficjalny rolling prerelease [`dev-build`](https://github.com/KotPasztet/Apace/releases/tag/dev-build) z brancha `dev` Apace (`api.github.com/repos/KotPasztet/Apace/releases/tags/dev-build`, workflow `development.yml`). Asset: `Apace-Dev-linux-arm64.zip`. Fallback: release `dev-build` w repo Apace-Launcher (workflow `dev-apace.yml` „Build Apace dev-build”). Działa bez konfiguracji — brak SHA-256 w manifeście, więc sumy kontrolnej się nie weryfikuje (SHA pakietu pokazywany w logu).

## 8. PRoot na Androidzie (środowisko)

Termux build proot 5.1.107.102 (aarch64, ELF dla `/system/bin/linker64`, NDK r30) + `libtalloc.so.2` (LGPL-3.0) + `libandroid-shmem.so` (Apache-2.0) — osadzone w APK (assets) z podaniem źródeł i licencji (NOTICE.txt, GPL-2.0 §3). Uruchamianie:

```
proot --kill-on-exit --link2symlink -r <rootfs> -0 -w /root \
      -b /dev -b /proc -b /sys -b <apace>/current:/apace -- \
      env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 PATH=... DOTNET_ROOT=/opt/dotnet \
      APACE_PANEL_BIND=... APACE_GC_HARD_LIMIT=... /bin/bash /apace/run-inside.sh
```

Env procesu PRoot: `PROOT_LOADER`, `PROOT_LOADER32`, `LD_LIBRARY_PATH=<proot dir>`, `PROOT_TMP_DIR`, `PROOT_NO_SECCOMP=1`. Oba flagi (`--kill-on-exit`, `--link2symlink`) potwierdzone w binarce (strings). **targetSdk 28** jest obowiązkowy (jak Termux): od API 29 SELinux W^X blokuje exec z katalogu aplikacji.

## 9. Wymagania sprzętowe (QUICKINSTALL-TERMUX.md)

64-bit arm64, ~3 GB dysku, ~3 GB RAM, 1–2 graczy komfortowo (narzut proot). Apace Mobile sprawdza ≥3,5 GB wolnego miejsca przed instalacją.

## 10. Panel = Blazor w WebView

Panel to ASP.NET Core + Blazor (Server + WASM) serwowany przez Launcher na :5000 — wymaga JS, cookies, DOM storage; wszystko włączone w WebView z ograniczeniem nawigacji do `127.0.0.1:5000` (NSC: cleartext tylko localhost). Upload plików (onShowFileChooser) i download (DownloadManager) obsłużone. Pierwsza konto = admin; EULA Fabric; patching MCE na PC (narzędzia x86-64 — nie da się patchować na telefonie, QUICKINSTALL-TERMUX.md §5).

## 11. Ryzyka przeniesione do dokumentacji (nie do kodu)

- Wydajność proot (narzut ptrace) — zmierzyć na realnym urządzeniu; nie ma w kodzie.
- OEM kill: FGS + partial wake lock + przycisk „wyłącz optymalizację baterii” + diagnostyka; gwarancji 24/7 brak.
- Limit API GitHub (60/h/IP bez auth) — obsłużony komunikatem; kanał Main omija limit (manifest z gh-pages).
