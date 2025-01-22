package de.nulide.findmydevice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import de.nulide.findmydevice.services.FMDSMSService


class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            // wrong receiver
            return
        }
        val bundle = intent.extras ?: return
        val format = bundle.getString("format") ?: return
        val pdus = bundle.get("pdus") as Array<ByteArray>? ?: return
        val subscriptionId = intent.getIntExtra("subscription", -1)

        for (pdu in pdus) {
            val sms = SmsMessage.createFromPdu(pdu, format)
            FMDSMSService.scheduleJob(
                context,
                sms.originatingAddress,
                subscriptionId,
                sms.messageBody,
            )
        }
    }
}
