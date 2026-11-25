package com.kotpasztet.apacelauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Apace Launcher — minimal native UI styled after the Apace web panel
 * (src/Apace.LauncherUI/wwwroot/app.css: dark cards, green buttons, sharp
 * pixel corners). The app shows only: install screen (first run), progress,
 * "Apace loading…" and then the ORIGINAL Apace panel in a full-screen,
 * immersive WebView. Everything else lives in the panel itself; the ⋮ menu
 * (corner button, or Android Back inside the panel) has only the essentials.
 */
public class MainActivity extends Activity {

    // ── Apace panel design tokens (app.css) ───────────────────────────────
    static final int C_BG      = 0xFF131313; // panel background (dark + 65% black)
    static final int C_CARD    = 0xFF1A1A1A; // .card background
    static final int C_BORDER  = 0xFF3A3A3A; // .card border
    static final int C_SHADOW  = 0xFF0A0A0A; // .card box-shadow
    static final int C_TEXT    = 0xFFD0D0D0; // .card color
    static final int C_WHITE   = 0xFFFFFFFF;
    static final int C_ACCENT  = 0xFF8BC34A; // a / .btn-link green
    static final int C_GREEN   = 0xFF3B8526; // .theme-btn green
    static final int C_GREEN_P = 0xFF236212; // .theme-btn:active
    static final int C_DIM     = 0xFF9A9AB0;
    static final int C_RED     = 0xFFEF4444;

    private static final int REQ_FILE_CHOOSER = 103;

    private static final int CH_MAIN = 0; // prefs "main" — GitHub Releases (stable)
    private static final int CH_DEV  = 1; // prefs "dev"  — obraz ghcr.io/kotpasztet/apace:dev

    private Paths paths;
    private Prefs prefs;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    private FrameLayout content;
    private Button updOffer;               // „Update available” na ekranie Start (auto-check)
    private LinearLayout progBox;          // install view (when active)
    private ProgressBar progBar;
    private TextView progLabel, progPercent;
    private TextView startError;
    private ScrollView logScroll;          // live console strip
    private TextView logView;

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private Updater.CheckResult lastCheck;
    private long lastCheckAt = 0;                 // kiedy wykonano ostatni check (ms)
    private boolean updateChecking = false;    // jakiś check update'u właśnie leci (dialog/Start/onStart)
    private TextView updStatus;               // stan sprawdzenia na ekranie Start (nigdy „cicho”)
    // Live check z onStart() skończony — czeka prompt o update; pokaże go
    // tryShowUpdatePrompt(), gdy stan serwera będzie „promptowalny”.
    private boolean updatePromptPending = false;
    // Auto-start (STOPPED/ERROR): check update'u PRZED startem serwera — start
    // czeka na wynik (checkWaiter), a rozstrzyga go decideStart(). Prośba
    // użytkownika: check ma być widoczny w logach, ZANIM Apace wystartuje.
    private boolean startPendingAfterCheck = false;
    private long updateCheckDeadline = 0;         // deadline checku przed startem (epoch ms)
    private boolean preStartUpdateChosen = false; // dialog przed startem: wybrano „Update”
    // Dialog „Apace update” przed startem — referencja potrzebna, żeby przy
    // ponownym otwarciu apki zamknąć stary dialog bez wywołania startu.
    private android.app.AlertDialog preStartDialog;
    // Dialog aktualizacji otwarty w trakcie checku — pomaluj wynik, gdy check skończy się
    private TextView pendingResult;
    private Button pendingApply;
    private boolean webLoaded = false;          // panel page loaded — re-attach never reloads
    private android.os.Bundle pendingWebState;  // WebView history to restore after recreation
    private boolean menuShownAtRoot = false;

    /** Instancja aktywności — ApaceService zamyka apkę po idle auto-stop (auto-close). */
    private static volatile MainActivity instance;

    /** Auto-recheck (dev: CI jeszcze nie zbudował HEAD) — co 3 min, dopóki apka otwarta. */
    private static final long DEV_RECHECK_MS = Updater.DEV_RECHECK_MS;
    private final Runnable devRecheck = new Runnable() { public void run() {
        if (updateChecking) { ui.postDelayed(this, 15000); return; }
        if (ApaceService.state() == ApaceService.State.RUNNING) return; // nikt nie patrzy
        performUpdateCheck(null, null, true); // live — omija cache
    }};

    /**
     * Auto-start: poczekalnia na wynik checku update'u PRZED startem serwera
     * (onStart → ui.postDelayed). Poll co 300 ms; po 20 s (updateCheckDeadline)
     * startujemy i tak — dialog nie może blokować startu w nieskończoność.
     * Przy timeout flagę updatePromptPending zostawiamy: późny wynik pokaże
     * prompt przez zwykłą maszynerię (tryShowUpdatePrompt/showState), gdy
     * serwer już ruszył. Startuje wyłącznie przez decideStart().
     */
    private final Runnable checkWaiter = new Runnable() { public void run() {
        if (!startPendingAfterCheck) return; // start anulowany (np. przerwane ładowanie)
        if (updateChecking) {
            if (System.currentTimeMillis() < updateCheckDeadline) {
                ui.postDelayed(this, 300); // check jeszcze leci — czekamy dalej
                return;
            }
            consoleLog("[update] Update check timed out — starting anyway");
            startPendingAfterCheck = false;
            // updatePromptPending celowo zostaje — późny wynik i tak pokaże prompt
            svc(ApaceService.ACTION_START);
            return;
        }
        decideStart(lastCheck);
    }};

    // ── lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        paths = new Paths(this);
        prefs = new Prefs(this);
        pendingWebState = savedInstanceState != null
                ? savedInstanceState.getBundle("web") : null;
        getWindow().setStatusBarColor(C_CARD);
        getWindow().setNavigationBarColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(content);
        setContentView(root);
        showState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ApaceService.noteAppVisible(); // reset licznika idle auto-stopu
        IntentFilter f = new IntentFilter();
        f.addAction(ApaceService.ACTION_STATE);
        f.addAction(ApaceService.ACTION_LOG);
        f.addAction(ApaceService.ACTION_INSTALL_PROGRESS);
        registerReceiver(recv, f);
        // Auto-start: opening the app starts the server when it is not running.
        // Spinner od razu — bez migania guzikiem „Start Apace” (guzik zostaje
        // tylko dla stanu błędu, żeby użytkownik widział komunikat i mógł ponowić).
        // Od 1.1.13: NAJPIERW check update'u — widoczny na konsoli (prośba
        // użytkownika); start serwera czeka na wynik (max 20 s, checkWaiter →
        // decideStart), dopiero potem ACTION_START.
        ApaceService.State s = ApaceService.state();
        if (paths.isInstalled()
                && (s == ApaceService.State.STOPPED || s == ApaceService.State.ERROR)) {
            // Stale dialog z poprzedniego otwarcia apki (np. proces zabity
            // z dialogiem na ekranie) — zamknij go bez startu z onDismiss
            // (preStartUpdateChosen=true przed dismiss(); niżej wraca false).
            if (preStartDialog != null) {
                try { preStartUpdateChosen = true; preStartDialog.dismiss(); } catch (Throwable t) { }
                preStartDialog = null;
            }
            // Check update'u PRZED startem serwera — musi być widoczny w logach.
            showBusyNow("Checking for updates…");
            consoleLog("[update] Checking for updates…");
            startPendingAfterCheck = true;
            preStartUpdateChosen = false;
            // Deadline 20 s, nie 8 s: dev-check robi 4-5 sekwencyjnych HTTP po
            // kolei, a connect-timeout to 20 s — 8 s prawie zawsze dawało
            // „timed out — starting anyway” na wolniejszych sieciach.
            updateCheckDeadline = System.currentTimeMillis() + 20000;
            if (paths.isInstalled() && s != ApaceService.State.INSTALLING
                    && s != ApaceService.State.UPDATING && !updateChecking) {
                updatePromptPending = true;
                performUpdateCheck(null, null, true);
            }
            ui.postDelayed(checkWaiter, 300);
        } else {
            // Ścieżka bez auto-startu (m.in. powrót do działającego serwera):
            // zachowanie z 1.1.11 — live check + jednorazowy prompt o nowej
            // wersji. Ekran Start (z przyciskiem „Update available”) bywa przy
            // auto-starcie nieosiągalny, więc o nowej wersji musi informować
            // prompt, nie ekran. Wynik obsłuży tryShowUpdatePrompt() —
            // jednorazowo, raz na nową wersję.
            showState();
            if (paths.isInstalled()
                    && s != ApaceService.State.INSTALLING && s != ApaceService.State.UPDATING
                    && !updateChecking) {
                updatePromptPending = true;
                performUpdateCheck(null, null, true);
            }
        }
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(devRecheck); // recheck tylko przy otwartej apce
        // Strona patchera otwarta? Patch działa kilkanaście minut — auto-stop
        // zabijałby serwer w trakcie joba (na desktopie serwer nigdy nie jest
        // zatrzymywany, więc to był mobile-only failure).
        CharSequence url = webView != null ? webView.getUrl() : null;
        boolean patcherOpen = url != null
                && url.toString().toLowerCase(java.util.Locale.ROOT).contains("/patcher");
        ApaceService.noteAppHidden(patcherOpen);
        unregisterReceiver(recv);
        super.onStop();
    }

    /** Zapamiętaj stan WebView — powrót po rekreacji (np. z wybieraka plików). */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            Bundle b = new Bundle();
            webView.saveState(b);
            outState.putBundle("web", b);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /**
     * Auto-close: wywołane przez ApaceService po idle auto-stop — zamyka apkę
     * (finish + usunięcie z Recents) z wątku serwisu, stąd post na main thread.
     * Aktywność może być wtedy ukryta (STOp) — to celowe: apka ma zniknąć.
     */
    static void finishFromService() {
        final MainActivity a = instance;
        if (a == null) return;
        a.ui.post(new Runnable() { public void run() {
            try {
                a.finishAndRemoveTask();
            } catch (Throwable t) {
                try { a.finish(); } catch (Throwable ignore) {}
            }
        }});
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive(ApaceService.state() == ApaceService.State.RUNNING);
    }

    /** Full-screen panel: no status bar, no navigation bar, stable layout. */
    private void applyImmersive(boolean on) {
        View decor = getWindow().getDecorView();
        if (on) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    // ── shared UI helpers (panel style) ────────────────────────────────────

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, int color, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Small uppercase section label (like panel section headers). */
    private TextView label(String s) {
        TextView t = text(s, C_ACCENT, 10, true);
        t.setLetterSpacing(0.14f);
        return t;
    }

    /** Card background: #1a1a1a + 3px #3a3a3a border + chunky bottom shadow. */
    private LayerDrawable cardBg() {
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(C_SHADOW);
        GradientDrawable card = new GradientDrawable();
        card.setColor(C_CARD);
        card.setStroke(dp(2), C_BORDER);
        card.setCornerRadius(0f);
        LayerDrawable l = new LayerDrawable(
                new android.graphics.drawable.Drawable[]{shadow, card});
        l.setLayerInset(1, 0, 0, 0, dp(5));
        return l;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(cardBg());
        c.setPadding(dp(20), dp(18), dp(20), dp(16));
        return c;
    }

    /** Panel button (.theme-btn): green, sharp corners, bold, uppercase. */
    private Button mcButton(String label, boolean primary) {
        Button b = new Button(this, null, 0);
        b.setText(label);
        b.setAllCaps(true);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0.06f);
        b.setTextColor(primary ? C_WHITE : C_TEXT);
        b.setPadding(dp(22), dp(14), dp(22), dp(14));
        GradientDrawable n = new GradientDrawable();
        n.setColor(primary ? C_GREEN : C_CARD);
        if (!primary) n.setStroke(dp(2), C_BORDER);
        GradientDrawable p = new GradientDrawable();
        p.setColor(primary ? C_GREEN_P : 0xFF2A2A2A);
        if (!primary) p.setStroke(dp(2), C_BORDER);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, p);
        bg.addState(new int[0], n);
        b.setBackground(bg);
        return b;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(C_TEXT);
        e.setHintTextColor(C_DIM);
        e.setTextSize(12);
        e.setSingleLine(true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_SHADOW);
        g.setStroke(dp(1), C_BORDER);
        e.setBackground(g);
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        return e;
    }

    private ImageView logoBig() {
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.mipmap.ic_launcher);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(84), dp(84)));
        return iv;
    }

    /** Live console (install / start screens): monospace, auto-scroll. */
    private LinearLayout buildConsole() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(cardBg());
        box.setPadding(dp(14), dp(10), dp(14), dp(8));
        box.addView(label("CONSOLE"));
        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(C_SHADOW);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(8);
        logView.setTextColor(C_DIM);
        String[] arr = LogRing.dump().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, arr.length - 12); i < arr.length; i++) sb.append(arr[i]).append('\n');
        logView.setText(sb);
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(6);
        box.addView(logScroll, lp);
        logScroll.post(new Runnable() { public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }});
        return box;
    }

    /**
     * Dopisz linię do konsoli na ekranie (logView): obcięcie do 12 tys. znaków
     * (zachowany ogon) + autoscroll na dół. Gdy konsola nie jest na ekranie
     * (np. panel WebView) — linia jest po prostu ignorowana.
     */
    private void paintConsole(String line) {
        if (logView == null) return;
        String s = logView.getText().length() == 0
                ? line : logView.getText() + "\n" + line;
        while (s.length() > 12000 && s.indexOf('\n') >= 0) {
            s = s.substring(s.indexOf('\n') + 1);
        }
        logView.setText(s);
        logScroll.post(new Runnable() { public void run() {
            logScroll.fullScroll(View.FOCUS_DOWN);
        }});
    }

    // Linie z MainActivity muszą przeżyć rebuild ekranu — idą do LogRing (jak
    // linie serwisu) i są dorysowywane do bieżącej konsoli. Bez tego [update]…
    // znika przy pierwszym showState() (konsola jest re-seedowana z LogRing.dump()).
    private void consoleLog(String line) {
        LogRing.add(line);
        paintConsole(line);
    }


    // ── state views ────────────────────────────────────────────────────────

    private View viewInstall() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(C_BG);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(18), dp(32), dp(18), dp(24));
        sc.addView(c);

        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        card.addView(logoBig());
        TextView title = text("APACE", C_WHITE, 30, true);
        title.setLetterSpacing(0.18f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14), 0, 0);
        card.addView(title);
        TextView desc = text("Minecraft server control panel — running entirely on your phone.\nNo Termux, no Docker, no root.",
                C_DIM, 12, false);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, dp(8), 0, dp(16));
        card.addView(desc);

        card.addView(label("CHANNEL"));
        final String[] chItems = {
                "Main — stable release (recommended)",
                "Dev — official Apace dev build (dev branch)"
        };
        final int[] channelIdx = { "dev".equals(prefs.channel()) ? CH_DEV : CH_MAIN };
        final Button chBtn = mcButton(chItems[channelIdx[0]], false);
        chBtn.setAllCaps(false);
        chBtn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        chBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            new AlertDialog.Builder(MainActivity.this, R.style.ApaceDialog)
                    .setTitle("Update channel")
                    .setSingleChoiceItems(chItems, channelIdx[0], new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            channelIdx[0] = w;
                            chBtn.setText(chItems[w]);
                            d.dismiss();
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
        }});
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6);
        card.addView(chBtn, blp);

        Button install = mcButton("Install Apace", true);
        install.setTextSize(15);
        install.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            String ch = channelIdx[0] == CH_DEV ? "dev" : "main";
            prefs.channel(ch);
            confirm("Install Apace?",
                    "The app will download the environment: Ubuntu ARM64, Java 17, .NET 10 "
                            + "and the latest Apace build (~3 GB). About 3.5 GB of free space is required.\n\n"
                            + "Installation runs in the background — you can minimize the app.\n"
                            + "The server starts automatically when it finishes.",
                    new Runnable() { public void run() {
                        svc(ApaceService.ACTION_INSTALL);
                        showProgressNow(); // instant feedback on the same frame
                    }});
        }});
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(18);
        card.addView(install, ilp);

        TextView note = text("~3 GB download · ~3.5 GB free space · ARM64 only",
                C_DIM, 10, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(14), 0, 0);
        card.addView(note);
        c.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sc;
    }

    private View viewProgress() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(28), dp(14), dp(14));

        LinearLayout card = card();
        TextView title = text("INSTALLING", C_WHITE, 22, true);
        title.setLetterSpacing(0.22f);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        progLabel = text(ApaceService.lastInstallStage != null
                ? ApaceService.lastInstallStage : "Preparing…", C_ACCENT, 12, false);
        progLabel.setGravity(Gravity.CENTER);
        progLabel.setPadding(0, dp(10), 0, dp(4));
        card.addView(progLabel);
        progBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progBar.setMax(100);
        progBar.setIndeterminate(true);
        progBar.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        progBar.setIndeterminateTintList(ColorStateList.valueOf(C_ACCENT));
        progBar.setProgressBackgroundTintList(ColorStateList.valueOf(C_SHADOW));
        card.addView(progBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        progPercent = text("", C_DIM, 11, false);
        progPercent.setGravity(Gravity.CENTER);
        progPercent.setPadding(0, dp(8), 0, 0);
        card.addView(progPercent);
        // Anulowanie instalacji — akcja serwisowa istniała, ale nie miała przycisku.
        Button cancel = mcButton("Cancel install", false);
        cancel.setAllCaps(false);
        cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            confirm("Cancel installation?",
                    "The partial download will be discarded and nothing will be installed.",
                    new Runnable() { public void run() {
                        svc(ApaceService.ACTION_CANCEL_INSTALL);
                    }});
        }});
        LinearLayout.LayoutParams canlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        canlp.topMargin = dp(16);
        card.addView(cancel, canlp);
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout console = buildConsole();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = dp(14);
        root.addView(console, clp);
        progBox = root;
        return root;
    }

    private View viewBusy(String titleText, boolean interruptable) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(28), dp(14), dp(14));

        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text(titleText, C_WHITE, 20, true);
        title.setLetterSpacing(0.12f);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        ProgressBar sp = new ProgressBar(this);
        sp.setIndeterminateTintList(ColorStateList.valueOf(C_ACCENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(44), dp(44));
        slp.topMargin = dp(18);
        card.addView(sp, slp);
        if (interruptable) {
            // Przerwanie ładowania = stop serwera + przełączenie kanału dev ↔ main
            // (następny check/start pobierze build z drugiego kanału).
            final boolean toDev = !"dev".equals(prefs.channel());
            Button abort = mcButton(toDev
                    ? "Interrupt — switch to Dev (dev build)"
                    : "Interrupt — switch to Main (stable)", false);
            abort.setAllCaps(false);
            abort.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                final String chName = toDev ? "Dev (dev build)" : "Main (stable)";
                // Podwójne potwierdzenie + ostrzeżenia (spec użytkownika)
                confirm("Interrupt and switch to " + chName + "?",
                        "WARNING: the server will be stopped immediately — this load "
                                + "is lost.\n\n"
                                + "The update channel switches to " + chName + ": the next "
                                + "update will download that channel's build.",
                        new Runnable() { public void run() {
                            confirm("Are you really sure?",
                                    "Second confirmation — the interrupted load cannot be "
                                            + "resumed.\n"
                                            + "The channel change is saved right away "
                                            + "(switch back any time: Back → Update Apace).",
                                    new Runnable() { public void run() {
                                        prefs.channel(toDev ? "dev" : "main");
                                        Toast.makeText(MainActivity.this,
                                                toDev ? "Channel switched to Dev (dev build)"
                                                      : "Channel switched to Main (stable)",
                                                Toast.LENGTH_LONG).show();
                                        // przerwane ładowanie — nie startujemy po checku
                                        // (checkWaiter nigdy nie odpali ACTION_START)
                                        startPendingAfterCheck = false;
                                        svc(ApaceService.ACTION_STOP);
                                    }});
                        }});
            }});
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = dp(16);
            card.addView(abort, alp);
        }
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout console = buildConsole();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = dp(14);
        root.addView(console, clp);
        return root;
    }

    private View viewStart() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(28), dp(14), dp(14));

        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(logoBig());
        String v = InstallerEngine.readVersion(paths);
        TextView title = text("APACE " + (v != null ? v : ""), C_WHITE, 20, true);
        title.setLetterSpacing(0.12f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, 0);
        card.addView(title);
        startError = text("", C_RED, 12, false);
        startError.setGravity(Gravity.CENTER);
        startError.setPadding(0, dp(10), 0, 0);
        String err = ApaceService.lastError();
        if (ApaceService.state() == ApaceService.State.ERROR && err != null) {
            startError.setText("Error: " + err);
        }
        card.addView(startError);
        Button b = mcButton("Start Apace", true);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            svc(ApaceService.ACTION_START);
            showBusyNow("Apace loading…");
        }});
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(18);
        card.addView(b, blp);
        updOffer = mcButton("Update available", true);
        updOffer.setAllCaps(false);
        updOffer.setVisibility(View.GONE);
        updOffer.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            updateDialog();
        }});
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = dp(10);
        card.addView(updOffer, ulp);
        TextView note = text("The server also starts automatically when you open the app.",
                C_DIM, 10, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(12), 0, 0);
        card.addView(note);
        // Stan sprawdzenia aktualizacji — zawsze widoczny wynik auto-checku
        // (up to date / czekamy na CI / błąd sprawdzenia). „Brak reakcji” na
        // nowy dev-build był zgłaszanym problemem — teraz nic nie jest ciche.
        updStatus = text("", C_DIM, 10, false);
        updStatus.setGravity(Gravity.CENTER);
        updStatus.setPadding(0, dp(8), 0, 0);
        updStatus.setVisibility(View.GONE);
        card.addView(updStatus);
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout console = buildConsole();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = dp(14);
        root.addView(console, clp);
        return root;
    }

    // ── state machine ──────────────────────────────────────────────────────

    private void showState() {
        ApaceService.State s = ApaceService.state();
        // Prompt po wolnym checku z onStart(): stan wylądował dopiero teraz
        // w punkcie, w którym można pytać (np. STARTING → RUNNING/STOPPED/
        // ERROR) — pokaż go tutaj, bo w ui.post checku był jeszcze za wcześnie.
        // Dotyczy ścieżki bez auto-startu i wyniku po timeout: przy auto-starcie
        // dialog pokaże decideStart(), a tryShowUpdatePrompt jest odgrodzone.
        if (updatePromptPending && lastCheck != null) tryShowUpdatePrompt(lastCheck);
        content.removeAllViews();
        progBox = null;
        logView = null;
        logScroll = null;
        startError = null;
        updOffer = null;
        updStatus = null;
        applyImmersive(s == ApaceService.State.RUNNING);
        menuShownAtRoot = false;
        if (s == ApaceService.State.RUNNING) {
            if (webView != null && webView.getParent() == content) {
                webView.onResume(); // panel already shown — no reload
            } else {
                showWebView();
            }
            return; // no menu button over the panel (Back opens it)
        }
        switch (s) {
            case NOT_INSTALLED:
                content.addView(viewInstall());
                break;
            case INSTALLING:
                content.addView(viewProgress());
                break;
            case UPDATING:
                content.addView(viewBusy("Updating Apace…", false));
                break;
            case STARTING:
                content.addView(viewBusy("Apace loading…", true));
                break;
            case STOPPING:
                content.addView(viewBusy("Stopping Apace…", false));
                break;
            default:
                content.addView(viewStart());
                // Wynik checku ma być ZAWSZE widoczny na ekranie Start (nigdy
                // „cicho”): świeży (< 3 min) wynik malujemy od razu, starszy —
                // odświeżamy (check() sam poda wynik z cache, jeśli TTL jeszcze żyje).
                if (paths.isInstalled()) {
                    if (lastCheck != null
                            && System.currentTimeMillis() - lastCheckAt < DEV_RECHECK_MS) {
                        paintStartCheck(lastCheck);
                    } else if (!updateChecking) {
                        performUpdateCheck(null, null, false);
                    }
                }
        }
    }

    /** Render the install progress screen immediately after the click. */
    private void showProgressNow() {
        content.removeAllViews();
        progBox = null;
        logView = null;
        logScroll = null;
        content.addView(viewProgress());
    }

    /** Render the loading spinner immediately after a Start click. */
    private void showBusyNow(String label) {
        content.removeAllViews();
        progBox = null;
        logView = null;
        logScroll = null;
        content.addView(viewBusy(label, true));
    }

    // ── Panel WebView (RUNNING) — full screen, immersive, fixed size ──────

    private void showWebView() {
        if (webView == null) {
            webView = new WebView(this);
            webView.setBackgroundColor(C_SHADOW);
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);
            ws.setSavePassword(false);
            ws.setTextZoom(100); // constant WebView size regardless of system font scale
            CookieManager.getInstance().setAcceptCookie(true);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean onRenderProcessGone(WebView wv,
                        android.webkit.RenderProcessGoneDetail detail) {
                    // renderer padł (zwykle OOM) — odbuduj WebView zamiast zostawiać
                    // martwą stronę albo crashować apkę
                    if (webView != null) {
                        content.removeAllViews();
                        webView.destroy();
                        webView = null;
                        webLoaded = false;
                        showWebView();
                    }
                    return true;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView wv, String url) {
                    if (isLocalPanel(url)) return false; // only the local panel
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Blocked: " + url, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            });
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb,
                                                 FileChooserParams params) {
                    if (fileChooserCallback != null) {
                        fileChooserCallback.onReceiveValue(null); // dokładnie jeden wynik na callback
                    }
                    fileChooserCallback = cb;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    try {
                        startActivityForResult(i, REQ_FILE_CHOOSER);
                    } catch (Exception e) {
                        fileChooserCallback = null;
                        return false;
                    }
                    return true;
                }

                // Stylowane dialogi (panel Apace) zamiast domyślnych systemowych monitów
                @Override
                public boolean onJsAlert(WebView wv, String url, String msg,
                                         final JsResult result) {
                    new AlertDialog.Builder(MainActivity.this, R.style.ApaceDialog)
                            .setTitle("Apace panel").setMessage(msg)
                            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { result.cancel(); }
                            })
                            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                public void onCancel(DialogInterface d) { result.cancel(); }
                            }).show();
                    return true;
                }

                @Override
                public boolean onJsConfirm(WebView wv, String url, String msg,
                                           final JsResult result) {
                    new AlertDialog.Builder(MainActivity.this, R.style.ApaceDialog)
                            .setTitle("Apace panel").setMessage(msg)
                            .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { result.confirm(); }
                            })
                            .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { result.cancel(); }
                            })
                            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                public void onCancel(DialogInterface d) { result.cancel(); }
                            }).show();
                    return true;
                }

                @Override
                public boolean onJsPrompt(WebView wv, String url, String msg, String def,
                                          final JsPromptResult result) {
                    final EditText input = field("");
                    if (def != null) input.setText(def);
                    LinearLayout box = new LinearLayout(MainActivity.this);
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.setPadding(dp(20), dp(8), dp(20), 0);
                    box.addView(text(msg != null ? msg : "", C_TEXT, 13, false));
                    box.addView(input, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    new AlertDialog.Builder(MainActivity.this, R.style.ApaceDialog)
                            .setTitle("Apace panel").setView(box)
                            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    result.confirm(input.getText().toString());
                                }
                            })
                            .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { result.cancel(); }
                            }).show();
                    return true;
                }
            });
            webView.setDownloadListener(new DownloadListener() {
                public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                    startPanelDownload(url, ua, cd, mime);
                }
            });
        }
        content.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.onResume();
        if (webLoaded) return; // re-attach: NIGDY nie przeładowuj — reset zmienia panel na stronę główną
        if (pendingWebState != null && pendingWebState.size() > 0) {
            webView.restoreState(pendingWebState); // powrót po rekreacji aktywności
        } else {
            webView.loadUrl("http://127.0.0.1:" + prefs.panelPort());
        }
        pendingWebState = null;
        webLoaded = true;
    }

    // ── Download z panelu (zpatchowany klient MCE z patchera itp.) ────────

    /**
     * Download z panelu WebView. Dwa tory:
     *
     * 1. Przekazanie URL do zewnętrznej przeglądarki (Chrome itp.) przez
     *    ACTION_VIEW — linki downloadu patchera są PODPISANE tokenem w samym
     *    URL-u (PatcherDownloadTokens po stronie serwera), więc przeglądarka
     *    pobiera plik bez cookie sesji panelu. Android prowadzi wtedy swój
     *    systemowy download (powiadomienie z progresem), a tapnięcie
     *    ukończonego pliku otwiera systemowe menu „co zrobić z plikiem”
     *    (dla .apk: instalator pakietów).
     *
     * 2. Awaryjnie (brak jakiejkolwiek przeglądarki): systemowy DownloadManager
     *    z przekazanym cookie sesji WebView (stare, niepodpisane URL-e też
     *    przejdą autoryzację) i poprawnym MIME — plik ląduje w publicznym
     *    katalogu Download, tapnięcie powiadomienia otwiera chooser. Gdy
     *    publiczny katalog nie jest dostępny (brak zgody storage na starym
     *    Androidzie), ostatnią deską ratunku jest katalog prywatny aplikacji
     *    (jak dotychczas) — download zawsze kończy się plikiem na dysku.
     */
    private void startPanelDownload(String url, String ua, String cd, String mime) {
        final String name = downloadFileName(cd, url, mime);
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Toast.makeText(MainActivity.this,
                        "Download opened in your browser: " + name, Toast.LENGTH_LONG).show();
                return;
            } catch (Exception e) {
                // brak przeglądarki na urządzeniu — DownloadManager poniżej
            }
        }
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request rq = buildDownloadRequest(url, ua, mime);
            rq.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, name);
            dm.enqueue(rq);
            Toast.makeText(MainActivity.this,
                    "Downloading: " + name + " (Download folder)", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            // publiczny Download niedostępny (storage/permission) — katalog
            // prywatny aplikacji zawsze działa; toast pokazuje gdzie plik jest
            try {
                android.app.DownloadManager dm = (android.app.DownloadManager)
                        getSystemService(DOWNLOAD_SERVICE);
                android.app.DownloadManager.Request rq = buildDownloadRequest(url, ua, mime);
                rq.setDestinationInExternalFilesDir(MainActivity.this,
                        android.os.Environment.DIRECTORY_DOWNLOADS, name);
                dm.enqueue(rq);
                Toast.makeText(MainActivity.this,
                        "Downloading: " + name + " (app private folder: "
                                + "Android/data/" + getPackageName() + "/files/Download)",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                Toast.makeText(MainActivity.this,
                        "Download failed: " + e2.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Request DownloadManagera: cookie sesji WebView (autoryzacja starych,
     *  niepodpisanych URL-i) + User-Agent + MIME z odpowiedzi serwera. */
    private android.app.DownloadManager.Request buildDownloadRequest(String url, String ua, String mime) {
        android.app.DownloadManager.Request rq =
                new android.app.DownloadManager.Request(Uri.parse(url));
        if (ua != null && ua.length() > 0) rq.addRequestHeader("User-Agent", ua);
        try {
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && cookies.length() > 0) rq.addRequestHeader("Cookie", cookies);
        } catch (Exception ignore) {}
        if (mime != null && mime.length() > 0
                && !"application/octet-stream".equals(mime)) {
            rq.setMimeType(mime); // np. apk → tapnięcie powiadomienia otwiera instalator
        }
        rq.setNotificationVisibility(android.app.DownloadManager.Request
                .VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        return rq;
    }

    /**
     * Nazwa pliku z Content-Disposition (RFC 6266: „filename*=UTF-8''…”
     * ma pierwszeństwo przed „filename=…”), potem ostatni segment URL-a,
     * na końcu znacznik czasu + rozszerzenie z MIME. Poprzedni parser
     * urywał nazwę na średniku i doklejał resztę nagłówka („apace.apk
     * filename*=utf-8''apace.apk”) albo generował „…timestamp.bin”.
     */
    private static String downloadFileName(String cd, String url, String mime) {
        if (cd != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("filename\\*=\\s*UTF-8''([^;]+)",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(cd);
            if (m.find()) {
                String n = pctDecode(m.group(1).trim());
                if (n.length() > 0) return cleanFileName(n);
            }
            m = java.util.regex.Pattern
                    .compile("filename=\\s*\"([^\"]*)\"",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(cd);
            if (m.find()) {
                String n = m.group(1).trim();
                if (n.length() > 0) return cleanFileName(n);
            }
            m = java.util.regex.Pattern
                    .compile("filename=\\s*([^;\"]+)",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(cd);
            if (m.find()) {
                String n = m.group(1).trim();
                if (n.length() > 0) return cleanFileName(n);
            }
        }
        if (url != null) {
            String u = url;
            int q = u.indexOf('?');
            if (q >= 0) u = u.substring(0, q);
            int s = u.lastIndexOf('/');
            if (s >= 0 && s + 1 < u.length()) {
                String seg = u.substring(s + 1);
                // tylko segment wyglądający na nazwę pliku (z kropką) — segmenty
                // typu token/id (trasa /dl/{job}/{expiry}/{token}) są bez sensu
                // jako nazwa; wtedy spada do znacznika czasu + rozszerzenie z MIME
                if (seg.indexOf('.') >= 0) return cleanFileName(seg);
            }
        }
        String ext = ".bin";
        if ("application/vnd.android.package-archive".equals(mime)) ext = ".apk";
        else if (mime != null && mime.toLowerCase(java.util.Locale.ROOT).contains("zip")) ext = ".zip";
        return "apace-download-" + System.currentTimeMillis() + ext;
    }

    /**
     * Bezpieczna nazwa pliku: bez separatorów ścieżek i null-bajtów
     * (Content-Disposition kontroluje serwer — jak w tar-slip), bez
     * nazw katalogowych („.”/„..”), maks. ~120 znaków.
     */
    private static String cleanFileName(String n) {
        n = n.replace("/", "_").replace("\\", "_").replace("\u0000", "").trim();
        if (n.length() == 0 || ".".equals(n) || "..".equals(n)) n = "download";
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n;
    }

    /** percent-decode bez zamiany „+” na spację (RFC 5987 koduje wyłącznie %-escape'y). */
    private static String pctDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private boolean isLocalPanel(String url) {
        if (url == null) return false;
        return url.startsWith("http://127.0.0.1:" + prefs.panelPort())
                || url.startsWith("http://localhost:" + prefs.panelPort())
                || url.startsWith("about:");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Przycisk menu (⋮) usunięty — Back otwiera menu w każdym stanie.
            // Panel: najpierw historia strony, potem menu, na końcu minimalizacja.
            if (ApaceService.state() == ApaceService.State.RUNNING
                    && webView != null && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            if (menuShownAtRoot) {
                moveTaskToBack(true);
                return true;
            }
            menuShownAtRoot = true;
            openMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── ⋮ menu ─────────────────────────────────────────────────────────────

    /**
     * Menu (Back) — tylko to, czego panel WWW nie ma: aktualizacje Apace
     * (kanał dev/main) i licencje. Stop, konsola, backup i ustawienia
     * są dostępne w samym panelu.
     */
    private void openMenu() {
        final String updLabel = "Update Apace"
                + (lastCheck != null && lastCheck.available && lastCheck.latest != null
                        ? " (" + lastCheck.latest + " available)" : "");
        new AlertDialog.Builder(this, R.style.ApaceDialog)
                .setTitle("Apace Launcher")
                .setItems(new String[]{
                        updLabel,
                        "Licenses"
                }, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) updateDialog();
                        else licenseDialog();
                    }
                }).show();
    }

    private void updateDialog() {
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        String cur = InstallerEngine.readVersion(paths);
        final TextView info = text(channelInfoLine(cur), C_TEXT, 13, false);
        box.addView(info);

        final TextView result = text("", C_DIM, 12, false);
        result.setPadding(0, dp(10), 0, 0);
        box.addView(result);
        final Button apply = mcButton("Update", true);
        apply.setEnabled(false);
        apply.setAlpha(0.4f);
        box.addView(apply, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        apply.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (lastCheck == null || !lastCheck.available || lastCheck.assetUrl == null) return;
            holder[0].dismiss();
            confirm("Update to " + lastCheck.latest + "?",
                    "The server will be stopped and a backup created "
                            + "(automatic rollback on failure). Continue?",
                    new Runnable() { public void run() {
                        svcUpdate(lastCheck.assetUrl, lastCheck.latest, lastCheck.sha256);
                    }});
        }});
        Button check = mcButton("Check for updates", false);
        check.setAllCaps(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(12), 0, dp(12));
        box.addView(check, clp);
        check.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            performUpdateCheck(result, apply, true); // przycisk = zawsze na żywo
        }});
        // Kanał dev ↔ main — przełącza źródło pobierania (Updater + InstallerEngine)
        // i od razu ponawia check dla wybranego kanału.
        box.addView(channelPicker(info, cur, result, apply));
        holder[0] = new AlertDialog.Builder(this, R.style.ApaceDialog)
                .setTitle("Update Apace")
                .setView(box)
                .setNegativeButton("Close", null)
                .show();
        performUpdateCheck(result, apply, true); // auto-check od razu po otwarciu (live)
    }

    /** Wybór kanału w dialogu aktualizacji: Main (GitHub Releases) | Dev (ghcr :dev). */
    private LinearLayout channelPicker(final TextView info, final String cur,
                                       final TextView result, final Button apply) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final Button main = mcButton("Main (stable)", false);
        final Button dev = mcButton("Dev (dev build)", false);
        main.setAllCaps(false);
        dev.setAllCaps(false);
        paintChannelChips(main, dev);
        main.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (!"main".equals(prefs.channel())) {
                prefs.channel("main");
                channelChanged(info, cur, main, dev, result, apply);
            }
        }});
        dev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (!"dev".equals(prefs.channel())) {
                prefs.channel("dev");
                channelChanged(info, cur, main, dev, result, apply);
            }
        }});
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMargins(0, dp(14), dp(6), 0);
        row.addView(main, lp1);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.setMargins(dp(6), dp(14), 0, 0);
        row.addView(dev, lp2);
        return row;
    }

    private void channelChanged(TextView info, String cur, Button main, Button dev,
                                TextView result, Button apply) {
        paintChannelChips(main, dev);
        info.setText(channelInfoLine(cur));
        performUpdateCheck(result, apply, true);
    }

    private void paintChannelChips(Button main, Button dev) {
        boolean isDev = "dev".equals(prefs.channel());
        main.setAlpha(isDev ? 0.45f : 1f);
        dev.setAlpha(isDev ? 1f : 0.45f);
    }

    /** Linia „Installed / Channel” w dialogu aktualizacji. */
    private String channelInfoLine(String cur) {
        return "Installed: " + (cur != null ? cur : "—")
                + "\nChannel: " + ("dev".equals(prefs.channel()) ? "Dev (dev-build)" : "Main (stable)");
    }

    /** Krótka linia stanu sprawdzenia — nigdy „cicho”. */
    private String checkStateLine(Updater.CheckResult r) {
        switch (r.status) {
            case Updater.OK_AVAILABLE:  return "UPDATE AVAILABLE";
            case Updater.OK_UPTODATE:  return "up to date";
            case Updater.OK_CI_PENDING: return "waiting for CI (image older than dev HEAD)";
            default: return "CHECK FAILED — press Check for updates to retry";
        }
    }

    /** Pierwsza linia komunikatu (do jednolinijkowego stanu na ekranie Start). */
    private static String firstLine(String s, int max) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        String line = nl >= 0 ? s.substring(0, nl) : s;
        return line.length() > max ? line.substring(0, max) + "…" : line;
    }

    /**
     * Wspólny check update'u: przycisk w dialogu (na żywo), auto po otwarciu
     * dialogu, auto na ekranie Start (z cache 4 min) oraz LIVE przy otwarciu
     * apki. Przy auto-starcie (STOPPED/ERROR) check idzie PRZED startem
     * serwera, a wynik rozstrzyga decideStart() (checkWaiter czeka na niego);
     * w pozostałych przypadkach jednorazowy prompt obsłuży tryShowUpdatePrompt.
     * Wynik jest ZAWSZE widoczny: prompt, przycisk „Update available”,
     * linia stanu albo błąd z retry. Gdy CI jeszcze nie zbudował HEAD dev —
     * automatyczny re-check co 3 min.
     *
     * Check w locie? Dialog, który właśnie otwarto, dopisuje się do kolejki
     * (pendingResult) — wbrew starszej wersji NIE zostaje na „Checking…”.
     */
    private void performUpdateCheck(final TextView result, final Button apply, final boolean force) {
        if (updateChecking) {
            // check już leci (np. auto z ekranu Start, a user otworzył dialog)
            // — pomaluj ten widok, gdy check się skończy
            if (result != null) {
                result.setText("Checking…");
                pendingResult = result;
                pendingApply = apply;
            }
            return;
        }
        updateChecking = true;
        final String checkedChannel = prefs.channel();
        if (result != null) result.setText("Checking…");
        new Thread() { public void run() {
            Updater u = new Updater(MainActivity.this, prefs, paths, null);
            final Updater.CheckResult r = force ? u.checkNow() : u.check();
            final boolean channelSwitched = !checkedChannel.equals(prefs.channel());
            ui.post(new Runnable() { public void run() {
                updateChecking = false;
                if (channelSwitched) {
                    // kanał przełączony w trakcie checku (chips) — wynik nieaktualny;
                    // jeśli w kolejce czeka dialog — sprawdź od razu dla NOWEGO kanału
                    if (pendingResult != null) {
                        TextView pr = pendingResult; Button pa = pendingApply;
                        pendingResult = null; pendingApply = null;
                        performUpdateCheck(pr, pa, true);
                    }
                    if (startPendingAfterCheck) {
                        consoleLog("[update] Channel switched during check — re-checking");
                        updateCheckDeadline = System.currentTimeMillis() + 20000;
                        performUpdateCheck(null, null, true);
                    }
                    return;
                }
                lastCheck = r;
                lastCheckAt = System.currentTimeMillis();
                // live check z onStart() skończony — jednorazowy prompt o nowej
                // wersji (jeśli stan serwera pozwala; inaczej showState() ponowi)
                if (updatePromptPending) tryShowUpdatePrompt(r);
                if (result != null) {
                    result.setText((r.message != null ? r.message + "\n" : "")
                            + "Latest: " + r.latest + "  →  " + checkStateLine(r));
                }
                if (apply != null) {
                    boolean can = r.available && r.assetUrl != null;
                    apply.setEnabled(can);
                    apply.setAlpha(can ? 1f : 0.4f);
                }
                // dialog, który przyszedł w trakcie checku — ten sam wynik
                if (pendingResult != null) {
                    TextView pr = pendingResult; Button pa = pendingApply;
                    pendingResult = null; pendingApply = null;
                    pr.setText((r.message != null ? r.message + "\n" : "")
                            + "Latest: " + r.latest + "  →  " + checkStateLine(r));
                    if (pa != null) {
                        boolean can = r.available && r.assetUrl != null;
                        pa.setEnabled(can);
                        pa.setAlpha(can ? 1f : 0.4f);
                    }
                }
                paintStartCheck(r);
                // dev: CI nie zbudował HEAD → odśwież wynik, gdy build się pojawi
                ui.removeCallbacks(devRecheck);
                if (r.ciPending && ApaceService.state() != ApaceService.State.RUNNING) {
                    ui.postDelayed(devRecheck, DEV_RECHECK_MS);
                }
            }});
        }}.start();
    }

    /**
     * Prompt o update przy otwarciu apki: pokazuje się RAZ na nową wersję
     * (Prefs.lastPromptedVersion — „Later” wycisza ją do następnej wersji) i
     * TYLKO gdy webserver jest off (STOPPED/ERROR) — otwarcie apki przy
     * działającym serwerze nigdy nie wypisuje dialogu (przycisk „Update
     * available” na ekranie Start zostaje). Odpalany po live-checku z
     * onStart(): bezpośrednio z ui.post w performUpdateCheck albo z
     * showState(), gdy serwer wyszedł dopiero teraz ze STARTING. Ekran Start
     * z przyciskiem „Update available” jest przy auto-starcie nieosiągalny,
     * więc wynik checku pokazujemy dialogiem, niezależnie od tego, co akurat
     * jest na ekranie.
     *
     * STARTING/STOPPING → BEZ konsumpcji flagi (showState() ponowi; stop w toku
     * nie może dostać update'u), INSTALLING/UPDATING → BEZ konsumpcji (ponowimy przy następnym otwarciu
     * apki), każdy inny stan konsumuje flagę. Dialog tylko przy dostępnej
     * nowej wersji, webserver off, bez aktywnego snooze i bez wcześniejszego
     * promptu dla tej wersji — reszta (up to date / CI pending / błąd)
     * zostaje na ekranie Start i w menu „Update Apace”.
     *
     * W trakcie checku przed startem (auto-start, startPendingAfterCheck)
     * naturalnie wychodzimy — ten flow pokaże dialog sam, w decideStart();
     * bez tego guardu ui.post z checku pokazałby dialog drugi raz.
     */
    private void tryShowUpdatePrompt(final Updater.CheckResult r) {
        if (startPendingAfterCheck) return; // dialog przed startem pokaże decideStart()
        ApaceService.State s = ApaceService.state();
        if (s == ApaceService.State.STARTING || s == ApaceService.State.STOPPING) return; // showState() ponowi
        if (s == ApaceService.State.INSTALLING || s == ApaceService.State.UPDATING) return;
        updatePromptPending = false;
        if (r == null || !(r.status == Updater.OK_AVAILABLE
                && r.assetUrl != null && r.latest != null)) {
            return; // brak nowej wersji / CI pending / błąd — nie pytamy
        }
        // webserver działa → bez dialogu (prompt tylko przy otwarciu z serwerem
        // off); przycisk „Update available” na ekranie Start zostaje
        if (s != ApaceService.State.STOPPED && s != ApaceService.State.ERROR) return;
        // prompt dla tej wersji już był — nie pytamy przy każdym otwarciu
        if (prefs.lastPromptedVersion().equals(r.latest)) return;
        if (prefs.isSnoozed(r.latest)) return; // ta wersja ma aktywne 7-dniowe snooze
        if (isFinishing()) return; // zamykana aktywność — zapytamy przy następnym otwarciu
        String cur = InstallerEngine.readVersion(paths);
        prefs.lastPromptedVersion(r.latest); // pokazany → jedyny prompt dla tej wersji
        new AlertDialog.Builder(this, R.style.ApaceDialog)
                .setTitle("Apace update")
                .setMessage("Update available: " + r.latest
                        + "\nInstalled: " + (cur != null ? cur : "—")
                        + "\n\nThe server will be stopped and a backup created "
                        + "(automatic rollback on failure).")
                .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        // ApaceService.beginUpdate sam zatrzyma działający serwer
                        svcUpdate(r.assetUrl, r.latest, r.sha256);
                    }
                })
                .setNeutralButton("Don't show for 7 days", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        prefs.snooze(r.latest, System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000);
                        consoleLog("[update] Update " + r.latest + " hidden until "
                                + new java.util.Date(prefs.snoozeUntilMs()));
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    /**
     * Rozstrzygnięcie auto-startu po checku PRZED startem serwera (checkWaiter).
     * Ten flow jest jedynym właścicielem promptu: sam konsumuje flagę
     * updatePromptPending (tryShowUpdatePrompt jest odgrodzone flagą
     * startPendingAfterCheck, żeby ui.post z checku nie pokazał dialogu drugi
     * raz). Wynik checku trafia do konsoli („[update] …”), potem: nowa wersja
     * (niesnoozowana) → dialog przed startem; w każdym innym
     * przypadku → zwykły start serwera.
     */
    private void decideStart(Updater.CheckResult r) {
        startPendingAfterCheck = false;
        updatePromptPending = false; // ten flow sam decyduje o prompcie
        if (r != null) {
            if (r.status == Updater.OK_AVAILABLE) {
                consoleLog("[update] Update available: " + r.latest
                        + " (installed " + InstallerEngine.readVersion(paths) + ")");
            } else if (r.status == Updater.OK_UPTODATE) {
                consoleLog("[update] Apace is up to date (" + r.latest + ")");
            } else if (r.status == Updater.OK_CI_PENDING) {
                consoleLog("[update] Waiting for CI — image older than dev HEAD ("
                        + (r.headShort != null ? r.headShort : "newest commit") + ")");
            } else {
                consoleLog("[update] Update check failed: " + firstLine(r.message, 120));
            }
        }
        boolean avail = r != null && r.status == Updater.OK_AVAILABLE
                && r.assetUrl != null && r.latest != null;
        if (avail && prefs.isSnoozed(r.latest)) {
            consoleLog("[update] Update " + r.latest + " snoozed — starting");
            svc(ApaceService.ACTION_START);
            return;
        }
        // prompt dla tej wersji już był pokazany — nie pytamy przy każdym
        // otwarciu apki, startujemy od razu (przycisk „Update available” na
        // ekranie Start nadal pozwala zaktualizować ręcznie)
        if (avail && prefs.lastPromptedVersion().equals(r.latest)) {
            consoleLog("[update] Update " + r.latest
                    + " available — prompt already shown for this version, starting");
            svc(ApaceService.ACTION_START);
            return;
        }
        if (avail) { showPreStartUpdateDialog(r); return; }
        svc(ApaceService.ACTION_START);
    }

    /**
     * Dialog „Update available” PRZED startem serwera (auto-start). KAŻDA
     * ścieżka domyka start: „Update” → svcUpdate (serwis sam zatrzyma serwer
     * i zrobi backup), „Later”/dismiss poza przyciskiem → zwykły ACTION_START
     * (onDismiss), „Don't show for 7 days” → snooze tej wersji + ACTION_START
     * (onDismiss). Zamykana aktywność → od razu start — proces nigdy nie
     * utyka na „Checking for updates…” bez odpowiedzi. Pokazany dialog
     * zapisuje wersję w Prefs.lastPromptedVersion — RAZ na wersję (patrz
     * tryShowUpdatePrompt).
     */
    private void showPreStartUpdateDialog(final Updater.CheckResult r) {
        if (isFinishing()) { // aktywność się zamyka — start bez dialogu
            svc(ApaceService.ACTION_START);
            return;
        }
        String cur = InstallerEngine.readVersion(paths);
        prefs.lastPromptedVersion(r.latest); // pokazany → jedyny prompt dla tej wersji
        AlertDialog dlg = new AlertDialog.Builder(this, R.style.ApaceDialog)
                .setTitle("Apace update")
                .setMessage("Update available: " + r.latest
                        + "\nInstalled: " + (cur != null ? cur : "—")
                        + "\n\nThe update stops the server and creates a backup "
                        + "(automatic rollback on failure).")
                .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        // ApaceService.beginUpdate sam zatrzyma działający serwer;
                        // onDismiss po tym NIE startuje (preStartUpdateChosen)
                        preStartUpdateChosen = true;
                        svcUpdate(r.assetUrl, r.latest, r.sha256);
                    }
                })
                .setNegativeButton("Later", null) // zwykły dismiss → start w onDismiss
                .setNeutralButton("Don't show for 7 days", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        prefs.snooze(r.latest, System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000);
                        consoleLog("[update] Update " + r.latest + " hidden until "
                                + new java.util.Date(prefs.snoozeUntilMs()));
                    }
                })
                .setCancelable(true)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface d) {
                        preStartDialog = null;
                        if (!preStartUpdateChosen) svc(ApaceService.ACTION_START);
                    }
                })
                .create();
        preStartDialog = dlg;
        dlg.show();
    }

    /** Maluje wynik checku na ekranie Start (przycisk „Update available” + linia stanu). */
    private void paintStartCheck(Updater.CheckResult r) {
        if (updOffer != null) {
            boolean show = r.available && r.assetUrl != null;
            if (show) updOffer.setText("Update available: " + r.latest);
            updOffer.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (updStatus != null) {
            String s = null;
            if (r.status == Updater.OK_UPTODATE) {
                s = "Apace is up to date" + (r.latest != null ? " (" + r.latest + ")" : "");
            } else if (r.status == Updater.OK_CI_PENDING) {
                s = "Dev: CI has not built "
                        + (r.headShort != null ? r.headShort : "the newest commit")
                        + " yet — re-checking in " + (DEV_RECHECK_MS / 60000L) + " min";
            } else if (r.status == Updater.OK_AVAILABLE) {
                s = null; // przycisk „Update available” mówi wszystko
            } else if (r.status == Updater.ERROR) {
                s = "Update check failed: " + firstLine(r.message, 90)
                        + " — open Update Apace to retry";
            }
            updStatus.setText(s != null ? s : "");
            updStatus.setVisibility(s != null ? View.VISIBLE : View.GONE);
        }
    }

    private void licenseDialog() {
        try {
            java.io.InputStream in = getAssets().open("NOTICE.txt");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            new AlertDialog.Builder(this, R.style.ApaceDialog).setTitle("Licenses")
                    .setMessage(new String(bo.toByteArray(), "UTF-8"))
                    .setPositiveButton("OK", null).show();
        } catch (Exception e) {
            Toast.makeText(this, "NOTICE.txt missing", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirm(String title, String msg, final Runnable onYes) {
        new AlertDialog.Builder(this, R.style.ApaceDialog)
                .setTitle(title).setMessage(msg)
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { onYes.run(); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void svc(String action) {
        Intent i = new Intent(this, ApaceService.class);
        i.setAction(action);
        startForegroundService(i);
    }

    private void svcUpdate(String url, String version, String sha) {
        Intent i = new Intent(this, ApaceService.class);
        i.setAction(ApaceService.ACTION_UPDATE);
        i.putExtra("assetUrl", url);
        i.putExtra("version", version);
        i.putExtra("sha256", sha);
        startForegroundService(i);
    }

    // ── broadcasts ─────────────────────────────────────────────────────────

    private final BroadcastReceiver recv = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ApaceService.ACTION_STATE.equals(a)) {
                // NIE wolno tu ponownie wołać setStateError z extra „error” — serwis
                // rozgłasza stan z błędem, a echo wołałoby setStateError w nieskończoną
                // pętlę broadcastów (freeze + „app crashed” przy otwarciu apki).
                // lastError jest już ustawione w serwisie i viewStart() je czyta.
                showState();
            } else if (ApaceService.ACTION_INSTALL_PROGRESS.equals(a)) {
                if (progBox != null) {
                    String lbl = i.getStringExtra("label");
                    int stage = i.getIntExtra("stage", -1);
                    if (lbl != null) { // new stage → bar back to "working"
                        progLabel.setText(stage > 0 ? "(" + stage + "/10) " + lbl : lbl);
                        progBar.setIndeterminate(true);
                        progPercent.setText("");
                    }
                    if (i.hasExtra("percent")) {
                        float p = i.getFloatExtra("percent", 0);
                        if (p > 0) {
                            progBar.setIndeterminate(false);
                            progBar.setProgress((int) (p * 100));
                            progPercent.setText("Downloading: " + (int) (p * 100) + "%");
                        }
                    }
                }
            } else if (ApaceService.ACTION_LOG.equals(a)) {
                String line = i.getStringExtra("line");
                // Linia jest już w LogRing (dodał ją ApaceService.log) — tylko dorysuj.
                if (line != null) paintConsole(line);
            }
        }
    };

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE_CHOOSER) {
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(
                        res == RESULT_OK && data != null && data.getData() != null
                                ? new Uri[]{ data.getData() } : null);
                fileChooserCallback = null;
            }
        } else {
            super.onActivityResult(req, res, data);
        }
    }
}
