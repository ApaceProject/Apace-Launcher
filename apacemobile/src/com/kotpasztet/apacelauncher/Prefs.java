package com.kotpasztet.apacelauncher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Ustawienia aplikacji (SharedPreferences). Wszystkie pola mają sensowne
 * wartości domyślne zgodne z audytem Apace (porty 5000/1808, heap 512 MB).
 */
public final class Prefs {
    private static final String FILE = "apacemobile";

    public final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int panelPort() { return sp.getInt("panel_port", 5000); }
    public void panelPort(int v) { sp.edit().putInt("panel_port", clampPort(v, 5000)).apply(); }

    public int apiPort() { return sp.getInt("api_port", 1808); }
    public void apiPort(int v) { sp.edit().putInt("api_port", clampPort(v, 1808)).apply(); }

    /** true => ASPNETCORE_URLS na 0.0.0.0 (panel dostępny w LAN); false => 127.0.0.1 */
    public boolean allowLan() { return sp.getBoolean("allow_lan", false); }
    public void allowLan(boolean v) { sp.edit().putBoolean("allow_lan", v).apply(); }

    /** Partial wake lock podczas pracy serwera (CPU nie zasypia). */
    public boolean keepAwake() { return sp.getBoolean("keep_awake", true); }
    public void keepAwake(boolean v) { sp.edit().putBoolean("keep_awake", v).apply(); }

    /** Limit sterty .NET w MB (DOTNET_GCHeapHardLimit). 256/512/1024/1536. */
    public int heapMb() { return sp.getInt("heap_mb", 512); }
    public void heapMb(int v) { sp.edit().putInt("heap_mb", v).apply(); }

    /**
     * Kanał aktualizacji Apace: "main" = GitHub Releases (stabilny),
     * "dev" = obraz ghcr.io/kotpasztet/apace:dev (build z brancha dev).
     * Migracja starych wartości ("stable"→"main", stare "main"=dev-build→"dev").
     */
    public String channel() {
        String v = sp.getString("channel", null);
        if (!sp.getBoolean("channel_migrated", false)) {
            if ("stable".equals(v)) v = "main";   // stary kanał stabilny → main (releases)
            else if ("main".equals(v)) v = "dev"; // stare "main" oznaczało dev-build → dev
            else if (v == null) v = "main";       // domyślnie: main (releases)
            sp.edit().putString("channel", v).putBoolean("channel_migrated", true).apply();
        }
        return v == null ? "main" : v;
    }
    public void channel(String v) { sp.edit().putString("channel", v).apply(); }

    /** Start serwera po bootowaniu urządzenia (domyślnie wyłączony). */
    public boolean autoStart() { return sp.getBoolean("auto_start", false); }
    public void autoStart(boolean v) { sp.edit().putBoolean("auto_start", v).apply(); }

    /**
     * Auto-close apki: gdy serwer zatrzyma się automatycznie po 5 min bezczynności
     * (idle auto-stop, „battery saving”), apka sama się zamyka (finish + usunięcie
     * z Recents) i zatrzymuje serwis — proces znika z pamięci. Ręczne zatrzymanie
     * serwera NIGDY nie zamyka apki. Domyślnie włączone (zgłoszenie użytkownika:
     * „auto close apki mobilnej nie działa”).
     */
    public boolean autoCloseApp() { return sp.getBoolean("auto_close_app", true); }
    public void autoCloseApp(boolean v) { sp.edit().putBoolean("auto_close_app", v).apply(); }

    /** URL manifestu aktualizacji samego APK {version,url} (opcjonalny). */
    public String appUpdateUrl() { return sp.getString("app_update_url", ""); }
    public void appUpdateUrl(String v) { sp.edit().putString("app_update_url", v).apply(); }

    public static int clampPort(int v, int def) {
        if (v < 1024 || v > 65535) return def;
        return v;
    }
}
