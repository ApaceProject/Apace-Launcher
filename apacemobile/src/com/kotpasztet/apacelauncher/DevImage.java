package com.kotpasztet.apacelauncher;

import java.io.File;
import java.util.List;

/**
 * Dev channel: pobieranie i rozpakowywanie OBRAZU ghcr.io/apaceproject/apace:dev
 * (używane przez instalator pierwszej instalacji i przez Updater).
 *
 * Kluczowa zmiana (fix „rootfs nigdy się nie aktualizuje”): aktualizacja dev
 * stosuje SYSTEMOWE warstwy obrazu do rootfs (paths.ubuntu) — czyli dokładnie
 * ten sam system, który ma obraz dockera: Ubuntu 24.04 + Java 17 + .NET 10 +
 * aapt/aapt2 + zipalign + android-framework + PowerShell. Wcześniej telefon
 * dostawał WYŁĄCZNIE warstwę /app (binarka serwera), a rootfs powstawał raz
 * przy instalacji z ubuntu-base + apt — stąd „no system aapt2 was found”
 * przy działającym nowym kodzie serwera.
 *
 * Warstwy są cacheowane po digeście (plik = treść, digest = nazwa), więc:
 *  - integralność każdej warstwy jest weryfikowana SHA-256 przy pobraniu,
 *  - kolejna aktualizacja dev pobiera tylko zmienione warstwy (zwykle samą
 *    warstwę /app), a warstwy bazowe z cache.
 *
 * Mapowanie obraz → telefon:
 *   / (warstwy systemowe, bez „app/”) → files/runtime/ubuntu  (rootfs proot)
 *   /app/* (warstwy aplikacyjne)      → files/apace/current   (bind /apace)
 * Warstwa „apt-get install docker.io docker-compose-v2” jest pomijana — na
 * telefonie nie ma gniazda dockera, a panel i tak aktualizuje się przez launcher.
 */
public final class DevImage {

    public interface Progress {
        /** 0..1 — łącznie dla wszystkich warstw. */
        void percent(float p);
        void note(String line);
    }

    /** Katalog cache warstw (digest → plik). Tworzony przy pierwszym użyciu. */
    public static File layerCacheDir(Paths paths) {
        File dir = new File(paths.cache, "oci");
        Paths.mkdirs(dir);
        return dir;
    }

    /** Plik cache dla digestu „sha256:&lt;hex&gt;”. */
    static File layerFile(File cacheDir, String digest) {
        String hex = digest.startsWith("sha256:") ? digest.substring(7) : digest;
        return new File(cacheDir, hex + ".tgz");
    }

    /**
     * Pobiera brakujące warstwy obrazu do cache (z weryfikacją SHA-256 względem
     * digestu). Istniejące pliki cache są pomijane — digest jest nazwą pliku,
     * więc cache hit = ta sama treść.
     */
    public static void downloadLayers(GitHubApi.DevBuild img, final Paths paths,
                                      final Progress cb) throws Exception {
        File cacheDir = layerCacheDir(paths);
        long total = 0, done = 0;
        for (GitHubApi.Layer l : img.layers) {
            if (!l.skip) total += l.size;
        }
        final long fTotal = total;
        final long[] fDone = {0};
        for (int i = 0; i < img.layers.size(); i++) {
            final GitHubApi.Layer l = img.layers.get(i);
            if (l.skip) {
                if (cb != null) cb.note("layer " + i + ": docker layer skipped (not used on Android)");
                continue;
            }
            File f = layerFile(cacheDir, l.digest);
            if (f.isFile() && f.length() == l.size) {
                fDone[0] += l.size;
                if (cb != null) {
                    cb.note("layer " + i + ": cached");
                    cb.percent(fTotal > 0 ? (float) fDone[0] / fTotal : 1f);
                }
                continue;
            }
            if (cb != null) cb.note("layer " + i + ": downloading " + (l.size >> 20) + " MB");
            String gotSha;
            try {
                gotSha = Downloader.download(GitHubApi.ghcrBlobUrl(l.digest), f,
                        new Downloader.Progress() {
                            public void onProgress(long d, long t) {
                                if (cb != null && fTotal > 0) {
                                    cb.percent(Math.min(1f,
                                            (float) (fDone[0] + d) / (float) fTotal));
                                }
                            }
                        }, GitHubApi.ghcrToken());
            } catch (Exception e) {
                f.delete();
                throw e;
            }
            String expected = l.digest.startsWith("sha256:") ? l.digest.substring(7) : l.digest;
            if (!gotSha.equalsIgnoreCase(expected)) {
                f.delete();
                throw new Exception("Layer integrity check failed:\nexpected sha256:"
                        + expected + "\ngot      sha256:" + gotSha);
            }
            fDone[0] += l.size;
            if (cb != null) cb.percent(fTotal > 0 ? (float) fDone[0] / fTotal : 1f);
        }
    }

