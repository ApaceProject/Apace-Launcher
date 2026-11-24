package com.kotpasztet.apacelauncher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Minimalny czytnik archiwów tar / tar.gz (USTAR + GNU longname 'L'/'K' + pax 'x'/'g').
 * Wystarczający do ubuntu-base i runtime'u .NET (oba to tar.gz).
 * Zabezpieczenie przed tar-slip: ścieżki absolutne i ".." są odrzucane.
 */
public final class TarExtractor {

    public interface Progress {
        void onEntry(String name, long bytesSoFar);
    }

    private final InputStream raw;
    private final File destDir;
    private final Progress progress;
    /** Opcjonalny filtr prefiksu (warstwy OCI): wycina tylko wpisy z tym prefiksem
     *  i zdejmuje go (np. „app/" z obrazu dockera → zawartość /app do destDir). */
    private final String onlyPrefix;

    public TarExtractor(InputStream in, boolean gzip, File destDir, Progress progress) throws Exception {
        this(in, gzip, destDir, progress, null);
    }

    public TarExtractor(InputStream in, boolean gzip, File destDir, Progress progress,
                        String onlyPrefix) throws Exception {
        this.raw = in;
        this.destDir = destDir;
        this.progress = progress;
        this.onlyPrefix = onlyPrefix;
        stream = gzip ? new GZIPInputStream(in, 65536) : in;
    }

    private InputStream stream;
    private long bytesSoFar = 0;

    public void extract() throws Exception {
        byte[] hdr = new byte[512];
        String pendingLongName = null;
        String pendingLongLink = null;
        while (true) {
            readFully(hdr);
            if (isZero(hdr)) break;
            // sanity check checksumy nagłówka
            if (!verifyChecksum(hdr)) {
                throw new Exception("Archiwum tar uszkodzone (zła suma kontrolna nagłówka)");
            }
            String name = str(hdr, 0, 100);
            long size = octal(hdr, 124, 12);
            String type = str(hdr, 156, 1);
            String prefix = str(hdr, 345, 155);
            int mode = (int) octal(hdr, 100, 8);

            if (prefix.length() > 0 && type.equals("0")) {
                name = prefix + "/" + name;
            }
            if ("L".equals(type)) { // GNU long name
                byte[] data = new byte[(int) size];
                readFully(data);
                pendingLongName = cstr(data);
                skipPad(size);
                continue;
            }
            if ("K".equals(type)) { // GNU long link
                byte[] data = new byte[(int) size];
                readFully(data);
                pendingLongLink = cstr(data);
                skipPad(size);
                continue;
            }
            if ("x".equals(type) || "g".equals(type)) { // pax: tylko nadpisz name jeśli jest path=
                byte[] data = new byte[(int) size];
                readFully(data);
                String paxPath = paxPath(cstr(data));
                if (paxPath != null) pendingLongName = paxPath;
                skipPad(size);
                continue;
            }

            if (pendingLongName != null) {
                name = pendingLongName;
                pendingLongName = null;
            }
            // absolutne ścieżki → względne (sanitize i tak odrzuci ".." — tar-slip)
            if (name.startsWith("/")) name = name.substring(1);

            String clean = sanitize(name);
            if (clean == null) {
                skipData(size);
                continue; // odrzucona ścieżka (slip) — pomiń zawartość
            }
            if (onlyPrefix != null) {
                if (!clean.startsWith(onlyPrefix)) {
                    skipData(size);
                    continue; // zawartość poza /app (java, dotnet, docker…) — pomijamy
                }
                clean = clean.substring(onlyPrefix.length());
                if (clean.length() == 0) {
                    skipData(size);
                    continue; // sam katalog „app”
                }
            }

            if ("5".equals(type)) {
                new File(destDir, clean).mkdirs();
            } else if ("0".equals(type) || "\0".equals(type) || "".equals(type)) {
                File f = new File(destDir, clean);
                f.getParentFile().mkdirs();
                FileOutputStream out = new FileOutputStream(f);
                copyN(out, size);
                out.close();
                applyMode(f, mode);
            } else if ("2".equals(type)) { // symlink
                String link = pendingLongLink != null ? pendingLongLink : str(hdr, 157, 100);
                pendingLongLink = null;
                File f = new File(destDir, clean);
                f.getParentFile().mkdirs();
                if (f.exists()) f.delete();
                boolean done = false;
                try {
                    android.system.Os.symlink(link, f.getAbsolutePath());
                    done = true;
                } catch (Throwable e) {
                    done = false; // JVM test lub rzadki system bez symlinków — fallback niżej
                }
                if (!done) {
                    // fallback: absolutny link → względny; plik-wzorzec kopiujemy, katalog tworzymy
                    String ln = link.replace('\\', '/');
                    if (ln.startsWith("/")) ln = ln.substring(1);
                    File t = new File(destDir, ln);
                    try {
                        if (t.isFile()) {
                            java.io.FileInputStream ti = new java.io.FileInputStream(t);
                            java.io.FileOutputStream to = new java.io.FileOutputStream(f);
                            byte[] b = new byte[65536];
                            int n2;
                            while ((n2 = ti.read(b)) > 0) to.write(b, 0, n2);
                            to.close();
                            ti.close();
                            f.setExecutable(t.canExecute(), false);
                        } else if (t.isDirectory()) {
                            f.mkdirs();
                        } else {
                            f.createNewFile();
                        }
                    } catch (Throwable e2) { /* zostaw */ }
                }
            } else {
                // '1' hardlink, '3'/'4'/'6' device, '7' — pomijamy zawartość
                skipData(size);
            }
            bytesSoFar += Math.max(size, 0);
            if (progress != null) progress.onEntry(clean, bytesSoFar);
        }
        stream.close();
    }

