package com.kotpasztet.apacelauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Opcjonalny autostart serwera po bootowaniu (Ustawienia → włącznik).
 * Android 12+: BOOT_COMPLETED jest wyjątkiem od blokady startu FGS z tła.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Prefs prefs = new Prefs(context);
        if (!prefs.autoStart()) return;
        Paths paths = new Paths(context);
        if (!paths.isInstalled()) return;
        Intent svc = new Intent(context, ApaceService.class);
        svc.setAction(ApaceService.ACTION_START);
        context.startForegroundService(svc);
    }
}
