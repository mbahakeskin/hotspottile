package com.mbaha.hotspottile;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Wi-Fi ADB'yi acma isini arka planda, kisa omurlu olarak yapar. */
public class HealJobService extends JobService {
    static final int JOB_ID = 1001;
    private static long lastRun;

    private volatile Thread worker;

    @Override
    public boolean onStartJob(JobParameters p) {
        long now = System.currentTimeMillis();
        if (now - lastRun < 20_000) return false;   // ard arda olaylari birlestir
        lastRun = now;
        worker = new Thread(() -> {
            try {
                Wireless.heal(this, 120_000);        // ag onay penceresini beklemeyi de kapsar
            } finally {
                jobFinished(p, false);
            }
        }, "adb-heal");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        Thread t = worker;
        if (t != null) t.interrupt();
        return false;
    }
}
