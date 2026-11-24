package org.apacemobile;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Kopie zapasowe danych użytkownika (specyfikacja 7.2).
 * Backup zip = launcher/Data, launcher/persistent_fabric, launcher/config.json,
 * data/, components/api_config.json. Logi pomijamy (regenerowalne).
 * Przechowujemy maks. 3 kopie; eksport przez SAF do dowolnej lokalizacji.
 */
public final class BackupManager {

    private static final String[] BACKUP_DIRS = {
            "launcher/Data", "launcher/persistent_fabric", "data"
    };
    private static final String[] BACKUP_FILES = {
            "launcher/config.json", "components/api_config.json"
    };
    private static final int KEEP = 3;

    private final Paths paths;

    public BackupManager(Paths paths) {
        this.paths = paths;
    }

    public File createBackup() throws Exception {
        if (!paths.isInstalled()) throw new Exception("Apace is not installed.");
        Paths.mkdirs(paths.backups);
        File out = new File(paths.backups, "apace-backup-" +
                new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".zip");
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
        zos.setLevel(5);
        byte[] buf = new byte[65536];
        for (String d : BACKUP_DIRS) {
            File dir = new File(paths.current, d);
            if (!dir.isDirectory()) continue;
            addTree(zos, dir, d, buf);
        }
        for (String f : BACKUP_FILES) {
            File file = new File(paths.current, f);
            if (!file.isFile()) continue;
            putEntry(zos, f, file.length());
            InputStream in = new FileInputStream(file);
            int n;
            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
            in.close();
            zos.closeEntry();
        }
        zos.close();
        prune();
        return out;
    }

    private void addTree(ZipOutputStream zos, File dir, String prefix, byte[] buf) throws Exception {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String path = prefix + "/" + k.getName();
            if (k.isDirectory()) {
                addTree(zos, k, path, buf);
            } else if (k.isFile()) {
                putEntry(zos, path, k.length());
                InputStream in = new FileInputStream(k);
                int n;
                while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                in.close();
                zos.closeEntry();
            }
        }
    }

    private void putEntry(ZipOutputStream zos, String name, long size) throws Exception {
        ZipEntry e = new ZipEntry(name);
        if (size > 0) e.setSize(size);
        zos.putNextEntry(e);
    }

    public List<File> listBackups() {
        List<File> out = new ArrayList<File>();
        File[] kids = paths.backups.listFiles();
        if (kids != null) {
            out.addAll(Arrays.asList(kids));
            Collections.sort(out, Collections.reverseOrder());
        }
        return out;
    }

    private void prune() {
        List<File> list = listBackups();
        for (int i = KEEP; i < list.size(); i++) {
            list.get(i).delete();
        }
    }

    /** Przywrócenie: rozpakowanie zipa do apace/current (nadpisuje pliki danych). */
    public void restore(File zip) throws Exception {
        if (!paths.isInstalled()) throw new Exception("Install Apace first.");
        ZipExtractor.extractZipFile(zip, paths.current, false, null);
    }

    /** Eksport kopii do wybranej lokalizacji przez SAF (ACTION_CREATE_DOCUMENT). */
    public void exportTo(Uri dest, File backup) throws Exception {
        OutputStream os = null;
        InputStream is = null;
        try {
            os = ctx0().getContentResolver().openOutputStream(dest);
            is = new FileInputStream(backup);
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignore) {}
            try { if (os != null) os.close(); } catch (Exception ignore) {}
        }
    }

    private Context ctx0() {
        return App.appContext();
    }

    /** Import kopii z wybranej lokalizacji przez SAF. */
    public void importFrom(Uri src) throws Exception {
        InputStream in = null;
        File tmp = new File(paths.cache, "import-backup.zip");
        try {
            in = ctx0().getContentResolver().openInputStream(src);
            OutputStream os = new FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignore) {}
        }
        restore(tmp);
        tmp.delete();
    }
}
