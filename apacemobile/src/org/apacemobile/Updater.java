package org.apacemobile;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aktualizacje Apace (specyfikacja 7): kanał Stable = GitHub Releases,
 * kanał Dev = oficjalny rolling prerelease „dev-build” z brancha dev Apace
 * (workflow development.yml). Dane użytkownika są chronione
 * przez te same wykluczenia co instalator; przed podmianą plików tworzony
 * jest pełny backup przez RENAME (natychmiastowy), z rollbackiem przy błędzie.
 */
public final class Updater {

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

    public static class CheckResult {
        public boolean available;
        public String current;
        public String latest;
        public String assetUrl;
        public String sha256;
        public String message;
    }

    public CheckResult check() {
        CheckResult r = new CheckResult();
        r.current = InstallerEngine.readVersion(paths);
        try {
            if ("main".equals(prefs.channel())) {
                GitHubApi.Release rel = GitHubApi.devRelease();
                GitHubApi.Asset a = GitHubApi.pickDevArm64Asset(rel);
                r.latest = a != null && a.updated != null ? a.updated : rel.tag;
                if (a != null) r.assetUrl = a.url;
                r.message = "Source: official dev-build release (Apace dev branch)";
            } else {
                GitHubApi.Release rel = GitHubApi.latestRelease();
                GitHubApi.Asset a = GitHubApi.pickArm64Asset(rel);
                r.latest = rel.tag;
                if (a != null) r.assetUrl = a.url;
                r.message = "Source: GitHub Releases (" + rel.assets.size() + " files)";
            }
            r.available = r.current == null || !r.current.equals(r.latest);
        } catch (Exception e) {
            r.message = e.getMessage();
        }
        return r;
    }

    /**
     * Pełny przepływ (specyfikacja 7.2): stop → backup(rename) → download →
     * podmiana plików programu → przywrócenie danych → smoke test → rollback przy błędzie.
     */
    public void run(String assetUrl, String version, String sha256, ProgressListener cb) throws Exception {
        if (!paths.isInstalled()) throw new Exception("Apace is not installed — run the installer first.");
        if (assetUrl == null) throw new Exception("Missing update package URL.");

        // 1. Kopia zapasowa przez rename (natychmiastowa, pełna: program + dane)
        cb.phase("Backing up before update");
        Paths.mkdirs(paths.staged);
        Paths.mkdirs(paths.backups);
        File preUpdate = new File(paths.staged, "pre-update-" + System.currentTimeMillis());
        if (!paths.current.renameTo(preUpdate)) {
            throw new Exception("Could not create the backup (rename current→staged blocked).");
        }
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

            // 5. Konfiguracja + marker wersji
            cb.phase("Configuration");
            new InstallerEngine(ctx, prefs, paths, noop()).finalizeInstall(version, assetUrl, prefs.channel());
            // 6. Test uruchomienia środowiska
            PRoot.Result smoke = PRoot.run(PRoot.shellCommand(paths,
                    "test -x /apace/launcher/Launcher && echo SMOKE_OK"), paths, null);
            if (!smoke.out.toString().contains("SMOKE_OK")) {
                throw new Exception("After the update there is no executable launcher:\n" + smoke.tail(10));
            }

            // 7. Rotacja backupów (trzymamy 3)
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

    private InstallerEngine.Callback noop() {
        return new InstallerEngine.Callback() {
            public void stage(int i, int t, String l) {}
            public void detail(String l) { if (sink != null) sink.onLine("[update] " + l); }
            public void percent(float p) {}
            public void done(String v) {}
            public void error(String m) {}
        };
    }

    /** Dane użytkownika (audyt install-termux.sh) wracają na swoje miejsce. */
    private void restoreDataDirs(File from, File to) throws Exception {
        String[] dirs = {"launcher/Data", "launcher/logs", "launcher/persistent_fabric", "data", "logs"};
        for (String d : dirs) {
            File src = new File(from, d);
            File dst = new File(to, d);
            if (src.isDirectory()) {
                Paths.rmRf(dst);
                Paths.mkdirs(dst.getParentFile());
                Paths.copyTree(src, dst);
            }
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

    private void pruneStaged() {
        File[] kids = paths.staged.listFiles();
        if (kids == null) return;
        List<File> dirs = new ArrayList<File>();
        for (File f : kids) if (f.isDirectory()) dirs.add(f);
        Collections.sort(dirs);
        int keep = 2;
        for (int i = 0; i < dirs.size() - keep; i++) {
            Paths.rmRf(dirs.get(i));
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
