# Building the Apace Launcher APK (without Gradle)

Apace Launcher builds from the command line: **JDK 17 + Android platform-28 + build-tools 33.0.2**. No Gradle, no AndroidX, no external dependencies — the code is plain Java (source/target 8) using only the Android framework (`android.jar`).

## Requirements

- JDK 17 (Temurin) — for `javac`, `keytool`, and running `d8.jar` / `apksigner.jar`
- Android platform 28 (`android.jar`) — `dl.google.com/android/repository/platform-28_r06.zip` (unpacks to `android-9/`)
- Build-tools 33.0.2 — `dl.google.com/android/repository/build-tools_r33.0.2-linux.zip` (unpacks to `android-13/`, contains `aapt`, `d8`, `zipalign`, `apksigner`)
- The build script `scripts/build_apk.sh` accepts two SDK layouts — the legacy one it historically assumed:

  ```
  work/android-sdk/android-9/android.jar
  work/android-sdk/android-13/{aapt,d8,zipalign,apksigner,lib/d8.jar}
  work/jdk17/bin/{javac,keytool,java}
  ```

  and the standard `sdkmanager` layout (what CI installs, `$SDK` = `$HOME/android-sdk`):

  ```
  $SDK/platforms/android-28/android.jar
  $SDK/build-tools/33.0.2/{aapt,d8,zipalign,apksigner,lib/d8.jar}
  ```

All paths can be overridden with environment variables — `PROJ`, `JDK` (or `JAVA_HOME`), `SDK` (or `ANDROID_HOME` / `ANDROID_SDK_ROOT`), and additionally `BT` (build-tools dir), `ANDROID_JAR`, `OUT` (output APK). Defaults are documented in the script header.

## What `scripts/build_apk.sh` does

1. **R.java** — `aapt package -f -m -J gen -M AndroidManifest.xml -S res -I android.jar` (generates `com.kotpasztet.apacelauncher.R` with `R.drawable.ic_stat`, `R.mipmap.ic_launcher`, `R.xml.network_security_config`).
2. **javac** — `javac -source 8 -target 8 -bootclasspath android.jar -classpath android.jar -d obj src/**/*.java gen/**/*.java` (without `-bootclasspath` javac warns; no `--release` so API 26+ stays available).
3. **aapt package** — `aapt package -f -M AndroidManifest.xml -S res -A assets -I android.jar -F base.apk` (packs the manifest, resources and the PRoot binaries from `assets/proot/`).
4. **d8** — `java -cp lib/d8.jar com.android.tools.r8.D8 --release --min-api 26 --lib android.jar --output dex obj/**/*.class` → `classes.dex`; `aapt add base.apk classes.dex`.
5. **zipalign** — `zipalign -f 4 base.apk aligned.apk`.
6. **apksigner** — generates the keystore (RSA 2048, 10000 days) if it is missing, then signs v2+v3:

   ```
   keytool -genkeypair -keystore keystore/apacemobile.jks -alias apace \
     -keyalg RSA -keysize 2048 -validity 10000 -storepass apacemobile -keypass apacemobile \
     -dname "CN=Apace Launcher, OU=ApaceLauncher, O=ApaceLauncher, C=PL"
   apksigner sign --ks keystore/apacemobile.jks --ks-pass pass:apacemobile \
     --key-pass pass:apacemobile --out ApaceLauncher.apk aligned.apk
   ```

7. **verify** — `apksigner verify --print-certs ApaceLauncher.apk` (v2+v3 OK) + `aapt dump badging`.

Result: `apacemobile/build/ApaceLauncher.apk` — a few hundred KB (only ~0.3 MB of PRoot binaries are embedded; the whole Ubuntu/.NET/Java environment is downloaded at first run, not shipped in the APK).

## CI (GitHub Actions)

The workflow at the repository root, [`.github/workflows/build.yml`](../../.github/workflows/build.yml), runs on every push to `main`, on `v*` tags, and manually (workflow_dispatch). It checks out the repo, sets up Temurin 17, installs the Android SDK (`platforms;android-28` + `build-tools;33.0.2`) via `sdkmanager`, and runs:

```
export SDK="$HOME/android-sdk"
export PROJ="$GITHUB_WORKSPACE/apacemobile"
bash scripts/build_apk.sh
```

The signed APK is uploaded as the artifact **ApaceLauncher** (`apacemobile/build/ApaceLauncher.apk`); on tag pushes it is also attached to a GitHub Release.

## Updating the PRoot assets (when a newer proot is needed)

```bash
# download the debs from the termux repo (aarch64)
curl -O https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_<v>_aarch64.deb
curl -O https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_<v>_aarch64.deb
curl -O https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_<v>_aarch64.deb
python3 scripts/extract_debs.py *.deb out   # extracts data.tar.xz → files
# copy into assets/proot/:
cp out/proot/.../usr/bin/proot            assets/proot/proot
cp out/proot/.../usr/libexec/proot/loader assets/proot/loader
cp out/proot/.../usr/libexec/proot/loader32 assets/proot/loader32
cp out/libtalloc/.../usr/lib/libtalloc.so.2.* assets/proot/libtalloc.so.2
cp out/libandroid-shmem/.../usr/lib/libandroid-shmem.so assets/proot/libandroid-shmem.so
chmod 755 assets/proot/*
# update NOTICE.txt with the new version and rebuild the APK
```

## Updating the .NET / Ubuntu versions

In `src/org/apacemobile/InstallerEngine.java` change the `DOTNET_URL` and `UBUNTU_URL` constants. Current .NET version: `https://builds.dotnet.microsoft.com/dotnet/release-metadata/10.0/releases.json` (`releases[0].aspnetcore-runtime.version`). Ubuntu base: list at `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/`.

## The keystore

The default keystore (`apacemobile/keystore/apacemobile.jks`, password `apacemobile`, alias `apace`) is committed **on purpose**: CI builds and local builds then produce APKs signed with the same key, so updates install over the existing app without wiping data. If you fork the project for a public release of your own, generate your own keystore and keep it safe — every APK update must be signed with the same key, otherwise Android forces an uninstall of the old version (and deletes its data).
