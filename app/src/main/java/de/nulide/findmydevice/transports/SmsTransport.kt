package de.nulide.findmydevice.transports

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.nulide.findmydevice.R
import de.nulide.findmydevice.commands.AccessResponse
import de.nulide.findmydevice.commands.ParserResult
import de.nulide.findmydevice.commands.hasPermission
import de.nulide.findmydevice.data.AccessRepository
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.data.TEMP_USAGE_VALIDITY_MILLIS
import de.nulide.findmydevice.data.TemporaryAllowlistRepository
import de.nulide.findmydevice.permissions.SmsPermission
import de.nulide.findmydevice.services.TempContactExpiredService
import de.nulide.findmydevice.ui.access.AccessControlActivity
import de.nulide.findmydevice.utils.Notifications
import de.nulide.findmydevice.utils.log


class SmsTransport(
    private val context: Context,
    private val phoneNumber: String,
    private val subscriptionId: Int
) : Transport<String>(phoneNumber) {

    companion object {
        private val TAG = SmsTransport::class.simpleName
    }

    private val settings = SettingsRepository.getInstance(context)
    private val accessRepo = AccessRepository.getInstance(context)
    private val tempAllowlistRepo = TemporaryAllowlistRepository.getInstance(context)

    @get:DrawableRes
    override val icon = R.drawable.ic_sms

    @get:StringRes
    override val title = R.string.transport_sms_title

    private val keyword = settings.get(Settings.SET_FMD_COMMAND) as String
    override val description = context.getString(R.string.transport_sms_description, keyword)

    override val descriptionAuth =
        context.getString(R.string.transport_sms_description_auth, keyword)

    override val descriptionNote = context.getString(R.string.transport_sms_description_note)

    override val requiredPermissions = listOf(SmsPermission())

    override val actions = listOf(TransportAction(R.string.Settings_Access_Control) { activity ->
        activity.startActivity(Intent(context, AccessControlActivity::class.java))
    })

    override fun getDestinationString() = phoneNumber

    override suspend fun isAllowed(parsed: ParserResult.Success): AccessResponse {
        var isKnownButDenied = false

        // Case 1: phone number in Allowed Contacts
        val storedNumber = accessRepo.getPhoneNumber(phoneNumber)
        if (storedNumber != null) {
            val hasPermission = storedNumber.permission.hasPermission(parsed.command.permission)
            if (hasPermission) {
                context.log().i(TAG, "${storedNumber.toDisplayLabel()} used FMD via allowlist")
                return AccessResponse.ALLOWED
            } else {
                // Even if the number is in the list of phone numbers and is explicitly denied this command, continue anyway.
                // Check below if there is a password that allows the command.
                // As long as one element allows access, that is sufficient.
                context.log().i(
                    TAG,
                    "${storedNumber.toDisplayLabel()} denied access to ${parsed.command.keyword}. Continuing to check for password."
                )
                isKnownButDenied = true
            }
        }

        // Case 2: phone number in temporary allowlist (i.e., it send the correct PIN earlier)
        // TODO: Check permission of the SMS password that added this number to the allowlist
        /*
        if (tempAllowlistRepo.containsValidNumber(phoneNumber)) {
            context.log().i(TAG, "$phoneNumber used FMD via temporary allowlist")
            return AccessResponse.ALLOWED
        }
         */

        // Case 3: the message contains the correct PIN
        val pinAccessPossible = parsed.pin != null
        if (pinAccessPossible) {
            val smsPass = accessRepo.getSmsPassword(parsed.pin)
            if (smsPass != null) {
                val hasPermission = smsPass.permission.hasPermission(parsed.command.permission)
                if (hasPermission) {
                    context.log()
                        .i(TAG, "$phoneNumber used FMD via SMS password '${smsPass.label}'")
                    // TODO: update strings, remove PIN, add label
                    send(context, context.getString(R.string.MH_Pin_Accepted))
                    Notifications.notify(
                        context,
                        context.getString(R.string.usage_notification_pin_title),
                        context.getString(R.string.usage_notification_pin_text, phoneNumber),
                        Notifications.CHANNEL_PIN
                    )

                    tempAllowlistRepo.add(phoneNumber, subscriptionId)
                    TempContactExpiredService.scheduleJob(
                        context,
                        TEMP_USAGE_VALIDITY_MILLIS + 1000
                    )

                    return AccessResponse.ALLOWED
                }
            }
        }

        return if (isKnownButDenied) AccessResponse.DENIED_EXISTS else AccessResponse.DENIED_UNKNOWN
    }

    override fun send(context: Context, msg: String) {
        super.send(context, msg)

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val defaultSmsManager = context.getSystemService(SmsManager::class.java)
            if (subscriptionId == -1) {
                defaultSmsManager
            } else {
                defaultSmsManager.createForSubscriptionId(subscriptionId)
            }
        } else {
            if (subscriptionId == -1) {
                SmsManager.getDefault()
            } else {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
        }

        if (msg.length <= 160) {
            smsManager.sendTextMessage(phoneNumber, null, msg, null, null)
        } else {
            val parts = smsManager.divideMessage(msg)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
        }
    }
}