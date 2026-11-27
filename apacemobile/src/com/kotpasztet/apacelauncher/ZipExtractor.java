package com.kotpasztet.apacelauncher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Ekstrakcja paczki release Apace (zip) do katalogu docelowego.
 *
 * Ważne (na podstawie audytu):
 *  - zipy Apace są budowane przez PowerShell Compress-Archive, który potrafi
 *    używać separatorów "\\" w nazwach wpisów — normalizujemy do "/".
 *  - Reinstalacja/aktualizacja NIGDY nie może nadpisać danych użytkownika:
 *    pomijamy launcher/Data/, launcher/logs/, launcher/persistent_fabric/,
 *    data/, logs/ oraz launcher/config.json (jeśli istnieje) — dokładnie te
 *    same wykluczenia stosuje install-termux.sh w repozytorium Apace.
 *  - Wykluczenia porównujemy CASE-INSENSITIVE (lowercase po obu stronach):
 *    release'owe zipy Apace v0.2.0–v0.2.3 zawierają zatruty wpis
 *    "launcher/Data/app.db" (wielkie „D"!) — gotową bazę z istniejącym
 *    kontem owner. Poprzednia wersja porównywała zlowercase'owaną nazwę
 *    wpisu z wzorcem "launcher/Data/" i przez różnicę wielkości liter nigdy
 *    nie łapała tego pliku: panel startował na dowożonej bazie i żadne
 *    nowo zarejestrowane konto nie dostawało roli owner. Kanał dev (obraz
 *    OCI) nie ma bazy w zipie i działał poprawnie.
 *  - Zabezpieczenie przed zip-slip ("..").
 */
public final class ZipExtractor {

    /**
     * Katalogi danych użytkownika pomijane przy instalacji/aktualizacji
     * (protectData = true). WZORCE MUSZĄ BYĆ lowercase — patrz isExcluded():
     * porównanie robi lowercase na nazwie wpisu ORAZ na wzorcach, żeby
     * różnica wielkości liter (np. "launcher/Data/" z Compress-Archive vs
     * "launcher/data/") nie przepuszczała zatrutych wpisów.
     *
     * launcher/Data/ to dane użytkownika: świeża instalacja sama tworzy bazę
     * przez migracje EF, a przy aktualizacji baza użytkownika nie może zostać
     * nadpisana — dlatego NIC z pod launcher/Data/ nie jest wypakowywane,
     * nawet ze starych zipów release'owych, które mają tam app.db z kontem owner.
     */
    public static final String[] DATA_EXCLUSIONS = {
            "launcher/data/", "launcher/logs/", "launcher/persistent_fabric/",
            "data/", "logs/"
    };
    private static final String CONFIG_ENTRY = "launcher/config.json";

    public interface Progress {
        void onEntry(String name, long bytesSoFar);
    }

    private final File destDir;
    private final boolean protectData;
    private final Progress progress;
    private long bytesSoFar = 0;

    /** @param protectData true = pomiń katalogi danych i config.json (instalacja/aktualizacja) */
    public ZipExtractor(File destDir, boolean protectData, Progress progress) {
        this.destDir = destDir;
        this.protectData = protectData;
        this.progress = progress;
    }

    public List<String> extract(InputStream in) throws Exception {
        List<String> written = new ArrayList<String>();
        ZipInputStream zis = new ZipInputStream(in);
        byte[] buf = new byte[65536];
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            String rawName = e.getName();
            String name = rawName.replace('\\', '/');
            if (name.endsWith("/")) {
                String d = sanitizeDir(name);
                if (d != null) Paths.mkdirs(new File(destDir, d));
                continue;
            }
            boolean skip = name.endsWith("/") || name.contains("..");
            if (!skip && isExcluded(name)) skip = true;
            if (skip) {
                // musimy przepompować dane wpisu, żeby strumień poszedł dalej
                long n;
                while ((n = zis.read(buf)) > 0) { /* drain */ }
                continue;
            }
            String clean = sanitizeDir(name);
            if (clean == null) continue;
            File f = new File(destDir, clean);
            Paths.mkdirs(f.getParentFile());
            FileOutputStream out = new FileOutputStream(f);
            long n;
            while ((n = zis.read(buf)) > 0) {
                out.write(buf, 0, (int) n);
                bytesSoFar += n;
            }
            out.close();
            f.setReadable(true, false);
            f.setWritable(true, true);
            written.add(clean);
            if (progress != null) progress.onEntry(clean, bytesSoFar);
        }
        zis.close();
        return written;
    }

    private boolean isExcluded(String name) {
        if (!protectData) return false;
        // Normalizacja OBU stron porównania: separatory "\\" → "/"
        // (Compress-Archive) + lowercase nazwy wpisu i wzorców. Bez lowercase
        // wzorców wpis "launcher/Data/app.db" z zatrutych zipów v0.2.0–v0.2.3
        // przechodził jako zwykły plik i dowoził gotową bazę z kontem owner
        // (bug: pierwsze zarejestrowane konto nigdy nie dostawało roli owner).
        String low = name.replace('\\', '/').toLowerCase();
        if (low.equals(CONFIG_ENTRY) && new File(destDir, CONFIG_ENTRY).isFile()) {
            return true; // zachowaj istniejącą konfigurację (ApiPort itd.)
        }
        for (String p : DATA_EXCLUSIONS) {
            if (low.startsWith(p.toLowerCase())) return true;
        }
        return false;
    }

    public static String sanitizeDir(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/") || n.contains("/../") || n.equals("..") || n.endsWith("/..")) {
            return null;
        }
        if (n.length() == 0) return null;
        return n;
    }

    public static List<String> extractZipFile(File zip, File destDir, boolean protectData, Progress p) throws Exception {
        FileInputStream fin = new FileInputStream(zip);
        try {
            return new ZipExtractor(destDir, protectData, p).extract(fin);
        } finally {
            try { fin.close(); } catch (Exception ignore) {}
        }
    }
}
