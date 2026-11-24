package org.apacemobile;

import android.content.Context;
import android.content.Intent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Foreground Service (specyfikacja 6): utrzymuje serwer przy życiu po
 * zminimalizowaniu aplikacji, pokazuje stałe powiadomienie z akcjami
 * „Otwórz panel” i „Stop”, obsługuje instalację i aktualizacje w tle.
 *
 * UWAGA targetSdk 28 (świadoma decyzja jak w Termuxie): od Androida 10
 * system blokuje exec() plików z pamięci aplikacji przy wyższym targetSdk
 * (SELinux W^X) — bez targetSdk 28 PRoot nie uruchomi się.
 */
public class ApaceService extends Service {

    public static final String ACTION_INSTALL = "org.apacemobile.INSTALL";
    public static final String ACTION_CANCEL_INSTALL = "org.apacemobile.CANCEL_INSTALL";
    public static final String ACTION_START = "org.apacemobile.START";
    public static final String ACTION_STOP = "org.apacemobile.STOP";
    public static final String ACTION_RESTART = "org.apacemobile.RESTART";
    public static final String ACTION_UPDATE = "org.apacemobile.UPDATE";

    public static final String ACTION_STATE = "org.apacemobile.STATE";
    public static final String ACTION_LOG = "org.apacemobile.LOG";
    public static final String ACTION_STATS = "org.apacemobile.STATS";
    public static final String ACTION_INSTALL_PROGRESS = "org.apacemobile.INSTALL_PROGRESS";

    public static final String CH_STATUS = "apace_status";
    public static final String CH_PROGRESS = "apace_progress";

    public enum State { NOT_INSTALLED, INSTALLING, STOPPED, STARTING, RUNNING, STOPPING, UPDATING, ERROR }

    private static final Object LOCK = new Object();
    private static volatile State state = ApaceService.State.NOT_INSTALLED;
    private static String lastError = null;
    private static volatile ApaceService instance;
    /** Ostatnia etykieta etapu instalacji — dla UI po re-attach aktywności. */
    public static volatile String lastInstallStage = null;

    private Paths paths;
    private Prefs prefs;
    private ServerEngine engine;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastCpuJiffies = -1;
    private volatile boolean panelReady = false;
    private volatile boolean installCancelled = false;
    private InstallerEngine installer;

    // ── Auto-stop oszczędnościowy ──────────────────────────────────────────
    /** Czy MainActivity jest teraz widoczna (między onStart a onStop). */
    private static volatile boolean appInForeground = true;
    /** Moment ostatniej widoczności apki / moment zejścia z pierwszego planu. */
    private static volatile long lastAppVisibleAt = System.currentTimeMillis();
    private static final long IDLE_STOP_MS = 5 * 60 * 1000L;
    private static final long IDLE_CHECK_MS = 30 * 1000L;
    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private final Runnable idleCheck = new Runnable() { public void run() {
        try { autoStopIfIdle(); } catch (Exception ignore) {}
        idleHandler.postDelayed(this, IDLE_CHECK_MS);
    }};

    public static State state() { return state; }
    public static String lastError() { return lastError; }

    public static void setState(State s) {
        synchronized (LOCK) { state = s; }
        broadcast(null, ACTION_STATE, null);
    }

    public static void setStateError(String msg) {
        lastError = msg;
        synchronized (LOCK) { state = ApaceService.State.ERROR; }
        android.content.Context c = App.appContext();
        if (c != null) {
            android.content.Intent i = new android.content.Intent(ACTION_STATE);
            i.setPackage(c.getPackageName());
            i.putExtra("error", msg);
            c.sendBroadcast(i);
        }
    }

    public static void broadcast(Context c, String action, android.os.Bundle extras) {
        Context cc = c != null ? c : App.appContext();
        if (cc == null) return;
        android.content.Intent i = new android.content.Intent(action);
        i.setPackage(cc.getPackageName());
        if (extras != null) i.putExtras(extras);
        cc.sendBroadcast(i);
    }

