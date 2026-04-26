package de.nulide.findmydevice.utils

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager

fun normalizePhoneNumber(context: Context, number: String): String? {
    val tm: TelephonyManager = context.getSystemService(TelephonyManager::class.java)
    val iso = tm.networkCountryIso

    val numberFormatted: String?
    if (iso.isEmpty()) {
        // iso is empty when the phone is in flight mode
        // fall back to deprecated function
        numberFormatted = PhoneNumberUtils.formatNumber(number)
    } else {
        // iso must be non-empty, else the number is treated as invalid
        numberFormatted = PhoneNumberUtils.formatNumber(number, iso)
    }

    return numberFormatted
}
