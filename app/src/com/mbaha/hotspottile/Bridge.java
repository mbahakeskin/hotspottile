package com.mbaha.hotspottile;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.security.KeyPair;

/**
 * Ayricalikli islemler icin kopru. Her cagri kendi kisa omurlu adb baglantisini acar, komutu
 * {@link Agent}'i shell yetkisiyle calistirarak yurutur, sonucu alir ve baglantiyi hemen kapatir.
 *
 * Kalici (acik birakilan) bir oturum KASITLI OLARAK kullanilmiyor: boyle bir oturumun kapanis
 * aninda, bu cihazda/ROM'da adbd'nin USB tasini zaman zaman istikrarsizlastirdigi gozlemlendi.
 * Her istek icin ayri baglanti birkac yuz milisaniye ek yuk getirir ama cok daha saglam calisir.
 *
 * Guvenlik: USB hata ayiklamayi yalnizca KAPALIYSA acariz; ASLA OTOMATIK KAPATMAYIZ. Daha once
 * "biz actik" bayragini kalici (SharedPreferences) tutup panel kapaninca geri kapatan bir tasarim
 * vardi; "am force-stop" gibi sert bir sureç sonlandirmasinda bu bayrak temizlenmeden takili
 * kalabiliyor ve sonraki her oturumda kullanicinin KENDI Gelistirici Secenekleri anahtarini
 * sessizce kapatiyordu. Bu, kullaniciyi tekrar tekrar kabloyu sokup takmak zorunda birakti. Fail-
 * open daha guvenli: en kotu ihtimalle hata ayiklama acik kalir, kullanici onu kendi Ayarlar'indan
 * kapatabilir; ama uygulama kullanicinin ayarini asla kendiliginden geri almaz.
 */
final class Bridge {
    static final int PORT = 5555;

    private static KeyPair keys;

    private Bridge() { }

    static void ensureAdbEnabled(Context c) {
        boolean on = Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        if (!on) {
            Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Ajani tek seferlik calistirir ve JSON sonucunu dondurur. Arka plan is parcacigindan cagirin. */
    static synchronized JSONObject call(Context c, String... args) throws Exception {
        ensureAdbEnabled(c);
        if (keys == null) keys = AdbClient.loadOrCreateKeys(c.getFilesDir());

        StringBuilder cmd = new StringBuilder();
        cmd.append("CLASSPATH=").append(c.getApplicationInfo().sourceDir)
                .append(" app_process /system/bin com.mbaha.hotspottile.Agent");
        for (String a : args) cmd.append(' ').append(quote(a));

        try {
            return run(c, cmd.toString(), 3_000);
        } catch (ConnectException closed) {
            // 5555 kapali (orn. yeniden baslatma sonrasi): Wi-Fi'deysek kendimiz yeniden aciyoruz.
            Wireless.Result r = Wireless.heal(c, 25_000);
            if (r == Wireless.Result.OPENED || r == Wireless.Result.ALREADY_OPEN) {
                return run(c, cmd.toString(), 6_000);
            }
            int msg = r == Wireless.Result.NO_WIFI ? R.string.err_adb_no_wifi
                    : r == Wireless.Result.NOT_ALLOWED ? R.string.err_adb_not_allowed
                    : R.string.err_adb_failed;
            throw new IOException(c.getString(msg), closed);
        }
    }

    /**
     * Telefonda Agent "daemon" surecini arka planda baslatir (tek kopya: dosya kilidi). Sureç "nohup setsid" ile
     * adb oturumundan ayrilir; oturum hemen kapanir, sureç sistem olaylarini beklemeye devam eder.
     */
    static synchronized void startDaemon(Context c) throws Exception {
        ensureAdbEnabled(c);
        if (keys == null) keys = AdbClient.loadOrCreateKeys(c.getFilesDir());
        String cmd = "CLASSPATH=" + c.getApplicationInfo().sourceDir
                + " nohup setsid app_process /system/bin com.mbaha.hotspottile.Agent daemon "
                + quote(Store.get(c).token()) + " >/dev/null 2>&1 &";
        AdbClient cl = new AdbClient(keys);
        try {
            cl.connect("127.0.0.1", PORT, 60_000);
            cl.exec(cmd);
        } finally {
            cl.close();
        }
    }

    private static JSONObject run(Context c, String cmd, long waitMs) throws Exception {
        long end = System.currentTimeMillis() + waitMs;
        ConnectException last = null;
        while (System.currentTimeMillis() < end) {
            AdbClient cl = new AdbClient(keys);
            try {
                cl.connect("127.0.0.1", PORT, 60_000);
                String text = cl.exec(cmd);
                String line = lastJsonLine(text);
                if (line == null) throw new IOException(c.getString(R.string.err_agent_no_output, text.trim()));
                return new JSONObject(line);
            } catch (ConnectException e) {
                last = e;
                Thread.sleep(400); // adbd yeni aciliyor olabilir
            } finally {
                cl.close();
            }
        }
        throw last != null ? last : new ConnectException("timeout");
    }

    private static String lastJsonLine(String text) {
        String line = null;
        for (String l : text.split("\n")) {
            if (l.startsWith("{")) line = l;
        }
        return line;
    }
}