    public static void log(String line) {
        LogRing.add(line);
        Context c = App.appContext();
        if (c != null) {
            Intent i = new Intent(ACTION_LOG);
            i.setPackage(c.getPackageName());
            i.putExtra("line", line);
            c.sendBroadcast(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        paths = new Paths(this);
        prefs = new Prefs(this);
        engine = new ServerEngine(paths, prefs);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS,
                "Apace status", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_PROGRESS,
                "Install & updates", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, buildNotification(state));
        idleHandler.postDelayed(idleCheck, IDLE_CHECK_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_INSTALL.equals(action)) {
            beginInstall();
        } else if (ACTION_CANCEL_INSTALL.equals(action)) {
            installCancelled = true;
            if (installer != null) installer.cancel();
        } else if (ACTION_START.equals(action)) {
            beginStart();
        } else if (ACTION_STOP.equals(action)) {
            beginStop();
        } else if (ACTION_RESTART.equals(action)) {
            beginStopThenStart();
        } else if (ACTION_UPDATE.equals(action)) {
            beginUpdate(intent.getStringExtra("assetUrl"), intent.getStringExtra("version"),
                    intent.getStringExtra("sha256"));
        }
        return START_STICKY;
    }

    /** Komenda na stdin działającego serwera (konsola). */
    public static boolean sendCommand(String cmd) {
        ApaceService s = instance;
        if (s == null || s.engine == null) return false;
        try {
            s.engine.sendCommand(cmd);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Instalacja ─────────────────────────────────────────────────────────

    private void beginInstall() {
        synchronized (LOCK) {
            if (state == ApaceService.State.INSTALLING || state == ApaceService.State.RUNNING || state == ApaceService.State.STARTING) return;
            state = ApaceService.State.INSTALLING;
        }
        lastError = null;
        lastInstallStage = null;
        installCancelled = false;
        updateNotification("Installing Apace…");
        installer = new InstallerEngine(this, prefs, paths, new InstallerEngine.Callback() {
            public void stage(final int index, final int total, final String label) {
                lastInstallStage = "(" + index + "/" + total + ") " + label;
                ui.post(new Runnable() { public void run() {
                    updateNotification("Install (" + index + "/" + total + "): " + label);
                }});
                android.os.Bundle b = new android.os.Bundle();
                b.putInt("stage", index);
                b.putInt("total", total);
                b.putString("label", label);
                broadcast(null, ACTION_INSTALL_PROGRESS, b);
            }
            public void detail(String line) { log(line); }
            public void percent(float p) {
                android.os.Bundle b = new android.os.Bundle();
                b.putFloat("percent", p);
                broadcast(null, ACTION_INSTALL_PROGRESS, b);
            }
            public void done(String version) {
                setState(paths.isInstalled() ? ApaceService.State.STOPPED : ApaceService.State.NOT_INSTALLED);
                updateNotification("Installed Apace " + version);
                log("[mobile] Install finished: Apace " + version);
                // user flow: after install the server starts right away → spinner → panel
                ui.post(new Runnable() { public void run() { beginStart(); }});
            }
            public void error(final String message) {
                lastError = message;
                log("[mobile] INSTALL ERROR: " + message);
                setState(ApaceService.State.ERROR);
                ui.post(new Runnable() { public void run() {
                    updateNotification("Installation failed");
                }});
            }
        });
        worker = new Thread() { public void run() { installer.run(); }};
        worker.start();
    }

    // ── Start ──────────────────────────────────────────────────────────────

    private void beginStart() {
        synchronized (LOCK) {
            if (state == ApaceService.State.RUNNING || state == ApaceService.State.STARTING || state == ApaceService.State.INSTALLING
                    || state == ApaceService.State.UPDATING) return;
            state = ApaceService.State.STARTING;
        }
        lastError = null;
        panelReady = false;
        acquireWakeLock();
        updateNotification("Starting Apace…");
        final PRoot.LogSink sink = new PRoot.LogSink() {
            public void onLine(String line) { log(line); }
        };
        try {
            engine.start(sink, new Runnable() {
                public void run() {
                    // process ended on its own (crash or Ctrl-C from console)
                    releaseWakeLock();
                    engine.stop(sink);
                    if (state == ApaceService.State.RUNNING || state == ApaceService.State.STARTING) {
                        setStateError("Server process exited (exit code in the console log)");
                    } else {
                        setState(ApaceService.State.STOPPED);
                    }
                    updateNotification("Server stopped");
                }
            });
        } catch (final Exception e) {
            releaseWakeLock();
            setStateError(e.getMessage());
            setState(ApaceService.State.ERROR);
            return;
        }
        // czekanie na panel (specyfikacja: „Czeka na dostępność panelu”)
        new Thread() { public void run() {
            int port = prefs.panelPort();
            long deadline = System.currentTimeMillis() + 5 * 60 * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (engine.isAlive() == false) return; // monitor zgłosi stop
                if (ServerEngine.portOpen(0, "127.0.0.1", port, 1500)) {
                    panelReady = true;
                    setState(ApaceService.State.RUNNING);
                    updateNotification("Apace is running — panel: 127.0.0.1:" + port);
                    log("[mobile] Panel ready at 127.0.0.1:" + port);
                    statsLoop();
                    return;
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
            if (engine.isAlive()) {
                setState(ApaceService.State.RUNNING); // proces żyje, panel jeszcze nie odpowiada
                statsLoop();
            }
        }}.start();
    }

    private void statsLoop() {
        final long[] last = { lastCpuJiffies < 0 ? -1 : lastCpuJiffies };
        new Thread() { public void run() {
            while (engine.isAlive()) {
                ServerEngine.Stats s = engine.stats(last[0], last);
                android.os.Bundle b = new android.os.Bundle();
                b.putFloat("cpu", s.cpuPercent);
                b.putLong("rss", s.rssBytes);
                b.putLong("uptime", s.uptimeMs);
                b.putInt("procs", s.processes);
                broadcast(null, ACTION_STATS, b);
                lastCpuJiffies = last[0];
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }}.start();
    }

    // ── Stop / Restart ─────────────────────────────────────────────────────

    private void beginStop() {
        beginStop(null);
    }

    private void beginStop(String reason) {
        synchronized (LOCK) {
            if (state == ApaceService.State.STOPPING) return;
            state = ApaceService.State.STOPPING;
        }
        updateNotification(reason == null
                ? "Stopping Apace…"
                : "Stopping Apace… (" + reason + ")");
        final PRoot.LogSink sink = new PRoot.LogSink() {
            public void onLine(String line) { log(line); }
        };
        new Thread() { public void run() {
            engine.stop(sink);
            releaseWakeLock();
            setState(ApaceService.State.STOPPED);
            updateNotification("Server stopped");
        }}.start();
    }

    /** MainActivity onStart — apka otwarta: auto-stop wyłączony, licznik wyzerowany. */
    public static void noteAppVisible() {
        appInForeground = true;
        lastAppVisibleAt = System.currentTimeMillis();
    }

    /** MainActivity onStop — apka już niewidoczna: dopiero teraz startuje licznik. */
    public static void noteAppHidden() {
        appInForeground = false;
        lastAppVisibleAt = System.currentTimeMillis();
    }

    /**
     * Po 5 min z zamkniętą apką zatrzymaj serwer panelu (PRoot + .NET z panelem
     * Solace), żeby nie marnował baterii. Zatrzymanie NIGDY nie następuje, gdy
     * apka jest otwarta (pierwszy plan), ani gdy serwer gry (JVM Minecraft)
     * działa w tle, ani gdy ktoś z zewnątrz jest połączony (Minecraft Earth /
     * klienci gry / panel w LAN). Po ponownym otwarciu apka sama uruchamia
     * serwer (MainActivity.onStart → ACTION_START).
     */
    private void autoStopIfIdle() {
        if (state != ApaceService.State.RUNNING) return;
        if (appInForeground) return;                 // apka otwarta → nigdy nie zatrzymuj
        if (System.currentTimeMillis() - lastAppVisibleAt < IDLE_STOP_MS) return;
        if (engine.javaServerRunning()) return;      // serwer gry działa w tle → nigdy
        if (engine.hasExternalConnections()) return; // aktywny klient z zewnątrz → nie zatrzymuj
        log("[mobile] Auto-stop: app closed for 5+ min, Java server off, no external clients — stopping to save battery");
        beginStop("battery saving");
    }

    private void beginStopThenStart() {
        synchronized (LOCK) {
            if (state == ApaceService.State.RUNNING || state == ApaceService.State.STARTING) {
                state = ApaceService.State.STOPPING;
            } else {
                beginStart();
                return;
            }
        }
        updateNotification("Restarting Apace…");
        final PRoot.LogSink sink = new PRoot.LogSink() {
            public void onLine(String line) { log(line); }
        };
        new Thread() { public void run() {
            engine.stop(sink);
            releaseWakeLock();
            try { Thread.sleep(1500); } catch (InterruptedException e) {}
            beginStart();
        }}.start();
    }

    // ── Aktualizacja ───────────────────────────────────────────────────────

    private void beginUpdate(final String assetUrl, final String version, final String sha256) {
        synchronized (LOCK) {
            if (state == ApaceService.State.UPDATING || state == ApaceService.State.INSTALLING) return;
            state = ApaceService.State.UPDATING;
        }
        updateNotification("Updating Apace…");
        new Thread() { public void run() {
            PRoot.LogSink sink = new PRoot.LogSink() { public void onLine(String l) { log(l); } };
            try {
                boolean wasRunning = engine.isAlive();
                if (wasRunning) {
                    log("[mobile] Stopping before update");
                    engine.stop(sink);
                    releaseWakeLock();
                }
                final Updater u = new Updater(ApaceService.this, prefs, paths, sink);
                u.run(assetUrl, version, sha256, new Updater.ProgressListener() {
                    public void phase(final String label) {
                        ui.post(new Runnable() { public void run() { updateNotification(label); }});
                        log("[update] " + label);
                    }
                    public void percent(float p) {
                        android.os.Bundle b = new android.os.Bundle();
                        b.putFloat("percent", p);
                        broadcast(null, ACTION_INSTALL_PROGRESS, b);
                    }
                });
                setState(ApaceService.State.STOPPED);
                updateNotification("Updated to " + version);
            } catch (final Exception e) {
                log("[update] ERROR: " + e.getMessage());
                setStateError(e.getMessage());
            }
        }}.start();
    }

    // ── Powiadomienia / wake lock ──────────────────────────────────────────

    private void acquireWakeLock() {
        if (!prefs.keepAwake()) return;
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ApaceMobile::server");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignore) {}
    }

    private Notification buildNotification(State s) {
        String text;
        switch (s) {
            case RUNNING: text = "Apace server is running. Panel: 127.0.0.1:" + prefs.panelPort(); break;
            case STARTING: text = "Starting the server…"; break;
            case INSTALLING: text = "Installing the environment…"; break;
            case UPDATING: text = "Updating…"; break;
            case STOPPING: text = "Stopping…"; break;
            case ERROR: text = lastError != null ? "Error: " + lastError : "Error"; break;
            default: text = "Server stopped";
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pOpen = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ApaceService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent panel = new Intent(this, MainActivity.class);
        panel.setAction(ACTION_START); // reuse: plain open
        panel.putExtra("tab", "panel");
        PendingIntent pPanel = PendingIntent.getActivity(this, 3, panel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat)
                .setChannelId(CH_STATUS) // BEZ tego startForeground rzuca RemoteServiceException na API 26+
                .setContentTitle("Apace Launcher")
                .setContentText(text)
                .setContentIntent(pOpen)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (state == ApaceService.State.RUNNING) {
            Icon ico = Icon.createWithResource(this, R.drawable.ic_stat);
            b.addAction(new Notification.Action.Builder(ico, "Open panel", pPanel).build());
            b.addAction(new Notification.Action.Builder(ico, "Stop", pStop).build());
        }
        return b.build();
    }

    private void updateNotification(String text) {
        Notification n = rebuildWithText(text);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, n);
    }

    private Notification rebuildWithText(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pOpen = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat)
                .setChannelId(CH_STATUS)
                .setContentTitle("Apace Launcher")
                .setContentText(text)
                .setContentIntent(pOpen)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (state == ApaceService.State.RUNNING) {
            Intent stop = new Intent(this, ApaceService.class);
            stop.setAction(ACTION_STOP);
            PendingIntent pStop = PendingIntent.getService(this, 2, stop,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Icon ico = Icon.createWithResource(this, R.drawable.ic_stat);
            b.addAction(new Notification.Action.Builder(ico, "Stop", pStop).build());
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        idleHandler.removeCallbacksAndMessages(null);
        instance = null;
        releaseWakeLock();
        super.onDestroy();
    }
}