    private void skipData(long size) throws Exception {
        skipBytes(size);
        skipPad(size);
    }

    private void skipPad(long size) throws Exception {
        long rem = size % 512;
        if (rem != 0) skipBytes(512 - rem);
    }

    private void skipBytes(long size) throws Exception {
        long left = size;
        while (left > 0) {
            long n = stream.skip(left);
            if (n <= 0) {
                if (stream.read() < 0) break;
                n = 1;
            }
            left -= n;
        }
    }

    private void copyN(FileOutputStream out, long size) throws Exception {
        byte[] buf = new byte[65536];
        long left = size;
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int n = stream.read(buf, 0, want);
            if (n < 0) throw new Exception("Archiwum tar ucięte");
            out.write(buf, 0, n);
            left -= n;
        }
        skipPad(size);
    }

    private void readFully(byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = stream.read(b, off, b.length - off);
            if (n < 0) throw new Exception("Archiwum tar ucięte (nagłówek)");
            off += n;
        }
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static boolean verifyChecksum(byte[] hdr) {
        int stored = (int) octal(hdr, 148, 8);
        int sum = 0;
        for (int i = 0; i < 512; i++) {
            if (i >= 148 && i < 156) sum += 32;
            else sum += hdr[i] & 0xFF;
        }
        return stored == sum;
    }

    private static String str(byte[] b, int off, int len) {
        int end = Math.min(off + len, b.length);
        int real = off;
        while (real < end && b[real] != 0) real++;
        return new String(b, off, real - off).trim();
    }

    private static String cstr(byte[] b) {
        int real = 0;
        while (real < b.length && b[real] != 0) real++;
        return new String(b, 0, real);
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        boolean started = false;
        for (int i = off; i < off + len; i++) {
            char c = (char) (b[i] & 0xFF);
            if (c == 0 || c == ' ') {
                if (started) break;
                continue;
            }
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
            started = true;
        }
        return v;
    }

    private static String paxPath(String pax) {
        if (pax == null) return null;
        int i = pax.indexOf("path=");
        if (i < 0) return null;
        int start = i + 5;
        int end = pax.indexOf('\n', start);
        if (end < 0) end = pax.length();
        String v = pax.substring(start, end);
        if (v.length() > 1 && v.charAt(0) >= '0' && v.charAt(0) <= '9') {
            // format: "<len> path=<path>\n" — obetnij prefiks długości
            int sp = v.indexOf(' ');
            if (sp > 0 && sp < v.length() - 1) v = v.substring(sp + 1);
        }
        return v;
    }

    private String sanitize(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/") || n.contains("/../") || n.equals("..") || n.endsWith("/..")) {
            return null;
        }
        if (n.length() == 0) return null;
        return n;
    }

    private void applyMode(File f, int mode) {
        boolean ownerExec = (mode & 0100) != 0;
        f.setReadable(true, false);
        f.setWritable(true, true);
        if (ownerExec) f.setExecutable(true, false);
    }

    public static void extractTarGz(File tarGz, File destDir, Progress p) throws Exception {
        extractTarGz(tarGz, destDir, null, p);
    }

    /** Warstwa OCI (tar.gz): tylko wpisy pod onlyPrefix (np. „app/”), bez tego prefiksu. */
    public static void extractTarGz(File tarGz, File destDir, String onlyPrefix, Progress p) throws Exception {
        FileInputStream fin = new FileInputStream(tarGz);
        try {
            new TarExtractor(fin, true, destDir, p, onlyPrefix).extract();
        } finally {
            try { fin.close(); } catch (Exception ignore) {}
        }
    }
}
