package com.kotpasztet.apacelauncher;

import java.io.File;

/**
 * Układ katalogów w pamięci prywatnej aplikacji.
 *
 * files/
 *   runtime/proot/            – binarka PRoot + loader + biblioteki (z assets APK)
 *   runtime/ubuntu/           – rootfs Ubuntu ARM64 (glibc) = środowisko serwera
 *   apace/current/            – pliki programu Apace (release zip) + dane wewnątrz
 *   apace/staged/             – katalog roboczy aktualizacji (pre-update-* / nowy)
 *   apace/backups/            – kopie zapasowe danych (zip)
 */
public final class Paths {
    public final File base;
    public final File runtime;
    public final File prootDir;
    public final File ubuntu;
    public final File apace;
    public final File current;
    public final File staged;
    public final File backups;
    public final File cache;

    public Paths(android.content.Context ctx) {
        base = ctx.getFilesDir();
        runtime = new File(base, "runtime");
        prootDir = new File(runtime, "proot");
        ubuntu = new File(runtime, "ubuntu");
        apace = new File(base, "apace");
        current = new File(apace, "current");
        staged = new File(apace, "staged");
        backups = new File(apace, "backups");
        cache = ctx.getCacheDir();
    }

    public File prootBin() { return new File(prootDir, "proot"); }
    public File prootLoader() { return new File(prootDir, "loader"); }
    public File prootLoader32() { return new File(prootDir, "loader32"); }

    // ── Rootfs: sprawdzanie wpisów BEZ podążania za symlinkami ───────────

    /**
     * Czy wpis o tej ścieżce istnieje w wyekstrahowanym rootfs — lstat, BEZ
     * podążania za symlinkami. Warstwy obrazu dockera zawierają symlinki z
     * ABSOLUTNYMI celami (usr/bin/java → /etc/alternatives/java →
     * /usr/lib/jvm/…, usr/bin/dotnet → /usr/share/dotnet/dotnet). Poza PRoot
     * Android rozwija je względem swojego korzenia „/”, nie katalogu rootfs,
     * więc File.isFile() i File.exists() zwracają false dla w pełni sprawnego
     * środowiska dev-image — stąd lstat (patrz fix „The environment is
     * incomplete” przy działającym smoke teście).
     */
    public static boolean entryExists(File f) {
        if (f == null) return false;
        try {
            android.system.Os.lstat(f.getAbsolutePath());
            return true;
        } catch (Throwable e) {
            return false; // ENOENT — wpisu nie ma (albo brak android.system w testach JVM)
        }
    }

    /**
     * Czy w rootfs jest któreś ze środowisk uruchomieniowych Apace (Java 17
     * dla Fabric / .NET 10 dla Launchera i ApiServer), w dowolnym układzie:
     *  - obraz dev: usr/bin/java (symlink apt), usr/bin/dotnet (symlink),
     *    usr/share/dotnet/dotnet (plik z aspnet:10.0),
     *  - kanał Main: usr/bin/java (symlink apt), opt/dotnet/dotnet (plik
     *    z tarballa aspnetcore-runtime).
     * Kolejność bez znaczenia (OR) — to zgrubny test „czy rootfs w ogóle
     * rozpakowany”, nie walidacja pojedynczego runtime'u.
     */
    public static boolean runtimePresent(File ubuntuRoot) {
        return entryExists(new File(ubuntuRoot, "usr/bin/java"))
                || entryExists(new File(ubuntuRoot, "usr/bin/dotnet"))
                || entryExists(new File(ubuntuRoot, "usr/share/dotnet/dotnet"))
                || entryExists(new File(ubuntuRoot, "opt/dotnet/dotnet"));
    }

    public boolean isInstalled() {
        return new File(current, ".apacemobile-version.json").isFile()
                && new File(current, "launcher").isDirectory();
    }

    public static void rmRf(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) rmRf(k);
        }
        f.delete();
    }

    public static void mkdirs(File f) {
        if (f != null && !f.exists()) f.mkdirs();
    }

    public static void copyTree(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            mkdirs(dst);
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
        } else if (src.isFile()) {
            mkdirs(dst.getParentFile());
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            dst.setExecutable(src.canExecute(), false);
            dst.setLastModified(src.lastModified());
        }
    }

    public static long freeBytes(File f) {
        return f.getFreeSpace();
    }
}
