package com.dhangofa.networktoggle;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.dhangofa.networktoggle.automation.AutomationExecutor;
import com.dhangofa.networktoggle.automation.AutomationRequest;
import com.dhangofa.networktoggle.automation.AutomationResult;
import com.dhangofa.networktoggle.model.NetworkMode;
import com.dhangofa.networktoggle.model.TargetSim;
import com.dhangofa.networktoggle.util.AppExecutors;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Home-screen widget targeting the PHYSICAL SIM slot 2 only.
 * It invokes NetToggle's existing privileged automation path; no separate
 * hard-coded telephony commands, root invocation, or insecure broadcast are used.
 */
public final class NetworkSwitchWidget extends AppWidgetProvider {
    private static final String ACTION_4G = "com.dhangofa.networktoggle.widget.PREF_4G";
    private static final String ACTION_5G = "com.dhangofa.networktoggle.widget.PREF_5G";
    private static final AtomicBoolean SWITCHING = new AtomicBoolean(false);

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_network_switch);
            v.setOnClickPendingIntent(R.id.widget_button_4g, clickIntent(context, ACTION_4G, 401));
            v.setOnClickPendingIntent(R.id.widget_button_5g, clickIntent(context, ACTION_5G, 402));
            manager.updateAppWidget(id, v);
        }
    }

    private PendingIntent clickIntent(Context context, String action, int requestCode) {
        Intent i = new Intent(context, NetworkSwitchWidget.class);
        i.setAction(action);
        return PendingIntent.getBroadcast(context, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (!ACTION_4G.equals(action) && !ACTION_5G.equals(action)) return;
        if (!SWITCHING.compareAndSet(false, true)) return;

        // AppWidgetProvider is a BroadcastReceiver; never block the main thread.
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        final boolean use5G = ACTION_5G.equals(action);
        final NetworkMode mode = use5G ? NetworkMode.PREFERRED_5G : NetworkMode.PREFERRED_4G;
        try {
            AppExecutors.executeTelephony(() -> {
                boolean success = false;
                try {
                    AutomationRequest request = new AutomationRequest(
                            mode, TargetSim.SIM_2, false, "Home Widget SIM2");
                    AutomationResult result = AutomationExecutor.execute(app, request);
                    success = result != null && result.success;
                } catch (Throwable ignored) {
                    // Existing diagnostic flow can be inspected in the application.
                }
                final int msg = success
                        ? (use5G ? R.string.widget_applied_5g : R.string.widget_applied_4g)
                        : R.string.widget_apply_failed;
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
                    } finally {
                        SWITCHING.set(false);
                        pending.finish();
                    }
                });
            });
        } catch (RuntimeException e) {
            SWITCHING.set(false);
            pending.finish();
        }
    }
}
