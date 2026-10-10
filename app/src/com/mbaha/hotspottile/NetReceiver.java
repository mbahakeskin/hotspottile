package com.mbaha.hotspottile;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

/**
 * Wi-Fi olaylarini ISLEMSIZ bekler: ConnectivityManager'a bir PendingIntent kaydedilir; sistem, uygulama
 * calismiyorken bile Wi-Fi baglaninca/kopunca bu alicayi uyandirir. Bekleyen thread, polling veya
 * servis yoktur; Wi-Fi olayi olmadikca CPU sifir. Kayit yeniden basladiktan sonra silindigi icin
 * BOOT_COMPLETED / MY_PACKAGE_REPLACED'de tekrarlanir.
 *
 * Olay gelince once ucuz bir yerel soket denemesi yapilir (5555 aciksa is biter). Kapaliysa agir is
 * (TLS ile yeniden acma) kisa omurlu bir JobScheduler isine birakilir.
 */
public class NetReceiver extends BroadcastReceiver {
    static final String ACTION_NET = "com.mbaha.hotspottile.NET";
    private static final int REQ = 7;

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            register(c);
            ActionReceiver.scheduleDaily(c);   // alarmlar yeniden baslatmada silinir
            return;
        }
        if (!ACTION_NET.equals(a)) return;
        if (!Store.get(c).autoAdb()) return;
        if (Wireless.tcpOpen(200)) return;           // zaten aciksa yapacak is yok
        if (!Wireless.wifiConnected(c)) return;      // "kayip" olayi: yapilacak is yok
        schedule(c);
    }

    /** Idempotent: ayni PendingIntent ile tekrar kayit eskisinin yerine gecer. */
    static void register(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            cm.registerNetworkCallback(req, pending(c));
        } catch (Exception ignored) {
            // izin/sistem kisitlamasi: tile veya panel kullanilirken yine de istege bagli onarim calisir
        }
    }

    static void unregister(Context c) {
        try {
            c.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(pending(c));
        } catch (Exception ignored) { }
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, NetReceiver.class).setAction(ACTION_NET);
        // Sistemin olay ayrintilarini (extras) ekleyebilmesi icin MUTABLE olmali.
        return PendingIntent.getBroadcast(c, REQ, i, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        ComponentName cn = new ComponentName(c, HealJobService.class);
        try {
            js.schedule(new JobInfo.Builder(HealJobService.JOB_ID, cn).setExpedited(true).build());
        } catch (Exception e) {
            js.schedule(new JobInfo.Builder(HealJobService.JOB_ID, cn).setOverrideDeadline(0).build());
        }
    }
}
