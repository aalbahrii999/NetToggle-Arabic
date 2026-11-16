package com.dhangofa.networktoggle.location;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Looper;

import com.dhangofa.networktoggle.config.AppPreferences;

public final class WorkLocationController {
    public static final float RADIUS_METERS = 300f;
    private static final int REQUEST_CODE = 7101;

    public interface FixCallback {
        void onFix(Location location);
        void onUnavailable();
    }

    private WorkLocationController() {}

    public static void reregisterIfEnabled(Context context) {
        AppPreferences prefs = new AppPreferences(context);
        if (!prefs.isWorkLocationEnabled() || !prefs.hasWorkLocation()) return;
        try {
            register(context, prefs.getWorkLatitude(), prefs.getWorkLongitude());
        } catch (SecurityException ignored) {
            prefs.setWorkLocationEnabled(false);
        }
    }

    @SuppressLint("MissingPermission")
    public static void register(Context context, double latitude, double longitude) {
        LocationManager manager = manager(context);
        if (manager == null) return;
        PendingIntent pending = pendingIntent(context);
        manager.removeProximityAlert(pending);
        manager.addProximityAlert(latitude, longitude, RADIUS_METERS, -1L, pending);
    }

    public static void unregister(Context context) {
        LocationManager manager = manager(context);
        if (manager == null) return;
        try {
            manager.removeProximityAlert(pendingIntent(context));
        } catch (SecurityException ignored) {
            // Removing an alert can still require location permission on some releases.
        }
    }

    @SuppressLint("MissingPermission")
    public static void requestCurrentFix(Context context, FixCallback callback) {
        LocationManager manager = manager(context);
        if (manager == null) {
            callback.onUnavailable();
            return;
        }
        Location cached = newest(
                safeLast(manager, LocationManager.GPS_PROVIDER),
                safeLast(manager, LocationManager.NETWORK_PROVIDER));
        if (cached != null && System.currentTimeMillis() - cached.getTime() < 120_000L) {
            callback.onFix(cached);
            return;
        }
        String provider = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER
                : (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER : null);
        if (provider == null) {
            if (cached != null) callback.onFix(cached);
            else callback.onUnavailable();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            manager.getCurrentLocation(provider, null, context.getMainExecutor(), location -> {
                if (location != null) callback.onFix(location);
                else if (cached != null) callback.onFix(cached);
                else callback.onUnavailable();
            });
            return;
        }
        manager.requestSingleUpdate(provider, new android.location.LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                callback.onFix(location);
            }

            @Override public void onStatusChanged(String p, int status, android.os.Bundle extras) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) { callback.onUnavailable(); }
        }, Looper.getMainLooper());
    }

    private static Location safeLast(LocationManager manager, String provider) {
        try {
            return manager.getLastKnownLocation(provider);
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private static Location newest(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.getTime() >= b.getTime() ? a : b;
    }

    private static LocationManager manager(Context context) {
        return (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, WorkLocationReceiver.class);
        intent.setAction(WorkLocationReceiver.ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
