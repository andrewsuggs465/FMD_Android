package de.nulide.findmydevice.data;

import android.content.Context;
import android.telephony.PhoneNumberUtils;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import de.nulide.findmydevice.utils.PhoneUtilsKt;

@Keep
public class Contact {

    private String name;
    private String number;

    private Contact(String name, String number) {
        this.name = name;
        this.number = number;
    }

    @Nullable
    public static Contact from(Context context, String name, String number) {
        String numberFormatted = PhoneUtilsKt.normalizePhoneNumber(context, number);
        if (numberFormatted == null) {
            return null;
        }
        return new Contact(name, numberFormatted);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public boolean equals(@Nullable Object other) {
        if (!(other instanceof Contact)) {
            return false;
        }
        return PhoneNumberUtils.compare(number, ((Contact) other).number);
    }
}
