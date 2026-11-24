package org.apacemobile;

import android.app.Application;
import android.content.Context;

public final class App extends Application {

    private static volatile Context appCtx;

    public static Context appContext() {
        return appCtx;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appCtx = getApplicationContext();
        // stan po starcie procesu: albo zainstalowane, albo nie
        Paths paths = new Paths(this);
        ApaceService.setState(paths.isInstalled()
                ? ApaceService.State.STOPPED
                : ApaceService.State.NOT_INSTALLED);
    }
}
