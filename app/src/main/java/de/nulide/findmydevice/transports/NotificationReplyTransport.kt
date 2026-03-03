package de.nulide.findmydevice.transports

import android.app.Notification
import android.app.PendingIntent.CanceledException
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.robj.notificationhelperlibrary.utils.NotificationUtils
import de.nulide.findmydevice.R
import de.nulide.findmydevice.commands.ParserResult
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.permissions.NotificationAccessPermission
import de.nulide.findmydevice.services.NotificationListenService
import de.nulide.findmydevice.utils.log


class NotificationReplyTransport(
    private val context: Context,
    // should only be null for the availableTransports list
    private val destination: StatusBarNotification?
) : Transport<StatusBarNotification?>(destination) {

    companion object {
        private val TAG = NotificationReplyTransport::class.simpleName
    }

    private val settings = SettingsRepository.getInstance(context)

    @get:DrawableRes
    override val icon = R.drawable.ic_notifications

    @get:StringRes
    override val title = R.string.transport_notification_reply_title

    private val keyword = settings.get(Settings.SET_FMD_COMMAND) as String
    override val description =
        context.getString(R.string.transport_notification_reply_description, keyword)

    override val descriptionAuth =
        context.getString(R.string.transport_notification_reply_description_auth)

    override val requiredPermissions = listOf(NotificationAccessPermission())

    override fun getDestinationString() = destination?.packageName ?: "Notification Response"

    override fun isAllowed(parsed: ParserResult.Success): Boolean {
        return parsed.pin != null
    }

    override fun send(context: Context, msg: String) {
        super.send(context, msg)
        if (destination == null) {
            context.log().w(TAG, "Cannot reply, destination is null!")
            return
        }

        val action = NotificationUtils.getQuickReplyAction(
            destination.notification, context.packageName
        )
        if (action == null) {
            context.log().i(TAG, "Cannot send message: quick reply action was null")
            return
        }
        try {
            action.sendReply(context, msg)
        } catch (e: CanceledException) {
            context.log().e(TAG, "Failed to send message via notification reply")
            e.printStackTrace()
        }
    }

    override fun closeChannel() {
        super.closeChannel()

        if (destination == null) {
            return
        }

        // Only the NotificationListenService is allowed to dismiss another app's notification.
        NotificationListenService.instance?.cancelNotification(destination.key)

        // As an additional fallback:
        // Try to dismiss the notification via a "mark as read" action, if it exists.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (action in destination.notification.actions) {
                if (action.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) {
                    try {
                        action.actionIntent?.send()
                    } catch (e: Exception) {
                        context.log()
                            .w(TAG, "Failed to mark_as_read: ${e.stackTraceToString()}")
                    }
                }
            }
        }
    }
}
