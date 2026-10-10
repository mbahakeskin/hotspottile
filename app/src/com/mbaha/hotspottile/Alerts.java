package com.mbaha.hotspottile;

import android.app.Notification;
import android.content.Context;

/** Yeni / onaysiz cihaz bildirimleri (Engelle, Guven, Izin ver eylemleriyle). */
final class Alerts {
    private Alerts() { }

    static void post(Context c, String mac, String host, boolean wantsToConnect) {
        Store st = Store.get(c);
        Notify.channels(c);
        String nick = st.nickname(mac);
        String label = nick != null ? nick
                : host != null && !host.isEmpty() && !"null".equals(host) ? host : mac;
        int id = Notify.ID_ALERT_BASE + (mac.hashCode() & 0xffff);
        Notification.Builder b = Notify.builder(c, Notify.CH_ALERT)
                .setContentTitle(c.getString(wantsToConnect ? R.string.alert_wants_title : R.string.alert_new_title))
                .setContentText(label + (label.equals(mac) ? "" : " \u00b7 " + mac))
                .setContentIntent(Notify.openPanel(c))
                .setAutoCancel(true);
        if (!wantsToConnect) {
            b.addAction(new Notification.Action.Builder(null, c.getString(R.string.block),
                    Notify.action(c, id, ActionReceiver.BLOCK, mac)).build());
        }
        b.addAction(new Notification.Action.Builder(null,
                c.getString(wantsToConnect ? R.string.alert_allow : R.string.alert_trust),
                Notify.action(c, id + 1, wantsToConnect ? ActionReceiver.ALLOW : ActionReceiver.TRUST, mac)).build());
        Notify.post(c, id, b.build());
    }
}
