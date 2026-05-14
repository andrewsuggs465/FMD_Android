package de.nulide.findmydevice.ui.access

import androidx.annotation.StringRes
import de.nulide.findmydevice.R
import de.nulide.findmydevice.database.NotificationPassword
import de.nulide.findmydevice.database.PhoneNumber
import de.nulide.findmydevice.database.SmsPassword

open class AccessType<T>(
    @StringRes val hintText: Int,
    @StringRes val emptyText: Int,

    @StringRes val addText: Int,
    @StringRes val addSecondaryText: Int? = null,

    @StringRes val deleteTitle: Int,
)

object ACCESS_PHONE_NUMBER : AccessType<PhoneNumber>(
    hintText = R.string.access_phone_number_hint,
    emptyText = R.string.access_phone_number_empty,
    addText = R.string.WhiteList_Add_Contact,
    addSecondaryText = R.string.allowlist_add_phone_number,
    deleteTitle = R.string.allowlist_delete_title_phone_number,
)

object ACCESS_SMS_PASS : AccessType<SmsPassword>(
    hintText = R.string.access_sms_password_hint,
    emptyText = R.string.access_password_empty,
    addText = R.string.access_sms_password_add,
    deleteTitle = R.string.allowlist_delete_title_password,
)

object ACCESS_NOTIF_PASS : AccessType<NotificationPassword>(
    hintText = R.string.access_notification_password_hint,
    emptyText = R.string.access_password_empty,
    addText = R.string.access_notification_password_add,
    deleteTitle = R.string.allowlist_delete_title_password,
)
