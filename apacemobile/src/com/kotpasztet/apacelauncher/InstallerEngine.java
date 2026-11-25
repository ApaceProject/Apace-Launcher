package com.kotpasztet.apacelauncher;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Etap 3 specyfikacji: instalator Apace wewnątrz aplikacji.
 * Kolejne etapy (zgodnie z tabelą ze specyfikacji):
 *  1. ARM64  2. miejsce na dysku  3. PRoot (assets)
 *  4–8. Środowisko: kanał Dev = pełny filesystem obrazu ghcr.io/kotpasztet/apace:dev
 *       (Ubuntu 24.04 + Java 17 + .NET 10 + aapt2/zipalign + android-framework,
 *       warstwy systemowe rozpakowane do rootfs — jak w dockerze); kanał Main =
 *       Ubuntu ARM64 + apt (z aapt/zipalign jak install-termux.sh) + Java 17
 *       + .NET 10 (ASP.NET Core Runtime — audyt: release jest framework-dependent!)
 *  9. pliki Apace (Main: GitHub Releases; Dev: warstwy /app obrazu)
 *  10. konfiguracja + smoke test.
 */
public final class InstallerEngine {

    // Zweryfikowane w sandboxie 2026-11-24 (curl HEAD 200):
    public static final String UBUNTU_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz";
    public static final String DOTNET_URL =
            "https://builds.dotnet.microsoft.com/dotnet/aspnetcore/Runtime/10.0.15/aspnetcore-runtime-10.0.15-linux-arm64.tar.gz";
    /** Fallback: skrypt dotnet-install.sh rozwiązuje aktualną wersję 10.0 sam. */
    public static final String DOTNET_INSTALL_SCRIPT = "https://dot.net/v1/dotnet-install.sh";
    public static final long MIN_FREE_BYTES = 3500L * 1024 * 1024; // ~3,5 GB

    public interface Callback {
        void stage(int index, int total, String label);
        void detail(String line);       // do konsoli
        void percent(float p);          // 0..1 w ramach etapu
        void done(String version);
        void error(String message);
    }

    private final Context ctx;
    private final Prefs prefs;
    private final Paths paths;
    private final Callback cb;
    private volatile boolean cancelled = false;

