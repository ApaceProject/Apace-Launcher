package org.apacemobile;

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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

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

    private static final int REQ_EXPORT = 101;
    private static final int REQ_IMPORT = 102;
    private static final int REQ_FILE_CHOOSER = 103;

    private static final int CH_MAIN = 0; // prefs "stable" — GitHub Releases
    private static final int CH_DEV  = 1; // prefs "main"   — CI manifest

    private Paths paths;
    private Prefs prefs;
    private BackupManager backups;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    private FrameLayout content;
    private Button menuBtn;                // corner ⋮ (hidden while panel shown)
    private LinearLayout progBox;          // install view (when active)
    private ProgressBar progBar;
    private TextView progLabel, progPercent;
    private TextView startError;
    private ScrollView logScroll;          // live console strip
    private TextView logView;

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private File exportBackup;
    private Updater.CheckResult lastCheck;
    private boolean menuShownAtRoot = false;
    private long lastStopRequestedAt = 0;

    // ── lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        paths = new Paths(this);
        prefs = new Prefs(this);
        backups = new BackupManager(paths);
        getWindow().setStatusBarColor(C_CARD);
        getWindow().setNavigationBarColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(content);
        menuBtn = buildMenuButton();
        setContentView(root);
        showState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(ApaceService.ACTION_STATE);
        f.addAction(ApaceService.ACTION_LOG);
        f.addAction(ApaceService.ACTION_INSTALL_PROGRESS);
        registerReceiver(recv, f);
        // Auto-start: opening the app starts the server when it is not running.
        ApaceService.State s = ApaceService.state();
        if (paths.isInstalled()
                && (s == ApaceService.State.STOPPED || s == ApaceService.State.ERROR)
                && System.currentTimeMillis() - lastStopRequestedAt > 10000) {
            svc(ApaceService.ACTION_START);
        }
        showState();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(recv);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
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

    private Button buildMenuButton() {
        Button b = new Button(this, null, 0);
        b.setText("⋮");
        b.setTextSize(20);
        b.setTextColor(C_TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_CARD);
        g.setStroke(dp(2), C_BORDER);
        b.setBackground(g);
        b.setPadding(0, 0, 0, 0);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openMenu(); }});
        return b;
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
                "Dev — CI build (needs manifest URL)"
        };
        final int[] channelIdx = { CH_MAIN };
        final LinearLayout devBox = new LinearLayout(this);
        devBox.setOrientation(LinearLayout.VERTICAL);
        devBox.setVisibility(View.GONE);
        final Button chBtn = mcButton(chItems[channelIdx[0]], false);
        chBtn.setAllCaps(false);
        chBtn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        chBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Update channel")
                    .setSingleChoiceItems(chItems, channelIdx[0], new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            channelIdx[0] = w;
                            chBtn.setText(chItems[w]);
                            devBox.setVisibility(w == CH_DEV ? View.VISIBLE : View.GONE);
                            d.dismiss();
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
        }});
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6);
        card.addView(chBtn, blp);

        TextView manifestLabel = label("CI MANIFEST URL (DEV)");
        manifestLabel.setPadding(0, dp(10), 0, 0);
        devBox.addView(manifestLabel);
        final EditText manifestUrl = field("https://<user>.github.io/<apace-fork>/main-manifest.json");
        manifestUrl.setText(prefs.mainManifestUrl());
        devBox.addView(manifestUrl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(devBox);

        Button install = mcButton("Install Apace", true);
        install.setTextSize(15);
        install.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            String ch = channelIdx[0] == CH_DEV ? "main" : "stable";
            prefs.channel(ch);
            if (channelIdx[0] == CH_DEV) {
                String u = manifestUrl.getText().toString().trim();
                if (u.length() == 0) {
                    Toast.makeText(MainActivity.this,
                            "The Dev channel needs a CI manifest URL", Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.mainManifestUrl(u);
            }
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

    private View viewBusy(String titleText) {
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
        TextView note = text("The server also starts automatically when you open the app.",
                C_DIM, 10, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(12), 0, 0);
        card.addView(note);
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
        content.removeAllViews();
        progBox = null;
        logView = null;
        logScroll = null;
        startError = null;
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
                content.addView(viewBusy("Updating Apace…"));
                break;
            case STARTING:
                content.addView(viewBusy("Apace loading…"));
                break;
            case STOPPING:
                content.addView(viewBusy("Stopping Apace…"));
                break;
            default:
                content.addView(viewStart());
        }
        content.addView(menuBtn, menuBtnParams());
    }

    private FrameLayout.LayoutParams menuBtnParams() {
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(dp(44), dp(44));
        ml.gravity = Gravity.TOP | Gravity.END;
        ml.setMargins(0, dp(12), dp(12), 0);
        return ml;
    }

    /** Render the install progress screen immediately after the click. */
    private void showProgressNow() {
        content.removeAllViews();
        progBox = null;
        logView = null;
        logScroll = null;
        content.addView(viewProgress());
        content.addView(menuBtn, menuBtnParams());
    }

    /** Render the loading spinner immediately after a Start click. */
    private void showBusyNow(String label) {
        content.removeAllViews();
        progBox = null;
        logView = null;
        logScroll = null;
        content.addView(viewBusy(label));
        content.addView(menuBtn, menuBtnParams());
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
                    fileChooserCallback = cb;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    try {
                        startActivityForResult(Intent.createChooser(i, "Choose file"), REQ_FILE_CHOOSER);
                    } catch (Exception e) {
                        fileChooserCallback = null;
                        return false;
                    }
                    return true;
                }
            });
            webView.setDownloadListener(new DownloadListener() {
                public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                    String name = "apace-download-" + System.currentTimeMillis() + ".bin";
                    if (cd != null && cd.contains("filename=")) {
                        int a = cd.indexOf("filename=") + 9;
                        name = cd.substring(a).replace("\"", "").replace(";", "").trim();
                    }
                    try {
                        android.app.DownloadManager dm = (android.app.DownloadManager)
                                getSystemService(DOWNLOAD_SERVICE);
                        android.app.DownloadManager.Request rq =
                                new android.app.DownloadManager.Request(Uri.parse(url));
                        rq.setDestinationInExternalFilesDir(MainActivity.this,
                                android.os.Environment.DIRECTORY_DOWNLOADS, name);
                        rq.setNotificationVisibility(android.app.DownloadManager.Request
                                .VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        dm.enqueue(rq);
                        Toast.makeText(MainActivity.this,
                                "Downloading: " + name, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
        content.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.onResume();
        webView.loadUrl("http://127.0.0.1:" + prefs.panelPort());
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
            if (ApaceService.state() == ApaceService.State.RUNNING) {
                // Inside the panel: Back = page history, then menu, then minimize
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else if (menuShownAtRoot) {
                    moveTaskToBack(true);
                } else {
                    menuShownAtRoot = true;
                    openMenu();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── ⋮ menu ─────────────────────────────────────────────────────────────

    private void openMenu() {
        ApaceService.State s = ApaceService.state();
        final boolean running = s == ApaceService.State.RUNNING || s == ApaceService.State.STARTING;
        new AlertDialog.Builder(this)
                .setTitle("Apace Launcher")
                .setItems(new String[]{
                        running ? "Stop server" : "Start server",
                        "Console (logs)",
                        "Backup data",
                        "Restore backup (import zip)",
                        "Update Apace",
                        "Settings",
                        "Licenses"
                }, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            if (running) lastStopRequestedAt = System.currentTimeMillis();
                            svc(running ? ApaceService.ACTION_STOP : ApaceService.ACTION_START);
                            if (!running) showBusyNow("Apace loading…");
                        }
                        else if (w == 1) consoleDialog();
                        else if (w == 2) backupNow();
                        else if (w == 3) importBackup();
                        else if (w == 4) updateDialog();
                        else if (w == 5) settingsDialog();
                        else licenseDialog();
                    }
                }).show();
    }

    private void importBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void consoleDialog() {
        final TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(10);
        tv.setTextColor(C_TEXT);
        tv.setText(LogRing.dump());
        ScrollView sc = new ScrollView(this);
        sc.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Console")
                .setView(sc)
                .setPositiveButton("Refresh", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        tv.setText(LogRing.dump());
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void backupNow() {
        new Thread() { public void run() {
            try {
                final File f = backups.createBackup();
                ui.post(new Runnable() { public void run() {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Backup created")
                            .setMessage(f.getName() + " (" + (f.length() >> 20) + " MB)\n\n"
                                    + "Android deletes app data on uninstall — export the "
                                    + "backup outside the app.")
                            .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { startExport(f); }
                            })
                            .setNegativeButton("Later", null).show();
                }});
            } catch (final Exception e) {
                ui.post(new Runnable() { public void run() {
                    Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }});
            }
        }}.start();
    }

    private void startExport(File f) {
        exportBackup = f;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, f.getName());
        startActivityForResult(i, REQ_EXPORT);
    }

    private void updateDialog() {
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        String cur = InstallerEngine.readVersion(paths);
        TextView info = text("Installed: " + (cur != null ? cur : "—")
                        + "\nChannel: " + ("main".equals(prefs.channel()) ? "Dev (CI)" : "Main (stable)"),
                C_TEXT, 13, false);
        box.addView(info);

        TextView ml = label("CI MANIFEST URL (DEV CHANNEL)");
        ml.setPadding(0, dp(14), 0, 0);
        box.addView(ml);
        final EditText manifestUrl = field("https://<user>.github.io/<apace-fork>/main-manifest.json");
        manifestUrl.setText(prefs.mainManifestUrl());
        box.addView(manifestUrl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
            prefs.mainManifestUrl(manifestUrl.getText().toString().trim());
            result.setText("Checking…");
            new Thread() { public void run() {
                final Updater.CheckResult r = new Updater(MainActivity.this, prefs, paths, null).check();
                ui.post(new Runnable() { public void run() {
                    lastCheck = r;
                    result.setText((r.message != null ? r.message + "\n" : "")
                            + "Latest: " + r.latest
                            + (r.available ? "  → update available" : "  → up to date"));
                    boolean can = r.available && r.assetUrl != null;
                    apply.setEnabled(can);
                    apply.setAlpha(can ? 1f : 0.4f);
                }});
            }}.start();
        }});
        holder[0] = new AlertDialog.Builder(this)
                .setTitle("Update Apace")
                .setView(box)
                .setNegativeButton("Close", null)
                .show();
    }

    private void settingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);

        box.addView(label("PANEL PORT (5000)"));
        final EditText portPanel = field("5000");
        portPanel.setText(String.valueOf(prefs.panelPort()));
        portPanel.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(portPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView al = label("APIPORT IN CONFIG.JSON (1808)");
        al.setPadding(0, dp(14), 0, 0);
        box.addView(al);
        final EditText portApi = field("1808");
        portApi.setText(String.valueOf(prefs.apiPort()));
        portApi.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(portApi, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Switch swLan = new Switch(this);
        swLan.setText("Allow LAN panel (0.0.0.0 — panel over Wi-Fi)");
        swLan.setTextColor(C_TEXT);
        swLan.setTextSize(12);
        swLan.setChecked(prefs.allowLan());
        swLan.setPadding(0, dp(14), 0, 0);
        box.addView(swLan);

        final Switch swAwake = new Switch(this);
        swAwake.setText("Wake lock (keeps the CPU awake while running)");
        swAwake.setTextColor(C_TEXT);
        swAwake.setTextSize(12);
        swAwake.setChecked(prefs.keepAwake());
        box.addView(swAwake);

        final Switch swAuto = new Switch(this);
        swAuto.setText("Start on device boot");
        swAuto.setTextColor(C_TEXT);
        swAuto.setTextSize(12);
        swAuto.setChecked(prefs.autoStart());
        box.addView(swAuto);

        TextView hl = label(".NET HEAP LIMIT (RESTART REQUIRED)");
        hl.setPadding(0, dp(14), 0, 0);
        box.addView(hl);
        RadioGroup heap = new RadioGroup(this);
        heap.setOrientation(RadioGroup.HORIZONTAL);
        final int[] vals = {256, 512, 1024, 1536};
        for (int v : vals) {
            RadioButton rb = new RadioButton(this);
            rb.setText(v + " MB");
            rb.setTextColor(C_TEXT);
            rb.setTextSize(11);
            rb.setId(1000 + v);
            if (prefs.heapMb() == v) rb.setChecked(true);
            heap.addView(rb);
        }
        box.addView(heap);

        Button save = mcButton("Save", true);
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            int p1 = Prefs.clampPort(parseInt(portPanel), 5000);
            int p2 = Prefs.clampPort(parseInt(portApi), 1808);
            prefs.panelPort(p1);
            prefs.apiPort(p2);
            prefs.allowLan(swLan.isChecked());
            prefs.keepAwake(swAwake.isChecked());
            prefs.autoStart(swAuto.isChecked());
            int heapId = heap.getCheckedRadioButtonId();
            if (heapId > 0) prefs.heapMb(heapId - 1000);
            try {
                File cfg = new File(paths.current, "launcher/config.json");
                if (cfg.isFile()) {
                    org.json.JSONObject o = new org.json.JSONObject(InstallerEngine.readText(cfg));
                    o.put("ApiPort", p2);
                    InstallerEngine.writeText(cfg, o.toString());
                }
            } catch (Exception ignore) {}
            Toast.makeText(MainActivity.this,
                    "Saved (restart the server for the ports to apply)", Toast.LENGTH_LONG).show();
        }});
        box.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button battery = mcButton("Disable battery optimization", false);
        battery.setAllCaps(false);
        battery.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try {
                startActivity(new Intent(android.provider.Settings
                        .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Open battery settings manually", Toast.LENGTH_SHORT).show();
            }
        }});
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(12), 0, 0);
        box.addView(battery, blp);

        Button wipe = mcButton("Delete environment (clean reinstall)", false);
        wipe.setAllCaps(false);
        wipe.setTextColor(C_RED);
        wipe.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (ApaceService.state() == ApaceService.State.RUNNING
                    || ApaceService.state() == ApaceService.State.STARTING) {
                Toast.makeText(MainActivity.this, "Stop the server first", Toast.LENGTH_SHORT).show();
                return;
            }
            confirm("Delete the environment?",
                    "Deletes Ubuntu, .NET, Java and the Apace files (backups are kept). "
                            + "The game world will be lost — make a backup first (⋮ → Backup data).",
                    new Runnable() { public void run() {
                        confirm("Are you sure?", "This cannot be undone.",
                                new Runnable() { public void run() {
                                    Paths.rmRf(paths.runtime);
                                    Paths.rmRf(paths.apace);
                                    ApaceService.setState(ApaceService.State.NOT_INSTALLED);
                                }});
                    }});
        }});
        box.addView(wipe, blp);

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(box)
                .setNegativeButton("Close", null)
                .show();
    }

    private void licenseDialog() {
        try {
            java.io.InputStream in = getAssets().open("NOTICE.txt");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            new AlertDialog.Builder(this).setTitle("Licenses")
                    .setMessage(new String(bo.toByteArray(), "UTF-8"))
                    .setPositiveButton("OK", null).show();
        } catch (Exception e) {
            Toast.makeText(this, "NOTICE.txt missing", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirm(String title, String msg, final Runnable onYes) {
        new AlertDialog.Builder(this)
                .setTitle(title).setMessage(msg)
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { onYes.run(); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private int parseInt(EditText e) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return 0; }
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
                String err = i.getStringExtra("error");
                if (err != null) ApaceService.setStateError(err);
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
                if (logView != null) {
                    String line = i.getStringExtra("line");
                    if (line != null) {
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
                }
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
        } else if (req == REQ_EXPORT && res == RESULT_OK && data != null && data.getData() != null
                && exportBackup != null) {
            final Uri dest = data.getData();
            final File src = exportBackup;
            new Thread() { public void run() {
                try {
                    backups.exportTo(dest, src);
                    ui.post(new Runnable() { public void run() {
                        Toast.makeText(MainActivity.this, "Exported", Toast.LENGTH_SHORT).show();
                    }});
                } catch (final Exception e) {
                    ui.post(new Runnable() { public void run() {
                        Toast.makeText(MainActivity.this, "Export error: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }});
                }
            }}.start();
        } else if (req == REQ_IMPORT && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri src = data.getData();
            confirm("Restore backup?",
                    "Overwrites the data (world, accounts, config) with the selected zip. Continue?",
                    new Runnable() { public void run() {
                        new Thread() { public void run() {
                            try {
                                backups.importFrom(src);
                                ui.post(new Runnable() { public void run() {
                                    Toast.makeText(MainActivity.this, "Backup restored",
                                            Toast.LENGTH_SHORT).show();
                                }});
                            } catch (final Exception e) {
                                ui.post(new Runnable() { public void run() {
                                    Toast.makeText(MainActivity.this, "Import error: " + e.getMessage(),
                                            Toast.LENGTH_LONG).show();
                                }});
                            }
                        }}.start();
                    }});
        } else {
            super.onActivityResult(req, res, data);
        }
    }
}
