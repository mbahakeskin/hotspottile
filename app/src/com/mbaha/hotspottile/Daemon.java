package com.mbaha.hotspottile;

import android.content.Context;

import org.json.JSONObject;

/** Telefondaki olay surecini (Agent daemon) gerektiginde, hafifce ve seyrek baslatir. */
final class Daemon {
    private static long lastTry;

    private Daemon() { }

    /**
     * Durum yanitina (state) bakar: olay gerektiren bir ozellik acik ve surec calismiyorsa baslatir.
     * Arka plan is parcacigindan cagirin. Basarisiz denemeyi dakikada en fazla bir kez tekrarlar.
     */
    static void ensure(Context c, JSONObject state) {
        if (!Store.get(c).needsDaemon() || state.optBoolean("daemon")) return;
        long now = System.currentTimeMillis();
        synchronized (Daemon.class) {
            if (now - lastTry < 60_000) return;
            lastTry = now;
        }
        try {
            Bridge.startDaemon(c);
        } catch (Exception ignored) {
            // 5555 kapaliysa/yetkisizse sessiz gec: bir sonraki acilista yeniden denenir
        }
    }
}
