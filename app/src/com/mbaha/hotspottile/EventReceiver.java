package com.mbaha.hotspottile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Telefondaki Agent "daemon" sureci hotspot/istemci DEGISIMINDE "am broadcast" ile burayi tetikler; uygulama
 * o an calismiyorsa sistem baslatir. Yani uygulamanin kalici bir servisi/bildirimi yoktur; is bitince surec
 * serbest kalir. Disaridan sahte olay gelmesin diye her olay gizli anahtari (token) tasir.
 */
public class EventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String token = i.getStringExtra("token");
        if (token == null || !MessageDigest.isEqual(token.getBytes(), Store.get(c).token().getBytes())) return;
        String event = i.getStringExtra("event"), data = i.getStringExtra("data");
        boolean snapshot = i.getBooleanExtra("snapshot", false);
        if (event == null || data == null) return;
        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                if ("ap".equals(event)) onAp(c, Integer.parseInt(data.trim()));
                else if ("clients".equals(event)) onClients(c, new JSONArray(data), snapshot);
            } catch (Exception ignored) {
                // bozuk olay: sessizce yut
            } finally {
                pr.finish();
            }
        }, "hotspot-event").start();
    }

    private static void onAp(Context c, int state) {
        Store st = Store.get(c);
        int prev = st.lastAp();
        st.setLastAp(state);
        if (state == 13) {
            if (prev != 13) {
                st.setApOnAt(System.currentTimeMillis());              // gercek acilis ani
                int timer = st.timerOffMin();
                if (timer > 0) ActionReceiver.setTimerAlarm(c, timer * 60_000L);
            }
        } else if (state == 11) {
            st.setApOnAt(0);
            st.clearConnectedSince();
            st.setLastClients("");
            ActionReceiver.setTimerAlarm(c, 0);
        }
    }

    private static void onClients(Context c, JSONArray arr, boolean snapshot) {
        Store st = Store.get(c);
        Map<String, String> now = new HashMap<>();          // mac -> host
        for (int k = 0; k < arr.length(); k++) {
            JSONObject o = arr.optJSONObject(k);
            if (o != null) now.put(o.optString("mac"), o.optString("host", null));
        }
        Set<String> before = new HashSet<>();
        for (String m : st.lastClients().split(",")) if (!m.isEmpty()) before.add(m);

        long t = System.currentTimeMillis();
        for (String mac : before) if (!now.containsKey(mac)) st.setConnectedSince(mac, 0);

        for (Map.Entry<String, String> e : now.entrySet()) {
            String mac = e.getKey();
            st.setHost(mac, e.getValue());
            if (before.contains(mac)) continue;               // zaten bagliydi
            // Ilk goruntude (daemon yeni basladi) gercek baglanma anini bilmeyiz: sure yazma, "yeni" uyarisi verme.
            if (!snapshot && st.connectedSince(mac) == 0) st.setConnectedSince(mac, t);
            boolean firstTime = st.markSeen(mac) && !snapshot;
            if (st.trustedOnly() && !st.isTrusted(mac)) {
                // Onaylanmamis cihaz: aninda engelle, kullaniciya "Izin ver" sun.
                try {
                    Bridge.call(c, "block", mac);
                    st.setBlockedAt(mac, true);
                } catch (Exception ignored) { }
                Alerts.post(c, mac, e.getValue(), true);
            } else if (firstTime && st.notifyNew() && !st.isTrusted(mac)) {
                Alerts.post(c, mac, e.getValue(), false);
            }
        }
        st.setLastClients(String.join(",", now.keySet()));
    }
}
