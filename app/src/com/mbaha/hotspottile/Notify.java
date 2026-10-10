package com.mbaha.hotspottile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

/** Bildirim kanallari ve kucuk gonderim yardimcilari. */
final class Notify {
    static final String CH_ALERT = "alert";     // yeni cihaz uyarilari
    static final String CH_SETUP = "setup";     // Wi-Fi ADB kurulum ipuclari

    static final int ID_SETUP = 2, ID_ALERT_BASE = 100;

    private Notify() { }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT,
                c.getString(R.string.ch_alert), NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_SETUP,
                c.getString(R.string.ch_setup), NotificationManager.IMPORTANCE_DEFAULT));
    }

    static Notification.Builder builder(Context c, String channel) {
        return new Notification.Builder(c, channel)
                .setSmallIcon(Icon.createWithResource(c, R.drawable.ic_hotspot));
    }

    static PendingIntent openPanel(Context c) {
        Intent i = new Intent(c, PanelActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Bildirim eylemleri icin ActionReceiver'a giden PendingIntent. */
    static PendingIntent action(Context c, int requestCode, String action, String mac) {
        Intent i = new Intent(c, ActionReceiver.class).setAction(action).putExtra("mac", mac);
        return PendingIntent.getBroadcast(c, requestCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void post(Context c, int id, Notification n) {
        try {
            c.getSystemService(NotificationManager.class).notify(id, n);
        } catch (Exception ignored) {
            // bildirim izni yoksa sessizce gec
        }
    }

    static void cancel(Context c, int id) {
        c.getSystemService(NotificationManager.class).cancel(id);
    }
}
