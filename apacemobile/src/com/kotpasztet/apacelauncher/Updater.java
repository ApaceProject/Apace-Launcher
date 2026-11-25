package com.kotpasztet.apacelauncher;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aktualizacje Apace (specyfikacja 7): kanał Stable/Main = GitHub Releases,
 * kanał Dev = obraz ghcr.io/apaceproject/apace:dev (anonimowy pull OCI).
 *
 * Od 1.1.8 aktualizacja Dev stosuje PEŁNY filesystem obrazu:
 *  - warstwy systemowe (Ubuntu 24.04 + Java 17 + .NET 10 + aapt2/zipalign +
 *    android-framework + PowerShell) → rootfs (paths.ubuntu), z pominięciem
 *    warstwy docker.io (na telefonie bezużytecznej) i katalogu /app;
 *  - warstwy aplikacyjne (cała zawartość /app: binarka, compose/, defaults/,
 *    entrypoint, chmody) → katalog programu (paths.current = /apace);
 *  - dane użytkownika (jak dotychczas) wracają z backupu; dodatkowo
 *    zachowywane są klucze ASP.NET DataProtection (sesje przeżywają update,
 *    jak przy volume w docker-compose) i resourcepacks.
 * Jeśli warstwy systemowe obrazu nie zmieniły się od poprzedniej instalacji
 * („rootfsKey” w markerze wersji), rootfs NIE jest przebudowywany — szybka
 * ścieżka odświeża tylko pliki /app, jak dotychczas.
 *
 * Przed podmianą plików tworzony jest pełny backup przez RENAME
 * (natychmiastowy), z rollbackiem obu katalogów przy błędzie.
 */
public final class Updater {

    /** Auto-recheck interval (dev: CI has not built HEAD yet) — shared with MainActivity. */
    public static final long DEV_RECHECK_MS = 3 * 60 * 1000L;

    public interface ProgressListener {
        void phase(String label);
        void percent(float p);
    }

    private final Context ctx;
    private final Prefs prefs;
    private final Paths paths;
    private final PRoot.LogSink sink;

