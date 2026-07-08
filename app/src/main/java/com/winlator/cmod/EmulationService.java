package com.winlator.cmod;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.winlator.cmod.xenvironment.XEnvironment;

public class EmulationService extends Service {
    public static final String ACTION_STOP_EMULATION = "com.winlator.cmod.ACTION_STOP_EMULATION";
    private static final String CHANNEL_ID = "EmulationService";
    private static final int NOTIFICATION_ID = 1001;
    private final IBinder binder = new EmulationBinder();
    private XEnvironment environment;

    public class EmulationBinder extends Binder {
        public EmulationService getService() {
            return EmulationService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_EMULATION.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Intent notificationIntent = new Intent(this, XServerDisplayActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, EmulationService.class);
        stopIntent.setAction(ACTION_STOP_EMULATION);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_ab_gear_0011)
                .setContentTitle("Winlator Emulation")
                .setContentText("Emulation is running in the background")
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.icon_remove, "Stop Emulation", stopPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public XEnvironment getEnvironment() {
        return environment;
    }

    public void setEnvironment(XEnvironment environment) {
        this.environment = environment;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Emulation Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (environment != null) {
            environment.stopEnvironmentComponents();
        }
        com.winlator.cmod.core.ProcessHelper.killAllWineProcesses();
    }
}
