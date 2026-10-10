package com.mbaha.hotspottile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Kalici ayarlar ve cihaz bilgileri (SharedPreferences, JSON). Tum erisimler kisa ve senkron. */
final class Store {
    private static Store inst;

    private final SharedPreferences sp;

    private Store(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("hotspot", Context.MODE_PRIVATE);
    }

    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c);
        return inst;
    }

    // ---- ayarlar ---------------------------------------------------------------------------------

    /** Wi-Fi'ye baglaninca 5555'i otomatik acma. */
    boolean autoAdb() { return sp.getBoolean("autoAdb", true); }
    void setAutoAdb(boolean v) { sp.edit().putBoolean("autoAdb", v).apply(); }

    /** Bagli cihaz kalmayinca N dakika sonra kapat (0 = kapali). */
    int idleOffMin() { return sp.getInt("idleOffMin", 0); }
    void setIdleOffMin(int v) { sp.edit().putInt("idleOffMin", v).apply(); }

    /** Acildiktan N dakika sonra kapat (0 = kapali). */
    int timerOffMin() { return sp.getInt("timerOffMin", 0); }
    void setTimerOffMin(int v) { sp.edit().putInt("timerOffMin", v).apply(); }

    /** Her gun belirli saatte kapat: gunun dakikasi (-1 = kapali). */
    int dailyOffMinute() { return sp.getInt("dailyOffMinute", -1); }
    void setDailyOffMinute(int v) { sp.edit().putInt("dailyOffMinute", v).apply(); }

    boolean notifyNew() { return sp.getBoolean("notifyNew", true); }
    void setNotifyNew(boolean v) { sp.edit().putBoolean("notifyNew", v).apply(); }

    /** Yalnizca guvenilen cihazlar baglanabilsin (istemci kontrolu). */
    boolean trustedOnly() { return sp.getBoolean("trustedOnly", false); }
    void setTrustedOnly(boolean v) { sp.edit().putBoolean("trustedOnly", v).apply(); }

    /** Olay surecine (daemon) ihtiyac duyan ozellik acik mi? (Bos durumda hicbir sey calismaz.) */
    boolean needsDaemon() {
        return timerOffMin() > 0 || notifyNew() || trustedOnly();
    }

    /** EventReceiver'in dogruladigi gizli anahtar: yalnizca bizim baslattigimiz daemon bildirim/engelleme tetikleyebilir. */
    synchronized String token() {
        String t = sp.getString("token", null);
        if (t == null) {
            byte[] b = new byte[24];
            new java.security.SecureRandom().nextBytes(b);
            t = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            sp.edit().putString("token", t).apply();
        }
        return t;
    }

    /** Son bilinen bagli istemciler (MAC listesi, virgulle) ve hotspot durumu: EventReceiver fark hesaplar. */
    String lastClients() { return sp.getString("lastClients", ""); }
    void setLastClients(String csv) { sp.edit().putString("lastClients", csv).apply(); }
    int lastAp() { return sp.getInt("lastAp", -1); }
    void setLastAp(int v) { sp.edit().putInt("lastAp", v).apply(); }

    /** Cihaz yetenekleri (kanal listeleri, ulke) onbellegi (JSON). */
    String capsJson() { return sp.getString("caps", null); }
    long capsAt() { return sp.getLong("capsAt", 0); }
    void setCaps(String json) { sp.edit().putString("caps", json).putLong("capsAt", System.currentTimeMillis()).apply(); }

    // ---- cihaz adlari / guvenilir / gorulmus ------------------------------------------------------

    private JSONObject obj(String key) {
        try {
            return new JSONObject(sp.getString(key, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void put(String key, JSONObject o) { sp.edit().putString(key, o.toString()).apply(); }

    String nickname(String mac) { return obj("names").optString(mac, null); }

    void setNickname(String mac, String name) {
        JSONObject o = obj("names");
        try {
            if (name == null || name.isEmpty()) o.remove(mac); else o.put(mac, name);
        } catch (Exception ignored) { }
        put("names", o);
    }

    boolean isTrusted(String mac) { return obj("trusted").has(mac); }

    void setTrusted(String mac, boolean t) {
        JSONObject o = obj("trusted");
        try {
            if (t) { if (!o.has(mac)) o.put(mac, System.currentTimeMillis()); } else o.remove(mac);
        } catch (Exception ignored) { }
        put("trusted", o);
    }

    /** Ne zamandir guvenilir (0 = bilinmiyor; eski kayitlar). */
    long trustedSince(String mac) { return obj("trusted").optLong(mac, 0); }

    /** Uygulamanin engelledigi cihazlar icin engelleme zamani (baska yerden engellenenlerde 0). */
    void setBlockedAt(String mac, boolean blocked) {
        JSONObject o = obj("blockedAt");
        try {
            if (blocked) { if (!o.has(mac)) o.put(mac, System.currentTimeMillis()); } else o.remove(mac);
        } catch (Exception ignored) { }
        put("blockedAt", o);
    }

    long blockedSince(String mac) { return obj("blockedAt").optLong(mac, 0); }

    /** Cihazin bilinen son ana bilgisayar adi (bagli degilken de isim gostermek icin). */
    String host(String mac) { return obj("hosts").optString(mac, null); }

    void setHost(String mac, String host) {
        if (host == null || host.isEmpty() || "null".equals(host)) return;
        JSONObject o = obj("hosts");
        if (host.equals(o.optString(mac, null))) return;
        try {
            o.put(mac, host);
        } catch (Exception ignored) { }
        put("hosts", o);
    }

    List<String> trustedMacs() {
        List<String> l = new ArrayList<>();
        JSONArray names = obj("trusted").names();
        if (names != null) for (int i = 0; i < names.length(); i++) l.add(names.optString(i));
        return l;
    }

    /** Daha once hic gorulmus mu? Ilk gorulmede true doner ve kaydeder. */
    boolean markSeen(String mac) {
        JSONObject o = obj("seen");
        if (o.has(mac)) return false;
        try {
            o.put(mac, System.currentTimeMillis());
        } catch (Exception ignored) { }
        put("seen", o);
        return true;
    }

    // ---- baglanti sureleri -----------------------------------------------------------------------

    long connectedSince(String mac) { return obj("since").optLong(mac, 0); }

    void setConnectedSince(String mac, long t) {
        JSONObject o = obj("since");
        try {
            if (t == 0) o.remove(mac); else o.put(mac, t);
        } catch (Exception ignored) { }
        put("since", o);
    }

    void clearConnectedSince() { put("since", new JSONObject()); }

    boolean flag(String k) { return sp.getBoolean("flag_" + k, false); }
    void setFlag(String k) { sp.edit().putBoolean("flag_" + k, true).apply(); }

    /** Hotspot'un en son acildigi an (zamanlayici icin). */
    long apOnAt() { return sp.getLong("apOnAt", 0); }
    void setApOnAt(long t) { sp.edit().putLong("apOnAt", t).apply(); }
}