    public Updater(Context ctx, Prefs prefs, Paths paths, PRoot.LogSink sink) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.paths = paths;
        this.sink = sink;
    }

    // ── Sprawdzanie aktualizacji ───────────────────────────────────────────

    /** Stan sprawdzenia — UI nigdy nie pokazuje „nic” (nigdy ciche „brak”). */
    public static final int OK_UPTODATE = 0;   // zainstalowana wersja = najnowsza
    public static final int OK_AVAILABLE = 1;  // jest nowsza wersja do pobrania
    public static final int OK_CI_PENDING = 2; // obraz starszy niż HEAD dev (CI buduje)
    public static final int ERROR = 3;         // sprawdzenie nie udało się (np. limit API)

    public static class CheckResult {
        public int status = ERROR;
        public boolean available;       // == (status == OK_AVAILABLE)
        public String current;
        public String latest;
        public String assetUrl;
        public String sha256;
        public String message;
        public String headShort;        // dev: krótki SHA HEAD brancha dev (gdy znany)
        public boolean headUnavailable; // dev: HEAD niedostępny (np. limit API)
        public boolean ciPending;       // dev: obraz starszy niż HEAD (CI nie zbudował)
    }

    /** Cache wyników sprawdzenia (auto-checki) — TTL krótszy niż auto-recheck. */
    private static final Object CACHE_LOCK = new Object();
    private static CheckResult cachedResult;
    private static String cachedChannel;
    private static long cachedAt;
    private static final long CACHE_TTL_MS = 4 * 60 * 1000L;

    /** Sprawdzenie z cache (dla auto-checków) — live najwyżej raz na 4 minuty. */
    public CheckResult check() {
        String channel = prefs.channel();
        synchronized (CACHE_LOCK) {
            if (cachedResult != null && cachedResult.status != ERROR
                    && channel != null && channel.equals(cachedChannel)
                    && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                return cachedResult;
            }
        }
        CheckResult r = doCheck();
        if (r.status != ERROR) {
            synchronized (CACHE_LOCK) {
                cachedResult = r;
                cachedChannel = channel;
                cachedAt = System.currentTimeMillis();
            }
        }
        return r;
    }

    /** Sprawdzenie na żywo (przycisk „Check for updates”, auto-recheck CI). */
    public CheckResult checkNow() {
        CheckResult r = doCheck();
        if (r.status != ERROR) {
            synchronized (CACHE_LOCK) {
                cachedResult = r;
                cachedChannel = prefs.channel();
                cachedAt = System.currentTimeMillis();
            }
        }
        return r;
    }

    private CheckResult doCheck() {
        CheckResult r = new CheckResult();
        r.current = InstallerEngine.readVersion(paths);
        try {
            if ("dev".equals(prefs.channel())) {
                // Kanał Dev: najnowszy obraz ghcr.io/apaceproject/apace:dev
                GitHubApi.DevBuild dev = GitHubApi.latestDevBuild();
                r.latest = dev.version;
                r.assetUrl = "ghcr-image:" + dev.appKey;
                StringBuilder m = new StringBuilder(
                        "Source: ghcr.io/apaceproject/apace:dev\nImage commit: ").append(dev.version);
                if (dev.created != null && dev.created.length() >= 16) {
                    m.append(" (built ").append(dev.created.substring(0, 16).replace('T', ' '))
                            .append(" UTC)");
                }
                // stan rootfs względem obrazu (czy update przebuduje środowisko)
                String installedRootfsKey = InstallerEngine.readMarkerString(paths, "rootfsKey");
                if (installedRootfsKey == null) {
                    m.append("\nRootfs: will be rebuilt from the image on update "
                            + "(system tools like aapt2 will be installed)");
                } else if (!installedRootfsKey.equals(dev.systemKey)) {
                    m.append("\nRootfs: image system layers changed — the environment "
                            + "will be refreshed on update");
                }
                try {
                    GitHubApi.DevCommit head = GitHubApi.latestDevCommit();
                    String headShort = head.sha.substring(0, Math.min(12, head.sha.length()));
                    r.headShort = headShort;
                    m.append("\nDev branch HEAD: ").append(headShort);
                    boolean imageHasHead = dev.revision != null && dev.revision.length() >= 12
                            && head.sha.regionMatches(true, 0, dev.revision, 0, 12);
                    long builtAt = dev.created != null ? parseIso(dev.created) : -1;
                    long headAt = head.date != null ? parseIso(head.date) : -1;
                    if (imageHasHead) {
                        m.append("\nImage matches the latest commit.");
                    } else if (builtAt >= 0 && headAt >= 0 && builtAt >= headAt) {
                        m.append("\nImage was built after the latest commit — up to date.");
                    } else {
                        r.ciPending = true;
                        m.append("\nCI has NOT built ").append(headShort)
                                .append(" yet — the newest image is older than the dev branch HEAD.")
                                .append("\nThe launcher re-checks automatically every ")
                                .append(DEV_RECHECK_MS / 60000L).append(" min while it is open.");
                    }
                } catch (Exception e) {
                    r.headUnavailable = true;
                    m.append("\n(dev branch HEAD unavailable: ").append(e.getMessage()).append(")");
                }
                r.message = m.toString();
            } else {
                // Kanał Main: najnowszy stabilny release z GitHub Releases.
                GitHubApi.Release rel = GitHubApi.latestRelease();
                GitHubApi.Asset a = GitHubApi.pickArm64Asset(rel);
                r.latest = rel.tag;
                if (a != null) r.assetUrl = a.url;
                r.message = "Source: GitHub Releases (" + rel.assets.size() + " files)";
            }
            r.available = r.current == null || r.latest == null
                    || !r.current.equals(r.latest);
            r.status = r.available ? OK_AVAILABLE
                    : (r.ciPending ? OK_CI_PENDING : OK_UPTODATE);
        } catch (Exception e) {
            r.status = ERROR;
            r.available = false;
            r.message = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        return r;
    }

    // ── Stosowanie aktualizacji ────────────────────────────────────────────

    /**
     * Pełny przepływ (specyfikacja 7.2): stop → download → backup(rename) →
     * podmiana plików → przywrócenie danych → smoke test → rollback przy błędzie.
     * Serwer jest zatrzymywany przez ApaceService PRZED wywołaniem run().
     */
    public void run(String assetUrl, String version, String sha256, ProgressListener cb) throws Exception {
        if (!paths.isInstalled()) throw new Exception("Apace is not installed — run the installer first.");
        if (assetUrl == null) throw new Exception("Missing update package URL.");
        if (assetUrl.startsWith("ghcr-image:")) {
            runDevUpdate(assetUrl.substring("ghcr-image:".length()), version, cb);
            return;
        }
        if (assetUrl.startsWith("ghcr-blob:")) {
            throw new Exception("Legacy dev update link — run a fresh update check "
                    + "(this version changed how dev updates are applied).");
        }
        runMainUpdate(assetUrl, version, sha256, cb);
    }

    /** Kanał Main: zip z GitHub Releases → tylko pliki programu (rootfs bez zmian). */
    private void runMainUpdate(String assetUrl, String version, String sha256,
                               ProgressListener cb) throws Exception {
        // 1. Kopia zapasowa przez rename (natychmiastowa, pełna: program + dane)
        cb.phase("Backing up before update");
        Paths.mkdirs(paths.staged);
        Paths.mkdirs(paths.backups);
        File preUpdate = new File(paths.staged, "pre-update-app-" + System.currentTimeMillis());
        if (!paths.current.renameTo(preUpdate)) {
            throw new Exception("Could not create the backup (rename current→staged blocked).");
        }
        // rootfsKey trzeba przeczytać Z BACKUPU — katalog current jest już pusty
        // (rename), a marker niesie informację o tym, z jakich warstw zbudowany
        // jest rootfs (przetrwa update kanału Main bez przebudowy).
        final String keepRootfsKey = InstallerEngine.readMarkerString(preUpdate, "rootfsKey");
        try {
            // 2. Pobranie nowej wersji do katalogu tymczasowego
            cb.phase("Downloading " + version);
            File zip = new File(paths.cache, "apace-update-" + System.currentTimeMillis() + ".zip");
            String gotSha = Downloader.download(assetUrl, zip, new Downloader.Progress() {
                public void onProgress(long d, long t) { cb.percent(t > 0 ? (float) d / t : 0f); }
            });
            if (sha256 != null && sha256.length() == 64) {
                String real = Downloader.sha256(zip);
                if (!real.equalsIgnoreCase(sha256)) {
                    zip.delete();
                    throw new Exception("SHA-256 mismatch:\nexpected " + sha256 + "\ngot      " + real);
                }
                cb.phase("SHA-256 OK");
            } else {
                cb.phase("Package SHA-256: " + gotSha.substring(0, 16) + "… (compare with the release page)");
            }

            // 3. Podmiana plików programu (wykluczenia danych — jak install-termux.sh)
            cb.phase("Extracting the new version");
            Paths.mkdirs(paths.current);
            ZipExtractor.extractZipFile(zip, paths.current, true, new ZipExtractor.Progress() {
                public void onEntry(String name, long soFar) { cb.percent(0.95f); }
            });
            zip.delete();

            // 4. Przywrócenie danych użytkownika z backupu
            cb.phase("Restoring user data");
            restoreDataDirs(preUpdate, paths.current);

            // 5. Konfiguracja + marker wersji (rootfsKey z backupu — rootfs nietknięty)
            cb.phase("Configuration");
            new InstallerEngine(ctx, prefs, paths, noop())
                    .finalizeInstall(version, assetUrl, "main", null, keepRootfsKey);
            // 6. Test uruchomienia środowiska
            PRoot.Result smoke = PRoot.run(PRoot.shellCommand(paths,
                    "test -x /apace/launcher/Launcher && echo SMOKE_OK"), paths, null);
            if (!smoke.out.toString().contains("SMOKE_OK")) {
                throw new Exception("After the update there is no executable launcher:\n" + smoke.tail(10));
            }

            // 7. Rotacja backupów (trzymamy 2)
            pruneStaged();
        } catch (final Exception e) {
            // 8. Rollback
            cb.phase("ROLLBACK: restoring the previous version");
            Paths.rmRf(paths.current);
            if (!preUpdate.renameTo(paths.current)) {
                // fallback kopiowania, gdyby rename się nie udał
                try {
                    Paths.mkdirs(paths.current);
                    Paths.copyTree(preUpdate, paths.current);
                    Paths.rmRf(preUpdate);
                } catch (Exception e2) {
                    throw new Exception("ROLLBACK FAILED: " + e2.getMessage() + " (backup: " + preUpdate + ")", e);
                }
            }
            throw e;
        }
    }

    /**
     * Kanał Dev: pełny filesystem obrazu ghcr.io/apaceproject/apace:dev.
     * Warstwy systemowe → rootfs (z pominięciem docker.io i /app), warstwy /app
     * → katalog programu. Gdy „rootfsKey” z markera zgadza się z warstwami
     * systemowymi obrazu, rootfs nie jest przebudowywany (tylko /app).
     */
    private void runDevUpdate(String expectedAppKey, String version, ProgressListener cb) throws Exception {
        // 1. Rozwiązanie obrazu na żywo (świeży token ghcr)
        cb.phase("Resolving ghcr.io/apaceproject/apace:dev");
        GitHubApi.DevBuild img = GitHubApi.latestDevBuild();
        if (expectedAppKey != null && expectedAppKey.length() > 0
                && !expectedAppKey.equals(img.appKey)) {
            throw new Exception("The dev image changed while you were reviewing it "
                    + "(a newer build was published). Run the update check again — "
                    + "it will pick up the newest build.");
        }

        // 1b. Odzyskanie rootfs po przerwanej aktualizacji (proces zabity w trakcie)
        recoverStagedRootfsIfNeeded();

        // 1c. Czy rootfs trzeba przebudować?
        String installedRootfsKey = InstallerEngine.readMarkerString(paths, "rootfsKey");
        // lstat (Paths.runtimePresent), nie isFile(): /usr/bin/java i
        // /usr/bin/dotnet w rootfs dev-image to symlinki z ABSOLUTNYMI celami,
        // które poza PRoot wyglądają na zerwane — isFile() kazałoby przebudować
        // zdrowy rootfs przy KAŻDEJ aktualizacji dev (rootfsKey nigdy nie działa).
        boolean rootfsBroken = !Paths.runtimePresent(paths.ubuntu);
        boolean rebuild = rootfsBroken || installedRootfsKey == null
                || !installedRootfsKey.equals(img.systemKey);

        // 1d. Miejsce na dysku (przebudowa rootfs ≈ dodatkowa kopia środowiska)
        long need = rebuild
                ? img.systemSize * 16 / 5 + 300L * 1024 * 1024
                : img.appSize * 3 + 300L * 1024 * 1024;
        ensureFreeSpace(need);

        // 2. Pobranie warstw (nic destrukcyjnego; cache po digeście — zwykle
        //    pobiera się tylko zmieniona warstwa /app)
        cb.phase("Downloading image layers ("
                + ((img.systemSize + img.appSize) >> 20) + " MB compressed; unchanged layers are cached)");
        DevImage.downloadLayers(img, paths, new DevImage.Progress() {
            public void percent(float p) { cb.percent(p); }
            public void note(String line) { if (sink != null) sink.onLine("[update] " + line); }
        });

        // 3. Backup programu przez rename
        cb.phase("Backing up before update");
        Paths.mkdirs(paths.staged);
        Paths.mkdirs(paths.backups);
        File preApp = new File(paths.staged, "pre-update-app-" + System.currentTimeMillis());
        if (!paths.current.renameTo(preApp)) {
            throw new Exception("Could not create the backup (rename current→staged blocked).");
        }
        File preRootfs = null;
        try {
            if (rebuild) {
                // 4a. Backup rootfs + rozpakowanie warstw systemowych obrazu
                cb.phase("Backing up the old rootfs");
                preRootfs = new File(paths.staged, "pre-update-rootfs-" + System.currentTimeMillis());
                if (preRootfs.isDirectory()) Paths.rmRf(preRootfs); // reszta po przerwanej próbie
                if (paths.ubuntu.isDirectory() && !paths.ubuntu.renameTo(preRootfs)) {
                    throw new Exception("Could not move the old rootfs to the backup directory.");
                }
                cb.phase("Extracting the image rootfs (Ubuntu + Java 17 + .NET 10 + aapt2 + tools)");
                Paths.mkdirs(paths.ubuntu);
                DevImage.extractRootfs(img, paths, paths.ubuntu, new DevImage.Progress() {
                    public void percent(float p) { cb.percent(p); }
                    public void note(String line) { if (sink != null) sink.onLine("[update] " + line); }
                });
                InstallerEngine.provisionBaseFiles(paths.ubuntu);
                // 4b. Klucze ASP.NET DataProtection — sesje przeżywają update
                //     (w docker-compose to wolumen /root/.aspnet/DataProtection-Keys)
                cb.phase("Restoring ASP.NET data-protection keys");
                if (preRootfs.isDirectory()) {
                    restoreDir(new File(preRootfs, "root/.aspnet/DataProtection-Keys"),
                            new File(paths.ubuntu, "root/.aspnet/DataProtection-Keys"));
                }
            } else {
                cb.phase("Rootfs already matches the image — refreshing app files only");
            }

            // 5. Pliki programu z warstw /app (prefiks „app/” zdejmowany)
            cb.phase("Extracting the new app files");
            Paths.mkdirs(paths.current);
            DevImage.extractApp(img, paths, paths.current, new DevImage.Progress() {
                public void percent(float p) { cb.percent(p); }
                public void note(String line) { if (sink != null) sink.onLine("[update] " + line); }
            });

            // 6. Dane użytkownika z backupu
            cb.phase("Restoring user data");
            restoreDataDirs(preApp, paths.current);

            // 7. Konfiguracja + marker wersji (z rootfsKey i rewizją obrazu)
            cb.phase("Configuration");
            new InstallerEngine(ctx, prefs, paths, noop())
                    .finalizeInstall(version, "ghcr-image:" + img.appKey, "dev",
                            img.revision, img.systemKey);

            // 8. Smoke test: launcher + java + dotnet; obecność aapt2 tylko logujemy
            cb.phase("Smoke test");
            PRoot.Result smoke = PRoot.run(PRoot.shellCommand(paths,
                    "ok=1; test -x /apace/launcher/Launcher || ok=0; "
                            + "command -v java >/dev/null 2>&1 || ok=0; "
                            + "command -v dotnet >/dev/null 2>&1 || ok=0; "
                            + "[ \"$ok\" = 1 ] && echo SMOKE_OK || true; "
                            + "test -x /usr/local/bin/aapt2 && echo 'aapt2: present' || echo 'note: aapt2 not present'; "
                            + "test -x /usr/bin/zipalign && echo 'zipalign: present' || echo 'note: zipalign not present'"),
                    paths, null);
            if (sink != null) {
                String[] lines = smoke.out.toString().split("\n");
                for (String line : lines) {
                    if (line.contains("aapt2") || line.contains("zipalign")) sink.onLine("[update] " + line);
                }
            }
            if (!smoke.out.toString().contains("SMOKE_OK")) {
                throw new Exception("After the update the environment does not start:\n" + smoke.tail(10));
            }

            // 9. Sprzątanie (backup rootfs jest duży — odbudowa z cache jest tania)
            pruneStaged();
        } catch (final Exception e) {
            // 10. Rollback obu katalogów
            cb.phase("ROLLBACK: restoring the previous version");
            Paths.rmRf(paths.current);
            if (preRootfs != null) {
                Paths.rmRf(paths.ubuntu);
                if (!preRootfs.renameTo(paths.ubuntu)) {
                    try {
                        Paths.mkdirs(paths.ubuntu);
                        Paths.copyTree(preRootfs, paths.ubuntu);
                        Paths.rmRf(preRootfs);
                    } catch (Exception e2) {
                        throw new Exception("ROLLBACK FAILED (rootfs): " + e2.getMessage()
                                + " (backup: " + preRootfs + ")", e);
                    }
                }
            }
            if (!preApp.renameTo(paths.current)) {
                try {
                    Paths.mkdirs(paths.current);
                    Paths.copyTree(preApp, paths.current);
                    Paths.rmRf(preApp);
                } catch (Exception e2) {
                    throw new Exception("ROLLBACK FAILED: " + e2.getMessage() + " (backup: " + preApp + ")", e);
                }
            }
            throw e;
        }
    }

    // ── Pomocnicze ─────────────────────────────────────────────────────────

    private InstallerEngine.Callback noop() {
        return new InstallerEngine.Callback() {
            public void stage(int i, int t, String l) {}
            public void detail(String l) { if (sink != null) sink.onLine("[update] " + l); }
            public void percent(float p) {}
            public void done(String v) {}
            public void error(String m) {}
        };
    }

    /** Miejsce na dysku; przy braku sprzątamy stare backupy i sprawdzamy ponownie. */
    private void ensureFreeSpace(long needBytes) throws Exception {
        long free = Paths.freeBytes(paths.base);
        if (free >= needBytes) return;
        File[] kids = paths.staged.listFiles();
        if (kids != null) {
            for (File k : kids) if (k.isDirectory()) Paths.rmRf(k);
        }
        free = Paths.freeBytes(paths.base);
        if (free < needBytes) {
            throw new Exception("Not enough free space for this update: ~" + (needBytes >> 20)
                    + " MB required, " + (free >> 20) + " MB free (after removing old update backups).");
        }
    }

    /**
     * Po przerwanej aktualizacji (proces zabity po przeniesieniu rootfs do
     * staged) katalog ubuntu może być pusty/niekompletny — przywróć backup.
     */
    private void recoverStagedRootfsIfNeeded() {
        if (Paths.entryExists(new File(paths.ubuntu, "usr/bin/env"))) return; // rootfs wygląda na cały
        File[] kids = paths.staged.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory() && k.getName().startsWith("pre-update-rootfs-")
                    && Paths.entryExists(new File(k, "usr/bin/env"))) {
                Paths.rmRf(paths.ubuntu);
                if (k.renameTo(paths.ubuntu)) {
                    if (sink != null) sink.onLine("[update] recovered the rootfs from an interrupted update: " + k.getName());
                    return;
                }
            }
        }
    }

    /**
     * Dane użytkownika wracają na swoje miejsce. Lista = audyt install-termux.sh
     * + resourcepacks (w docker-compose.dev.yml to wolumen — przeżywa update).
     */
    private void restoreDataDirs(File from, File to) throws Exception {
        String[] dirs = {"launcher/Data", "launcher/logs", "launcher/persistent_fabric",
                "data", "logs", "staticdata/resourcepacks"};
        for (String d : dirs) {
            restoreDir(new File(from, d), new File(to, d));
        }
        File cfg = new File(from, "launcher/config.json");
        if (cfg.isFile()) {
            InstallerEngine.writeText(new File(to, "launcher/config.json"), InstallerEngine.readText(cfg));
        }
        File apiCfg = new File(from, "components/api_config.json");
        if (apiCfg.isFile()) {
            InstallerEngine.writeText(new File(to, "components/api_config.json"), InstallerEngine.readText(apiCfg));
        }
    }

    private void restoreDir(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            Paths.rmRf(dst);
            Paths.mkdirs(dst.getParentFile());
            Paths.copyTree(src, dst);
        }
    }

    /**
     * Rotacja backupów: backupy rootfs (pre-update-rootfs-*) są ogromne — po
     * udanej aktualizacji usuwane (odbudowa z cache warstw jest tania); backupy
     * programu (pre-update-app-*) — trzymamy 2 najnowsze.
     */
    private void pruneStaged() {
        File[] kids = paths.staged.listFiles();
        if (kids == null) return;
        List<File> appBackups = new ArrayList<File>();
        for (File f : kids) {
            if (!f.isDirectory()) continue;
            if (f.getName().startsWith("pre-update-rootfs-")) {
                Paths.rmRf(f);
                continue;
            }
            appBackups.add(f);
        }
        Collections.sort(appBackups);
        int keep = 2;
        for (int i = 0; i < appBackups.size() - keep; i++) {
            Paths.rmRf(appBackups.get(i));
        }
    }

    /** ISO-8601 („2026-11-24T19:28:56…” → epoch ms), UTC; -1 gdy nie parsowalny. */
    private static long parseIso(String s) {
        try {
            String t = s.length() > 19 ? s.substring(0, 19) : s;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            f.setLenient(false);
            return f.parse(t).getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Marker wersji (JSON) — czytnik dla UI. */
    public static JSONObject versionInfo(Paths paths) {
        try {
            return new JSONObject(InstallerEngine.readText(
                    new File(paths.current, ".apacemobile-version.json")));
        } catch (Exception e) {
            return null;
        }
    }
}