    /**
     * Rozpakowuje SYSTEMOWĄ część obrazu do rootfs: wszystkie warstwy w
     * kolejności manifestu, z pominięciem warstw „skip” (docker) i wpisów
     * „app/” (te trafiają do katalogu Apace — na telefonie /app = /apace).
     * docelowy katalog musi być pusty (świeży rootfs).
     */
    public static void extractRootfs(GitHubApi.DevBuild img, Paths paths, File destRootfs,
                                     final Progress cb) throws Exception {
        File cacheDir = layerCacheDir(paths);
        List<GitHubApi.Layer> layers = img.layers;
        String[] excludeApp = {"app/"};
        for (int i = 0; i < layers.size(); i++) {
            GitHubApi.Layer l = layers.get(i);
            if (l.skip) continue;
            final float base = (float) i / (float) Math.max(1, layers.size());
            final float span = 1f / (float) Math.max(1, layers.size());
            if (cb != null) cb.percent(base);
            File layerFile = layerFile(cacheDir, l.digest);
            try {
                TarExtractor.extractTarGz(layerFile, destRootfs,
                        null, excludeApp, new TarExtractor.Progress() {
                            public void onEntry(String name, long bytesSoFar) {
                                if (cb != null) cb.percent(base + span * 0.95f);
                            }
                        });
            } catch (Exception e) {
                layerFile.delete(); // uszkodzony cache — następna próba pobierze warstwę ponownie
                throw new Exception("rootfs: warstwa " + l.digest.substring(0, 19)
                        + " uszkodzona (usunięto z cache): " + e.getMessage(), e);
            }
        }
        if (cb != null) cb.percent(1f);
    }

    /**
     * Rozpakowuje APLIKACYJNĄ część obrazu (cała zawartość /app ze wszystkich
     * warstw — także późniejsze COPY compose/, defaults/mods, entrypoint.sh
     * i warstwa chmod) do katalogu instalacji Apace (paths.current). Prefiks
     * „app/” jest zdejmowany, tak jak wcześniej robiła to ekstrakcja samej
     * warstwy /app — katalog docelowy musi być świeży (dane wracają potem
     * z backupu przez Updater.restoreDataDirs).
     */
    public static void extractApp(GitHubApi.DevBuild img, Paths paths, File destCurrent,
                                  final Progress cb) throws Exception {
        File cacheDir = layerCacheDir(paths);
        List<GitHubApi.Layer> layers = img.layers;
        int appCount = 0;
        for (GitHubApi.Layer l : layers) if (l.app && !l.skip) appCount++;
        int seen = 0;
        for (int i = 0; i < layers.size(); i++) {
            GitHubApi.Layer l = layers.get(i);
            if (l.skip || !l.app) continue;
            final float base = appCount > 0 ? (float) seen / (float) appCount : 0f;
            final float span = appCount > 0 ? 1f / (float) appCount : 1f;
            seen++;
            if (cb != null) cb.percent(base);
            File layerFile = layerFile(cacheDir, l.digest);
            try {
                TarExtractor.extractTarGz(layerFile, destCurrent,
                        "app/", null, new TarExtractor.Progress() {
                            public void onEntry(String name, long bytesSoFar) {
                                if (cb != null) cb.percent(base + span * 0.95f);
                            }
                        });
            } catch (Exception e) {
                layerFile.delete(); // uszkodzony cache — następna próba pobierze warstwę ponownie
                throw new Exception("app layer " + l.digest.substring(0, 19)
                        + " uszkodzona (usunięto z cache): " + e.getMessage(), e);
            }
        }
        if (cb != null) cb.percent(1f);
    }

    private DevImage() {}
}
