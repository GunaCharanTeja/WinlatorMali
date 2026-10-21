package com.winlator.cmod.contents;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;

import java.util.List;
import java.util.Locale;

/**
 * Keeps content downloads alive while the app is in the background (without it the system freezes the process and the
 * download stalls), shows their progress and stops itself once none is left.
 */
public class ContentDownloadService extends Service implements ContentDownloads.Listener {
    private static final String CHANNEL_ID = "ContentDownloads";
    private static final int NOTIFICATION_ID = 1002;
    private static final long UPDATE_INTERVAL_MS = 1000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private int finishedNotificationId = NOTIFICATION_ID + 1;
    private boolean idle = false;

    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            List<ContentDownloads.Task> active = ContentDownloads.getActiveTasks();
            // Stops one tick after the last task ends, so its result notification (posted to this thread) is shown first.
            if (active.isEmpty()) {
                if (idle) {
                    stopSelf();
                    return;
                }
                idle = true;
                handler.postDelayed(this, UPDATE_INTERVAL_MS);
                return;
            }
            idle = false;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildProgressNotification(active));
            handler.postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "Content Downloads", NotificationManager.IMPORTANCE_LOW));
        }
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "winlator:content_downloads");
        wakeLock.acquire(60 * 60 * 1000L);
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "winlator:content_downloads");
            wifiLock.acquire();
        }
        ContentDownloads.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildProgressNotification(ContentDownloads.getActiveTasks()));
        handler.removeCallbacks(updater);
        handler.postDelayed(updater, UPDATE_INTERVAL_MS);
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskFinished(ContentDownloads.Task task) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ab_gear_0011)
            .setContentTitle(task.state == ContentDownloads.State.DONE ? "Content installed" : "Content not installed")
            .setContentText(task.message)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build();
        getSystemService(NotificationManager.class).notify(finishedNotificationId++, notification);
    }

    private Notification buildProgressNotification(List<ContentDownloads.Task> active) {
        String title = active.size() == 1 ? active.get(0).name : active.size() + " downloads";
        long current = 0, total = 0;
        boolean installing = false, known = true;
        for (ContentDownloads.Task task : active) {
            current += task.currentBytes;
            total += task.totalBytes;
            if (task.totalBytes <= 0) known = false;
            if (task.state == ContentDownloads.State.INSTALLING) installing = true;
        }
        int percent = known && total > 0 ? (int) (current * 100 / total) : -1;
        String text = installing && active.size() == 1 ? "Installing..." :
            percent >= 0 ? String.format(Locale.US, "%s / %s (%d%%)", Downloader.formatFileSize(current), Downloader.formatFileSize(total), percent) :
            "Downloading...";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ab_gear_0011)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, Math.max(percent, 0), percent < 0 || installing)
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build();
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(updater);
        ContentDownloads.removeListener(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
