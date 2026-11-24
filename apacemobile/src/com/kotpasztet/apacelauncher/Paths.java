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
