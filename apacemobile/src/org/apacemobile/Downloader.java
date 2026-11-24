package org.apacemobile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Pobieranie plików przez HTTP(S) z raportowaniem postępu + suma SHA-256.
 * Plik trafia najpierw do <cel>.part i dopiero po udanym pobraniu jest renamowany.
 */
public final class Downloader {

    public interface Progress {
        /** downloaded / total (total może być -1, gdy serwer nie podał długości) */
        void onProgress(long downloaded, long total);
    }

    public static String download(String url, File dest, Progress progress) throws Exception {
        File part = new File(dest.getParentFile(), dest.getName() + ".part");
        part.getParentFile().mkdirs();
        long done = 0;
        MessageDigest sha = null;
        try {
            HttpURLConnection c = open(url);
            int code = c.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code + " przy pobieraniu " + url);
            }
            long total = c.getContentLength();
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(part);
            sha = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    if (progress != null) progress.onProgress(done, total);
                }
            } finally {
                try { in.close(); } catch (Exception ignore) {}
                try { out.close(); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            part.delete();
            throw e;
        }
        if (!part.renameTo(dest)) {
            // rename na tym samym systemie plików nie powinno zawieść, ale gdyby:
            copyFile(part, dest);
            part.delete();
        }
        return hex(sha.digest());
    }

    public static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ApaceMobile/1.0 (Android)");
        return c;
    }

    public static void copyFile(File src, File dst) throws Exception {
        InputStream in = new java.io.FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    public static String hex(byte[] b) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(out);
    }
}
