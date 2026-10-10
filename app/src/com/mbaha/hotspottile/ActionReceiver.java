package com.mbaha.hotspottile;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/** Bildirim dugmeleri ve alarmlar: kisa omurlu, arka planda Bridge cagirir. */
public class ActionReceiver extends BroadcastReceiver {
    static final String BLOCK = "com.mbaha.hotspottile.BLOCK";
    static final String TRUST = "com.mbaha.hotspottile.TRUST";
    static final String ALLOW = "com.mbaha.hotspottile.ALLOW";
    static final String OFF = "com.mbaha.hotspottile.OFF";
    static final String ALARM_OFF = "com.mbaha.hotspottile.ALARM_OFF";
    static final String ALARM_DAILY = "com.mbaha.hotspottile.ALARM_DAILY";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        String mac = i.getStringExtra("mac");
        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                Store st = Store.get(c);
                if (BLOCK.equals(a)) {
                    Bridge.call(c, "block", mac);
                    st.setBlockedAt(mac, true);
                } else if (TRUST.equals(a)) {
                    st.setTrusted(mac, true);
                } else if (ALLOW.equals(a)) {
                    st.setTrusted(mac, true);
                    Bridge.call(c, "unblock", mac);
                    st.setBlockedAt(mac, false);
                } else if (OFF.equals(a) || ALARM_OFF.equals(a)) {
                    Bridge.call(c, "power", "off");
                } else if (ALARM_DAILY.equals(a)) {
                    Bridge.call(c, "power", "off");
                    scheduleDaily(c);
                }
                if (mac != null) Notify.cancel(c, Notify.ID_ALERT_BASE + (mac.hashCode() & 0xffff));
            } catch (Exception ignored) {
                // bildirimden tetiklenen sessiz islem: hata ekrani yok
            } finally {
                pr.finish();
            }
        }).start();
    }

    // ---- alarmlar --------------------------------------------------------------------------------

    private static PendingIntent pi(Context c, int req, String action) {
        Intent i = new Intent(c, ActionReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Hotspot acikken "N dk sonra kapat" alarmi (bos birakilirsa iptal). */
    static void setTimerAlarm(Context c, long afterMs) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (afterMs <= 0) {
            am.cancel(pi(c, 11, ALARM_OFF));
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + afterMs, pi(c, 11, ALARM_OFF));
        }
    }

    /** Her gun belirli saatte kapat: bir sonraki tetikleme zamanini planlar (-1 = iptal). */
    static void scheduleDaily(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        int minute = Store.get(c).dailyOffMinute();
        if (minute < 0) {
            am.cancel(pi(c, 12, ALARM_DAILY));
            return;
        }
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, minute / 60);
        cal.set(Calendar.MINUTE, minute % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_MONTH, 1);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi(c, 12, ALARM_DAILY));
    }
}
