package com.dhangofa.networktoggle.location;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.dhangofa.networktoggle.R;
import com.dhangofa.networktoggle.automation.AutomationExecutor;
import com.dhangofa.networktoggle.automation.AutomationRequest;
import com.dhangofa.networktoggle.automation.AutomationResult;
import com.dhangofa.networktoggle.config.AppPreferences;
import com.dhangofa.networktoggle.model.NetworkMode;
import com.dhangofa.networktoggle.util.AppExecutors;

public class WorkLocationReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.dhangofa.networktoggle.WORK_PROXIMITY";
    private static final String CHANNEL = "work_location";
    private static final long DEBOUNCE_MS = 90_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION.equals(intent.getAction())) return;
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return;

        final boolean entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false);
        final Context app = context.getApplicationContext();
        final AppPreferences prefs = new AppPreferences(app);
        if (!prefs.isWorkLocationEnabled()) return;

        long now = System.currentTimeMillis();
        if (prefs.hasWorkLocationEvent()
                && prefs.wasLastWorkLocationEntering() == entering
                && now - prefs.getLastWorkLocationEventAt() < DEBOUNCE_MS) {
            return;
        }
        prefs.setLastWorkLocationEvent(entering, now);

        final PendingResult pending = goAsync();
        final NetworkMode mode = entering ? NetworkMode.PREFERRED_4G : NetworkMode.PREFERRED_5G;
        AppExecutors.executeTelephony(() -> {
            boolean success = false;
            try {
                AutomationResult result = AutomationExecutor.execute(app, new AutomationRequest(
                        mode, prefs.getTargetSim(), false, "Work location", true));
                success = result != null && result.success;
            } catch (Throwable ignored) {
                success = false;
            }
            final boolean applied = success;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    notify(app, entering, applied);
                } finally {
                    pending.finish();
                }
            });
        });
    }

    private static void notify(Context context, boolean entering, boolean success) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, context.getString(R.string.work_location_channel), NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }
        int message = success
                ? (entering ? R.string.work_location_notify_enter : R.string.work_location_notify_exit)
                : R.string.work_location_notify_fail;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        manager.notify(entering ? 7102 : 7103, builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(context.getString(R.string.work_location_notify_title))
                .setContentText(context.getString(message))
                .setAutoCancel(true)
                .build());
    }
}
