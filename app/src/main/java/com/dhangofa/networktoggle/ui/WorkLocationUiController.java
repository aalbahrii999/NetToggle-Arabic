package com.dhangofa.networktoggle.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.dhangofa.networktoggle.MainActivity;
import com.dhangofa.networktoggle.R;
import com.dhangofa.networktoggle.config.AppPreferences;
import com.dhangofa.networktoggle.location.WorkLocationController;

public final class WorkLocationUiController {
    public static final int REQ_LOCATION = 2401;
    public static final int REQ_BACKGROUND = 2402;
    public static final int REQ_NOTIFICATIONS = 2403;

    private final MainActivity activity;
    private final AppPreferences preferences;
    private Switch toggle;
    private TextView status;
    private boolean suppressToggle;

    public WorkLocationUiController(MainActivity activity, AppPreferences preferences) {
        this.activity = activity;
        this.preferences = preferences;
    }

    public void bind() {
        toggle = activity.findViewById(R.id.switchWorkLocation);
        status = activity.findViewById(R.id.textWorkLocationStatus);
        TextView save = activity.findViewById(R.id.btnSaveWorkLocation);
        if (toggle == null || save == null) return;

        suppressToggle = true;
        toggle.setChecked(preferences.isWorkLocationEnabled());
        suppressToggle = false;
        refreshStatus();

        toggle.setOnCheckedChangeListener(this::onToggle);
        save.setOnClickListener(v -> {
            if (!ensureForegroundLocation()) return;
            capture(false);
        });
    }

    public void handlePermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != REQ_LOCATION && requestCode != REQ_BACKGROUND && requestCode != REQ_NOTIFICATIONS) {
            return;
        }
        if (requestCode == REQ_NOTIFICATIONS) {
            armIfReady();
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            preferences.setWorkLocationEnabled(false);
            setChecked(false);
            Toast.makeText(activity, R.string.work_location_need_permission, Toast.LENGTH_LONG).show();
            openAppSettings();
            return;
        }
        if (requestCode == REQ_LOCATION) {
            if (!ensureBackgroundLocation()) return;
        }
        requestNotificationsThenArm();
    }

    private void onToggle(CompoundButton button, boolean checked) {
        if (suppressToggle) return;
        if (!checked) {
            preferences.setWorkLocationEnabled(false);
            WorkLocationController.unregister(activity);
            refreshStatus();
            return;
        }
        if (!ensureForegroundLocation() || !ensureBackgroundLocation()) {
            setChecked(false);
            return;
        }
        if (!preferences.hasWorkLocation()) {
            capture(true);
            return;
        }
        requestNotificationsThenArm();
    }

    private boolean ensureForegroundLocation() {
        if (hasFine()) return true;
        activity.requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, REQ_LOCATION);
        return false;
    }

    private boolean ensureBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29 || hasBackground()) return true;
        activity.requestPermissions(
                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BACKGROUND);
        return false;
    }

    private void requestNotificationsThenArm() {
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        armIfReady();
    }

    private void capture(boolean armAfter) {
        WorkLocationController.requestCurrentFix(activity, new WorkLocationController.FixCallback() {
            @Override
            public void onFix(Location location) {
                preferences.setWorkLocation(location.getLatitude(), location.getLongitude());
                Toast.makeText(activity, R.string.work_location_saved_toast, Toast.LENGTH_SHORT).show();
                if (armAfter) requestNotificationsThenArm();
                else refreshStatus();
            }

            @Override
            public void onUnavailable() {
                if (armAfter) {
                    preferences.setWorkLocationEnabled(false);
                    setChecked(false);
                }
                Toast.makeText(activity, R.string.work_location_need_fix, Toast.LENGTH_LONG).show();
                refreshStatus();
            }
        });
    }

    private void armIfReady() {
        if (!preferences.hasWorkLocation()) {
            capture(true);
            return;
        }
        try {
            WorkLocationController.register(activity, preferences.getWorkLatitude(), preferences.getWorkLongitude());
        } catch (SecurityException e) {
            preferences.setWorkLocationEnabled(false);
            setChecked(false);
            Toast.makeText(activity, R.string.work_location_need_permission, Toast.LENGTH_LONG).show();
            refreshStatus();
            return;
        }
        preferences.setWorkLocationEnabled(true);
        setChecked(true);
        askIgnoreBatteryOptimizations();
        refreshStatus();
    }

    private void askIgnoreBatteryOptimizations() {
        PowerManager power = activity.getSystemService(PowerManager.class);
        if (power == null || power.isIgnoringBatteryOptimizations(activity.getPackageName())) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (RuntimeException ignored) {
            // Some devices hide this screen. The feature still registers.
        }
    }

    private void refreshStatus() {
        if (status == null) return;
        if (preferences.isWorkLocationEnabled() && preferences.hasWorkLocation()) {
            status.setText(R.string.work_location_status_on);
        } else if (preferences.hasWorkLocation()) {
            status.setText(R.string.work_location_status_saved);
        } else {
            status.setText(R.string.work_location_status_off);
        }
    }

    private void setChecked(boolean checked) {
        if (toggle == null) return;
        suppressToggle = true;
        toggle.setChecked(checked);
        suppressToggle = false;
    }

    private boolean hasFine() {
        return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackground() {
        return activity.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }
}
