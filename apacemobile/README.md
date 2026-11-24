# apacemobile — the Android app module

This module contains the Apace Launcher Android app (package `org.apacemobile`), built **without Gradle**:

- `src/org/apacemobile/` — plain Java sources (no AndroidX, no external dependencies; `javac` source/target 8 against `android.jar`);
- `res/` — launcher icons, app strings, network security config;
- `assets/` — `NOTICE.txt` (full license notice) and the embedded PRoot binaries (`assets/proot/`);
- `keystore/apacemobile.jks` — self-signed release key (store/key password `apacemobile`, alias `apace`), **committed on purpose** so CI and local builds are upgrade-compatible;
- `AndroidManifest.xml` — minSdk 26, targetSdk 28 (intentional, see the root README);
- `docs/` — user and build documentation (`INSTALL.md`, `BUILD.md`, `AUDIT-ETAP0.md`).

The build entry point is [`../scripts/build_apk.sh`](../scripts/build_apk.sh) — see the root [README](../README.md) and [`docs/BUILD.md`](docs/BUILD.md).
