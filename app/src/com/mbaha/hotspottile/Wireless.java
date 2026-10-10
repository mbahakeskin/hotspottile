package com.mbaha.hotspottile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyPair;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Telefon yeniden basladiktan sonra "adb tcpip 5555" komutunu bilgisayar olmadan kendi kendine verir.
 *
 * Yontem: Wi-Fi'ye baglanilinca Kablosuz hata ayiklamayi (adb_wifi_enabled) acariz; adbd TLS portunu
 * acar. Zaten yetkili olan anahtarimizla (eslestirme gerekmez, bkz. AdbClient.connectTls) o porta
 * baglanip "tcpip:5555" servisini calistiririz; boylece klasik 5555 portu geri gelir. Is bitince
 * Kablosuz hata ayiklamayi eski haline dondururuz (acik birakmayiz).
 *
 * Sistem kisitlari: Wi-Fi baglantisi sart; sistem tanimadigi her yeni agda bir kez "Bu agda kablosuz
 * hata ayiklamaya izin verilsin mi?" diye sorar ("Her zaman izin ver" secilirse sonraki sefer sormaz).
 */
final class Wireless {
    private static final String TAG = "HotspotWireless";

    private Wireless() { }

    enum Result { ALREADY_OPEN, OPENED, NO_WIFI, NOT_ALLOWED, FAILED }

    /** 127.0.0.1:5555 acik mi? Ucuz bir yerel baglanti denemesi; CPU/pil maliyeti yok denecek kadar az. */
    static boolean tcpOpen(int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", Bridge.PORT), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean wifiConnected(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return true;
        }
        return false;
    }

    private static int wifiSetting(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0);
    }

    /**
     * 5555 kapaliysa acmaya calisir. maxWaitMs, kullanicinin ag onay penceresini yanitlamasini da kapsar.
     * Arka plan is parcacigindan cagirin.
     */
    static synchronized Result heal(Context c, long maxWaitMs) {
        if (tcpOpen(300)) return Result.ALREADY_OPEN;
        if (!wifiConnected(c)) return Result.NO_WIFI;

        int original = wifiSetting(c);
        boolean prompted = false;
        try {
            Bridge.ensureAdbEnabled(c);
            Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);

            long end = System.currentTimeMillis() + maxWaitMs;
            long start = System.currentTimeMillis();
            int port = 0;
            boolean triedNsd = false;
            while (System.currentTimeMillis() < end) {
                port = propPort();
                if (port > 0) break;
                // Ozellik okunamiyorsa (SELinux) 4 sn sonra mDNS ile ara; yalniz ayar acikken.
                if (!triedNsd && System.currentTimeMillis() - start > 4000 && wifiSetting(c) == 1) {
                    triedNsd = true;
                    port = discoverPort(c, 4000);
                    if (port > 0) break;
                }
                // Sistem tanimadigi bir agda ayari hemen 0'a ceker ve onay penceresi acar.
                if (!prompted && System.currentTimeMillis() - start > 1500 && wifiSetting(c) == 0) {
                    prompted = true;
                    Log.i(TAG, "ag onayi bekleniyor");
                    Notify.channels(c);
                    Notify.post(c, Notify.ID_SETUP, Notify.builder(c, Notify.CH_SETUP)
                            .setContentTitle(c.getString(R.string.setup_title))
                            .setContentText(c.getString(R.string.setup_allow_network))
                            .setStyle(new android.app.Notification.BigTextStyle()
                                    .bigText(c.getString(R.string.setup_allow_network)))
                            .setAutoCancel(true).build());
                }
                Thread.sleep(500);
            }
            if (port <= 0) return prompted ? Result.NOT_ALLOWED : Result.FAILED;
            Notify.cancel(c, Notify.ID_SETUP);

            KeyPair keys = AdbClient.loadOrCreateKeys(c.getFilesDir());
            String out = null;
            Exception last = null;
            for (String host : new String[]{"127.0.0.1", "::1"}) {
                AdbClient cl = new AdbClient(keys);
                try {
                    cl.connectTls(host, port);
                    out = cl.service("tcpip:" + Bridge.PORT);
                    break;
                } catch (Exception e) {
                    // adbd "tcpip" sonrasi yeniden basladigi icin baglanti kopabilir; asil olcut 5555'in acilmasi
                    last = e;
                    if (tcpWait(3000)) break;
                } finally {
                    cl.close();
                }
            }
            if (!tcpWait(8000)) {
                Log.w(TAG, "5555 acilmadi", last);
                return Result.FAILED;
            }
            Log.i(TAG, "5555 acildi: " + out);
            return Result.OPENED;
        } catch (Exception e) {
            Log.w(TAG, "heal basarisiz", e);
            return Result.FAILED;
        } finally {
            // Kablosuz hata ayiklamayi acik BIRAKMA: eski haline dondur.
            if (original == 0 && wifiSetting(c) != 0) {
                Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 0);
            }
        }
    }

    private static boolean tcpWait(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (tcpOpen(300)) return true;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    // ---- TLS portunu bulma -----------------------------------------------------------------------

    /** adbd, TLS portunu "service.adb.tls.port" sistem ozelligine yazar. */
    private static int propPort() {
        try {
            Process pr = new ProcessBuilder("getprop", "service.adb.tls.port").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
                String line = r.readLine();
                return line == null || line.trim().isEmpty() ? 0 : Integer.parseInt(line.trim());
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** Ozellik okunamazsa yedek: mDNS "_adb-tls-connect._tcp" yayinindan port bulur. */
    @SuppressWarnings("deprecation")
    static int discoverPort(Context c, long timeoutMs) {
        NsdManager nsd = c.getSystemService(NsdManager.class);
        AtomicInteger found = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { latch.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo s) { }
            @Override public void onServiceFound(NsdServiceInfo s) {
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int e) { }
                    @Override public void onServiceResolved(NsdServiceInfo i) {
                        found.set(i.getPort());
                        latch.countDown();
                    }
                });
            }
        };
        try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, dl);
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            try {
                nsd.stopServiceDiscovery(dl);
            } catch (Exception ignored) { }
        }
        return found.get();
    }
}
