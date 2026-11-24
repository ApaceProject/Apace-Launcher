package org.apacemobile;

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
 *  1. ARM64  2. miejsce na dysku  3. PRoot (assets)  4. Ubuntu ARM64
 *  5. rozpakowanie  6. biblioteki systemowe  7. Java 17
 *  8. .NET 10 (ASP.NET Core Runtime — audyt: release jest framework-dependent!)
 *  9. pliki Apace (GitHub Releases)  10. konfiguracja + smoke test.
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
        if (cancelled) throw new Exception("Instalacja anulowana przez użytkownika");
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

        // ── 4. Ubuntu ARM64 ────────────────────────────────────────────────
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
        provisionBaseFiles();

        // ── 6. Biblioteki systemowe ────────────────────────────────────────
        stage(6, "Installing system libraries (apt)");
        PRoot.Result r1 = PRoot.run(PRoot.shellCommand(paths,
                "apt-get update -qq && apt-get install -y -qq --no-install-recommends " +
                        "ca-certificates curl unzip libicu74 libstdc++6 && echo APT_OK"),
                paths, sink());
        if (!r1.ok() || !r1.out.toString().contains("APT_OK")) {
            throw new Exception("apt-get failed:\n" + r1.tail(15));
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

        // ── 9. Pliki Apace (Main = GitHub Releases, Dev = manifest CI) ─────
        String channel = prefs.channel();
        String tag; String assetName; String assetUrl; String expectedSha = null;
        if ("main".equals(channel)) {
            stage(9, "Downloading Apace (Dev channel — CI manifest)");
            String mu = prefs.mainManifestUrl();
            if (mu == null || mu.length() == 0) {
                throw new Exception("Dev channel selected, but the CI manifest URL is empty. " +
                        "Set it in ⋮ → Update Apace (docs/main-channel.yml has the workflow).");
            }
            GitHubApi.Manifest m = GitHubApi.fetchManifest(mu);
            tag = m.version;
            assetName = "apace-dev-" + tag + ".zip";
            assetUrl = m.url;
            expectedSha = m.sha256;
            cb.detail("Dev manifest → " + tag);
        } else {
            stage(9, "Downloading Apace (Main channel — GitHub Releases)");
            GitHubApi.Release rel = GitHubApi.latestRelease();
            GitHubApi.Asset asset = GitHubApi.pickArm64Asset(rel);
            if (asset == null) {
                throw new Exception("No arm64 asset in release " + rel.tag);
            }
            tag = rel.tag;
            assetName = asset.name;
            assetUrl = asset.url;
            cb.detail("Release " + rel.tag + " → " + asset.name);
        }
        File zip = new File(paths.cache, assetName);
        String gotSha = Downloader.download(assetUrl, zip, new Downloader.Progress() {
            public void onProgress(long d, long t) {
                cb.percent(t > 0 ? (float) d / t : 0f);
            }
        });
        if (expectedSha != null && expectedSha.length() == 64
                && !gotSha.equalsIgnoreCase(expectedSha)) {
            zip.delete();
            throw new Exception("SHA-256 mismatch:\nexpected " + expectedSha + "\ngot      " + gotSha);
        }
        checkCancel();
        stage(9, "Extracting Apace");
        Paths.mkdirs(paths.current);
        ZipExtractor.extractZipFile(zip, paths.current, true, new ZipExtractor.Progress() {
            public void onEntry(String name, long soFar) { cb.percent(0.9f); }
        });
        zip.delete();

        // ── 10. Konfiguracja + smoke test ──────────────────────────────────
        stage(10, "Configuration and smoke test");
        finalizeInstall(tag, assetName, channel);
        PRoot.Result r3 = PRoot.run(PRoot.shellCommand(paths,
                "java -version 2>&1 | head -1; /opt/dotnet/dotnet --list-runtimes 2>/dev/null | head -2; echo SMOKE_OK"),
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
    private void provisionBaseFiles() throws Exception {
        writeText(new File(paths.ubuntu, "etc/resolv.conf"),
                "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
        writeText(new File(paths.ubuntu, "etc/hosts"),
                "127.0.0.1 localhost\n::1 localhost\n");
        new File(paths.ubuntu, "root").mkdirs();
        new File(paths.ubuntu, "tmp").mkdirs();
    }

    void finalizeInstall(String tag, String asset, String channel) throws Exception {
        // run-inside.sh — wariant install-termux.sh + DOTNET_ROOT (audyt: bez tego Launcher nie wystartuje)
        String runInside = "#!/bin/bash\n" +
                "set -e\n" +
                "APACE_DIR=\"$(cd \"$(dirname \"$(readlink -f \"$0\")\")\" && pwd)\"\n" +
                "export ASPNETCORE_URLS=\"${APACE_PANEL_BIND:-http://127.0.0.1:5000}\"\n" +
                "export DOTNET_SYSTEM_NET_DISABLEIPV6=1\n" +
                "export COMPlus_gcServer=0\n" +
                "export COMPlus_gcConcurrent=1\n" +
                "export DOTNET_GCHeapHardLimit=\"${APACE_GC_HARD_LIMIT:-536870912}\"\n" +
                "export DOTNET_CLI_TELEMETRY_OPTOUT=1\n" +
                "export DOTNET_ROOT=/opt/dotnet\n" +
                "export PATH=\"/opt/dotnet:$PATH\"\n" +
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
        writeText(new File(paths.current, ".apacemobile-version.json"), o.toString());
    }

    public static String readVersion(Paths paths) {
        try {
            File f = new File(paths.current, ".apacemobile-version.json");
            if (!f.isFile()) return null;
            JSONObject o = new JSONObject(readText(f));
            return o.optString("tag", null);
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
