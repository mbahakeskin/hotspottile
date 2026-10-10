package com.mbaha.hotspottile;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QS tile: kisa dokunus hotspot'u acar/kapatir; UZUN BASIS paneli (menu) acar - sistem, tile'in
 * uzun basisinda PanelActivity'deki QS_TILE_PREFERENCES eylemini baslatir.
 */
public class HotspotTileService extends TileService {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        NetReceiver.register(this);       // Wi-Fi olay kaydi (idempotent): yeniden baslatma sonrasi da buradan toparlanir
        refreshFromDevice();
    }

    @Override
    public void onClick() {
        unlockAndRun(this::toggle);
    }

    /** Hotspot'u ters cevirir; basarisizsa (orn. ADB kapali) hata/yonerge icin paneli acar. */
    private void toggle() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean turnOn = t.getState() != Tile.STATE_ACTIVE;
        t.setState(turnOn ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(getString(turnOn ? R.string.tile_turning_on : R.string.tile_turning_off));
        t.updateTile();

        io.execute(() -> {
            try {
                JSONObject r = Bridge.call(this, "power", turnOn ? "on" : "off");
                if (turnOn && r.optBoolean("ok", false)) Store.get(this).setApOnAt(System.currentTimeMillis());
                Daemon.ensure(this, r);
                applyState(r);
            } catch (Exception e) {
                main.post(this::openPanel);
                refreshFromDevice();
            }
        });
    }

    private void openPanel() {
        Intent i = new Intent(this, PanelActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
    }

    /** Tile'in gorunumunu (acik/kapali, cihaz sayisi) gercek cihaz durumundan gunceller. */
    private void refreshFromDevice() {
        io.execute(() -> {
            try {
                JSONObject s = Bridge.call(this, "state");
                Daemon.ensure(this, s);
                applyState(s);
            } catch (Exception ignored) {
                // sessiz gec: QS paneli her acilista dener, gecici bir hata kullaniciyi rahatsiz etmesin
            }
        });
    }

    private void applyState(JSONObject s) {
        boolean on = s.optInt("apState") == 13;
        JSONArray clients = s.optJSONArray("clients");
        int n = clients != null ? clients.length() : 0;
        main.post(() -> {
            Tile t = getQsTile();
            if (t == null) return;
            t.setIcon(Icon.createWithResource(this, R.drawable.ic_hotspot));
            t.setLabel(getString(R.string.app_name));
            t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            t.setSubtitle(!on ? getString(R.string.tile_off) : n == 0 ? getString(R.string.tile_on)
                    : getResources().getQuantityString(R.plurals.devices_count, n, n));
            t.updateTile();
        });
    }
}
