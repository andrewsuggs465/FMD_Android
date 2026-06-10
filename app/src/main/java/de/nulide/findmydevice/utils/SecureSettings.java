package de.nulide.findmydevice.utils;

import android.content.Context;
import android.location.LocationManager;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

public class SecureSettings {

    private static final String TAG = SecureSettings.class.getSimpleName();

    public static void turnGPS(Context context, boolean enable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Settings.Secure.LOCATION_MODE was deprecated in API 28; use LocationManager instead.
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            locationManager.setLocationEnabledForUser(enable, Process.myUserHandle());
        } else {
            int value = enable
                    ? android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                    : android.provider.Settings.Secure.LOCATION_MODE_OFF;
            Settings.Secure.putString(context.getContentResolver(), android.provider.Settings.Secure.LOCATION_MODE, Integer.valueOf(value).toString());
        }
        FmdLogKt.log(context).d(TAG, "Turned GPS on/off using SecureSettings: " + enable);
    }

}