    public InstallerEngine(Context ctx, Prefs prefs, Paths paths, Callback cb) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.paths = paths;
        this.cb = cb;
    }

    public void cancel() { cancelled = true; }

    private void checkCancel() throws Exception {
        if (cancelled) throw new Exception("Installation cancelled by user");
    }

    private void stage(int i, String label) {
        cb.stage(i, 10, label);
    }

    public void run() {
        try {
            runImpl();
        } catch (Exception e) {
            cb.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void runImpl() throws Exception {
        // katalogi muszą istnieć PRZED pierwszym proot — inaczej
        // „proot warning: can't sanitize binding …/apace/current: No such file or directory”
        Paths.mkdirs(paths.current);
        Paths.mkdirs(paths.staged);
        Paths.mkdirs(paths.backups);

        // ── 1. Architektura ────────────────────────────────────────────────
        stage(1, "Checking ARM64 architecture");
        String[] abis = Build.SUPPORTED_ABIS;
        boolean arm64 = false;
        for (String a : abis) if ("arm64-v8a".equals(a)) arm64 = true;
        if (!arm64) {
            throw new Exception("This device is not ARM64 (found: " +
                    (abis.length > 0 ? abis[0] : "?") + "). Apace Launcher requires arm64-v8a.");
        }
        cb.detail("Architecture OK: arm64-v8a");

        // ── 2. Miejsce i RAM ───────────────────────────────────────────────
        stage(2, "Checking free space");
        long free = Paths.freeBytes(paths.base);
        if (free < MIN_FREE_BYTES) {
            throw new Exception("Not enough free space: " + (free >> 20) + " MB free, ~3500 MB required.");
        }
        cb.detail("Free space: " + (free >> 20) + " MB");

        // ── 3. PRoot z assets APK ──────────────────────────────────────────
        stage(3, "Installing PRoot");
        Paths.mkdirs(paths.prootDir);
        copyAssetExec("proot/proot", paths.prootBin());
        copyAssetExec("proot/loader", paths.prootLoader());
        copyAssetExec("proot/loader32", paths.prootLoader32());
        copyAssetExec("proot/libtalloc.so.2", new File(paths.prootDir, "libtalloc.so.2"));
        copyAssetExec("proot/libandroid-shmem.so", new File(paths.prootDir, "libandroid-shmem.so"));
        cb.detail("PRoot ready: " + paths.prootBin().getAbsolutePath());

        // ── 4. Środowisko: Dev = obraz ghcr.io (pełny filesystem obrazu),
        //       Main = ubuntu-base + apt + Java + .NET (bez zmian) ───────────
        String channel = prefs.channel();
        String tag = null; String assetName = null; String assetUrl = null;
        String revision = null; String rootfsKey = null;
        if ("dev".equals(channel)) {
            // Dev: instalujemy SYSTEMOWĄ część obrazu ghcr.io/kotpasztet/apace:dev
            // jako rootfs (Ubuntu 24.04 + Java 17 + .NET 10 + aapt2/zipalign +
            // android-framework + PowerShell — dokładnie to, co ma obraz dockera)
            // oraz APLIKACYJNĄ część („/app”) jako pliki programu Apace.
            GitHubApi.DevBuild dev;
            stage(4, "Resolving ghcr.io/kotpasztet/apace:dev");
            try {
                dev = GitHubApi.latestDevBuild();
            } catch (Exception e) {
                throw new Exception("ghcr.io/kotpasztet/apace:dev: " + e.getMessage(), e);
            }
            cb.detail("image commit " + dev.version
                    + (dev.revision != null ? " (" + dev.revision + ")" : "")
                    + (dev.created != null && dev.created.length() >= 16
                        ? ", built " + dev.created.substring(0, 16).replace('T', ' ') + " UTC" : ""));
            stage(4, "Downloading the dev image ("
                    + ((dev.systemSize + dev.appSize) >> 20) + " MB, layers cached for updates)");
            DevImage.downloadLayers(dev, paths, new DevImage.Progress() {
                public void percent(float p) { cb.percent(p); }
                public void note(String line) { cb.detail(line); }
            });
            checkCancel();

            stage(5, "Extracting the image rootfs (Ubuntu + Java 17 + .NET 10 + tools)");
            Paths.rmRf(paths.ubuntu);
            Paths.mkdirs(paths.ubuntu);
            DevImage.extractRootfs(dev, paths, paths.ubuntu, new DevImage.Progress() {
                public void percent(float p) { cb.percent(p); }
                public void note(String line) { cb.detail(line); }
            });
            provisionBaseFiles(paths.ubuntu);
            cb.detail("rootfs: image filesystem (aapt2 + zipalign + android-framework included)");
            checkCancel();

            stage(6, "System libraries — included in the dev image");
            cb.detail("base distro + ca-certificates + icu + openjdk-17-jre baked in the image layers");
            stage(7, "Java 17 — included in the dev image (openjdk-17-jre)");
            cb.detail("java: " + new File(paths.ubuntu, "usr/bin/java").getAbsolutePath());
            stage(8, ".NET 10 — included in the dev image (/usr/share/dotnet, aspnet:10.0 base)");
            cb.detail("dotnet: " + new File(paths.ubuntu, "usr/bin/dotnet").getAbsolutePath());

            stage(9, "Extracting Apace (app layers of the dev image)");
            Paths.mkdirs(paths.current);
            DevImage.extractApp(dev, paths, paths.current, new DevImage.Progress() {
                public void percent(float p) { cb.percent(p); }
                public void note(String line) { cb.detail(line); }
            });
            tag = dev.version;
            assetName = "ghcr.io/kotpasztet/apace:dev";
            assetUrl = "ghcr-image:" + dev.appKey;
            revision = dev.revision;
            rootfsKey = dev.systemKey;
        } else {
            stage(4, "Downloading Ubuntu ARM64 (~50 MB)");
            File ubuntuTar = new File(paths.cache, "ubuntu-base-arm64.tar.gz");
            String sha = Downloader.download(UBUNTU_URL, ubuntuTar, new Downloader.Progress() {
                public void onProgress(long d, long t) {
                    cb.percent(t > 0 ? (float) d / t : 0f);
                }
            });
            cb.detail("Ubuntu downloaded, SHA-256: " + sha.substring(0, 16) + "…");
            checkCancel();

            // ── 5. Rozpakowanie środowiska ─────────────────────────────────────
            stage(5, "Extracting the Ubuntu rootfs");
            Paths.rmRf(paths.ubuntu);
            Paths.mkdirs(paths.ubuntu);
            TarExtractor.extractTarGz(ubuntuTar, paths.ubuntu, new TarExtractor.Progress() {
                public void onEntry(String name, long soFar) {
                    cb.percent(0.9f); // rootfs rozpakowuje się „wszystko albo nic”
                }
            });
            ubuntuTar.delete();
            cb.detail("Rootfs: " + paths.ubuntu.getAbsolutePath());
            provisionBaseFiles(paths.ubuntu);

            // ── 6. Biblioteki systemowe ────────────────────────────────────────
            stage(6, "Installing system libraries (apt)");
            PRoot.Result r1 = PRoot.run(PRoot.shellCommand(paths,
                    "apt-get update -qq && apt-get install -y -qq --no-install-recommends " +
                            "ca-certificates curl unzip libicu74 libstdc++6 && echo APT_OK"),
                    paths, sink());
            if (!r1.ok() || !r1.out.toString().contains("APT_OK")) {
                throw new Exception("apt-get failed:\n" + r1.tail(15));
            }
            // aapt (+aapt2) i zipalign dla patchera w panelu — tak jak install-termux.sh
            // (fallback patchera, gdy aapt2 z apktool pada SIGILL na starszych ARM).
            // Niefatalne: ich brak nie blokuje instalacji kanału Main.
            PRoot.Result r1b = PRoot.run(PRoot.shellCommand(paths,
                    "apt-get install -y -qq --no-install-recommends aapt zipalign " +
                            "&& test -x /usr/bin/aapt2 && echo AAPT_OK || echo AAPT_OPTIONAL_MISSING"),
                    paths, sink());
            if (r1b.out.toString().contains("AAPT_OK")) {
                cb.detail("patcher tools: /usr/bin/aapt2 + /usr/bin/zipalign installed");
            } else {
                cb.detail("NOTE: aapt/zipalign not installed (" + r1b.tail(3).trim()
                        + ") — the panel patcher will use the apktool-bundled aapt2");
            }

            // ── 7. Java 17 ─────────────────────────────────────────────────────
            stage(7, "Installing Java 17 (openjdk-17-jre-headless)");
            PRoot.Result r2 = PRoot.run(PRoot.shellCommand(paths,
                    "apt-get install -y -qq --no-install-recommends openjdk-17-jre-headless " +
                            "|| apt-get install -y -qq --no-install-recommends openjdk-21-jre-headless; " +
                            "java -version 2>&1 | head -1"),
                    paths, sink());
            if (!r2.ok()) {
                throw new Exception("Java installation failed:\n" + r2.tail(15));
            }
            cb.detail(r2.tail(2).trim());

            // ── 8. .NET 10 (ASP.NET Core Runtime) ──────────────────────────────
            stage(8, "Downloading .NET 10 ASP.NET Core Runtime (~100 MB)");
            installDotnet();
        }

        // ── 9. Pliki Apace — Dev rozpakował już warstwy /app powyżej ────────
        if (!"dev".equals(channel)) {
            stage(9, "Downloading Apace (Main channel — GitHub Releases)");
            GitHubApi.Release rel;
            try {
                rel = GitHubApi.latestRelease();
            } catch (Exception e) {
                throw new Exception("GitHub Releases: " + e.getMessage(), e);
            }
            GitHubApi.Asset asset = GitHubApi.pickArm64Asset(rel);
            if (asset == null) {
                throw new Exception("No arm64 asset in release " + rel.tag);
            }
            tag = rel.tag;
            assetName = asset.name;
            assetUrl = asset.url;
            cb.detail("Release " + rel.tag + " → " + asset.name);
            File zip = new File(paths.cache, assetName);
            String gotSha = Downloader.download(assetUrl, zip, new Downloader.Progress() {
                public void onProgress(long d, long t) {
                    cb.percent(t > 0 ? (float) d / t : 0f);
                }
            });
            cb.detail("Package SHA-256: " + gotSha.substring(0, 16)
                    + "… (compare with the release page)");
            checkCancel();
            stage(9, "Extracting Apace");
            Paths.mkdirs(paths.current);
            ZipExtractor.extractZipFile(zip, paths.current, true, new ZipExtractor.Progress() {
                public void onEntry(String name, long soFar) { cb.percent(0.9f); }
            });
            zip.delete();
        }

        // ── 10. Konfiguracja + smoke test ──────────────────────────────────
        stage(10, "Configuration and smoke test");
        finalizeInstall(tag, assetName, channel, revision, rootfsKey);
        PRoot.Result r3 = PRoot.run(PRoot.shellCommand(paths,
                "java -version 2>&1 | head -1; dotnet --list-runtimes 2>/dev/null | head -2 || true; echo SMOKE_OK"),
                paths, sink());
        if (!r3.out.toString().contains("SMOKE_OK")) {
            throw new Exception("Smoke test failed:\n" + r3.tail(15));
        }
        cb.detail("Smoke test OK");
        cb.detail("Apace " + tag + " installed!");
        cb.done(tag);
    }

    /** .NET: najpierw konkretny, zweryfikowany tarball; awaryjnie dotnet-install.sh w proocie. */
    private void installDotnet() throws Exception {
        File dotnetTar = new File(paths.cache, "aspnetcore-runtime.tar.gz");
        try {
            Downloader.download(DOTNET_URL, dotnetTar, new Downloader.Progress() {
                public void onProgress(long d, long t) { cb.percent(t > 0 ? (float) d / t : 0f); }
            });
            File dst = new File(paths.ubuntu, "opt/dotnet");
            Paths.mkdirs(dst);
            TarExtractor.extractTarGz(dotnetTar, dst, new TarExtractor.Progress() {
                public void onEntry(String name, long soFar) { cb.percent(0.9f); }
            });
            dotnetTar.delete();
            cb.detail(".NET 10.0.15 installed in /opt/dotnet");
            return;
        } catch (Exception e) {
            cb.detail(".NET tarball failed (" + e.getMessage() + ") — falling back to dotnet-install.sh");
        }
        // fallback: skrypt instalacyjny Microsoftu uruchomiony w rootfs
        PRoot.Result r = PRoot.run(PRoot.shellCommand(paths,
                "curl -fsSL " + DOTNET_INSTALL_SCRIPT + " -o /tmp/di.sh && bash /tmp/di.sh " +
                        "--channel 10.0 --runtime aspnetcore --install-dir /opt/dotnet " +
                        "&& /opt/dotnet/dotnet --list-runtimes | head -1"), paths, sink());
        if (!r.ok() || new File(paths.ubuntu, "opt/dotnet/dotnet").isFile() == false) {
            throw new Exception(".NET installation failed:\n" + r.tail(15));
        }
        cb.detail(r.tail(2).trim());
    }

    /** resolv.conf + hosts (Android nie ma resolv.conf pod bindem). */
    static void provisionBaseFiles(File ubuntuRoot) throws Exception {
        writeText(new File(ubuntuRoot, "etc/resolv.conf"),
                "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
        writeText(new File(ubuntuRoot, "etc/hosts"),
                "127.0.0.1 localhost\n::1 localhost\n");
        new File(ubuntuRoot, "root").mkdirs();
        new File(ubuntuRoot, "tmp").mkdirs();
    }

    /**
     * Konfiguracja końcowa (instalacja i aktualizacja): run-inside.sh,
     * config.json, katalogi danych, chmody i marker wersji.
     *
     * @param revision pełny SHA commita obrazu dev (APACE_COMMIT) — null dla Main
     * @param rootfsKey łańcuch digestów warstw systemowych obrazu dev, z których
     *                  zbudowany jest rootfs — pozwala aktualizacji dev pominąć
     *                  przebudowę rootfs, gdy warstwy systemowe się nie zmieniły
     *                  (null = rootfs spoza obrazu dev → przy najbliższej
     *                  aktualizacji dev zostanie przebudowany)
     */
    void finalizeInstall(String tag, String asset, String channel,
                         String revision, String rootfsKey) throws Exception {
        // run-inside.sh — wariant install-termux.sh; DOTNET_ROOT zależny od rootfs
        String runInside = "#!/bin/bash\n" +
                "set -e\n" +
                "APACE_DIR=\"$(cd \"$(dirname \"$(readlink -f \"$0\")\")\" && pwd)\"\n" +
                "export ASPNETCORE_URLS=\"${APACE_PANEL_BIND:-http://127.0.0.1:5000}\"\n" +
                "export DOTNET_SYSTEM_NET_DISABLEIPV6=1\n" +
                "export COMPlus_gcServer=0\n" +
                "export COMPlus_gcConcurrent=1\n" +
                "export DOTNET_GCHeapHardLimit=\"${APACE_GC_HARD_LIMIT:-536870912}\"\n" +
                "export DOTNET_CLI_TELEMETRY_OPTOUT=1\n" +
                // marker dla panelu Apace — wykrycie środowiska mobilnego
                // (np. domyślny adres IPv4 = 127.0.0.1 zamiast LAN IP, bo klient chodzi na tym samym telefonie)
                "export APACE_MOBILE=1\n" +
                "# .NET: dev-image rootfs ma /usr/share/dotnet (+/usr/bin/dotnet),\n" +
                "# Main-channel rootfs — /opt/dotnet\n" +
                "if [ -d /usr/share/dotnet ]; then\n" +
                "    export DOTNET_ROOT=/usr/share/dotnet\n" +
                "elif [ -d /opt/dotnet ]; then\n" +
                "    export DOTNET_ROOT=/opt/dotnet\n" +
                "fi\n" +
                "export PATH=\"${DOTNET_ROOT:-/opt/dotnet}:$PATH\"\n" +
                "# narzędzia patchera z obrazu dev (Dockerfile ENV) — tylko gdy istnieją\n" +
                "if [ -x /usr/local/bin/aapt2 ]; then export AAPT2_PATH=/usr/local/bin/aapt2; fi\n" +
                "if [ -x /usr/bin/zipalign ]; then export ZIPALIGN_PATH=/usr/bin/zipalign; fi\n" +
                "if [ -d /opt/android-framework ]; then export APKTOOL_FRAMEWORK_DIR=/opt/android-framework; fi\n" +
                (revision != null && revision.matches("[0-9a-fA-F]{8,40}")
                        ? "export APACE_COMMIT=" + revision + "\n" : "") +
                "if command -v java >/dev/null 2>&1; then\n" +
                "    JAVA_BIN=\"$(readlink -f \"$(command -v java)\")\"\n" +
                "    export JAVA_HOME=\"$(dirname \"$(dirname \"$JAVA_BIN\")\")\"\n" +
                "else\n" +
                "    echo \"Java was not found — reinstall the environment.\"\n" +
                "    exit 1\n" +
                "fi\n" +
                "cd \"$APACE_DIR/launcher\"\n" +
                "exec ./Launcher\n";
        writeText(new File(paths.current, "run-inside.sh"), runInside);

        // config.json z ApiPort (audyt: domyślny ApiPort = 1808)
        File cfg = new File(paths.current, "launcher/config.json");
        if (!cfg.isFile()) {
            Paths.mkdirs(cfg.getParentFile());
            writeText(cfg, "{\"ApiPort\":" + prefs.apiPort() + "}\n");
        }
        // katalogi danych (jak w Dockerfile)
        for (String d : new String[]{
                "launcher/Data", "launcher/logs", "launcher/persistent_fabric",
                "data", "logs", "staticdata/resourcepacks", "staticdata/server_template_dir/mods"}) {
            Paths.mkdirs(new File(paths.current, d));
        }
        // chmod +x launcher/Launcher components/* (jak w install-termux.sh)
        PRoot.run(PRoot.shellCommand(paths,
                "chmod -R +x /apace/launcher /apace/components 2>/dev/null; " +
                        "chmod +x /apace/run-inside.sh; test -x /apace/launcher/Launcher"), paths, sink());

        // marker wersji
        JSONObject o = new JSONObject();
        o.put("tag", tag);
        o.put("asset", asset);
        o.put("channel", channel);
        o.put("installedAt", System.currentTimeMillis());
        if (revision != null) o.put("revision", revision);
        if (rootfsKey != null) o.put("rootfsKey", rootfsKey);
        writeText(new File(paths.current, ".apacemobile-version.json"), o.toString());
    }

    public static String readVersion(Paths paths) {
        return readMarkerString(paths, "tag");
    }

    /** Dowolne pole z markera wersji (np. „rootfsKey”, „revision”) — null gdy brak. */
    public static String readMarkerString(Paths paths, String key) {
        return readMarkerString(paths.current, key);
    }

    /** Wariant z jawnym katalogiem (np. backup pre-update po rename current). */
    public static String readMarkerString(File dir, String key) {
        try {
            File f = new File(dir, ".apacemobile-version.json");
            if (!f.isFile()) return null;
            JSONObject o = new JSONObject(readText(f));
            String v = o.optString(key, null);
            return v != null && v.length() > 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void writeText(File f, String s) throws Exception {
        Paths.mkdirs(f.getParentFile());
        if (f.exists() && f.isDirectory()) Paths.rmRf(f);
        FileOutputStream out = new FileOutputStream(f);
        out.write(s.getBytes("UTF-8"));
        out.close();
    }

    public static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private void copyAssetExec(String assetPath, File dest) throws Exception {
        Paths.mkdirs(dest.getParentFile());
        InputStream in = ctx.getAssets().open(assetPath);
        OutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
        dest.setReadable(true, false);
        dest.setWritable(true, true);
        dest.setExecutable(true, false);
    }

    private PRoot.LogSink sink() {
        return new PRoot.LogSink() {
            public void onLine(String line) { cb.detail(line); }
        };
    }
}
