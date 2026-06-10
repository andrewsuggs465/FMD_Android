package de.nulide.findmydevice.utils;

import android.content.Context;
import android.provider.Settings;

public class SecureSettings {

    private static final String TAG = SecureSettings.class.getSimpleName();

    @SuppressWarnings("deprecation")
    public static void turnGPS(Context context, boolean enable) {
        // LOCATION_MODE is deprecated in API 28 but remains the only public way to toggle
        // location programmatically when the app holds WRITE_SECURE_SETTINGS.
        int value = enable
                ? android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                : android.provider.Settings.Secure.LOCATION_MODE_OFF;
        Settings.Secure.putString(context.getContentResolver(), android.provider.Settings.Secure.LOCATION_MODE, Integer.valueOf(value).toString());
        FmdLogKt.log(context).d(TAG, "Turned GPS on/off using SecureSettings: " + enable);
    }

}
