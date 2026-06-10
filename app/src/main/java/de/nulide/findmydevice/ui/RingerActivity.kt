package de.nulide.findmydevice.ui

import android.app.NotificationManager
import android.app.NotificationManager.INTERRUPTION_FILTER_ALL
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.Ringtone
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import androidx.lifecycle.lifecycleScope
import de.nulide.findmydevice.R
import de.nulide.findmydevice.commands.RING_DURATION_DEFAULT_SECS
import de.nulide.findmydevice.commands.RING_DURATION_MAX_SECS
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.utils.RingerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


const val EXTRA_RING_DURATION: String = "EXTRA_RING_DURATION"

class RingerActivity : FmdActivity() {

    companion object {
        fun newInstance(context: Context, duration: Int) {
            val intent = Intent(context, RingerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_RING_DURATION, duration)
            }
            context.startActivity(intent)
        }
    }

    private var ringtone: Ringtone? = null

    private var oldRingerMode: Int? = null
    private var oldAlarmVolume: Int? = null

    private var oldInterruptionFiler: Int? = null
    private var oldNotificationPolicy: NotificationManager.Policy? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ring)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val buttonStopRinging = findViewById<Button>(R.id.buttonStopRinging)
        buttonStopRinging.setOnClickListener { finish() }

        val settings = SettingsRepository.Companion.getInstance(this)
        ringtone = RingerUtils.getRingtone(this, settings.get(Settings.SET_RINGER_TONE) as String)

        val bundle = intent.extras
        var durationSec: Int = bundle?.getInt(EXTRA_RING_DURATION) ?: RING_DURATION_DEFAULT_SECS
        if (durationSec > RING_DURATION_MAX_SECS) {
            durationSec = RING_DURATION_MAX_SECS
        }

        // Capture the current audio/DND state on the main thread before handing off to the
        // background coroutine. If we let raiseVolume() save these values on Dispatchers.Default,
        // onDestroy() can run concurrently and call resetVolume() before the saves complete,
        // leaving DND permanently disabled.
        saveVolumeState()

        lifecycleScope.launch(Dispatchers.Default) {
            startRinging(durationSec)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        ringtone?.stop()
        resetVolume()
    }

    private suspend fun startRinging(durationSec: Int) {
        applyRaisedVolume()
        ringtone?.play()

        delay(durationSec * 1000L)

        finish()
    }

    /** Save current audio/DND state. Must be called on the main thread before the coroutine starts. */
    private fun saveVolumeState() {
        val audioManager = getSystemService(AudioManager::class.java)
        val notificationManager = getSystemService(NotificationManager::class.java)

        oldRingerMode = audioManager.ringerMode
        oldAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        oldInterruptionFiler = notificationManager.currentInterruptionFilter
        oldNotificationPolicy = notificationManager.notificationPolicy
    }

    /** Apply max-volume / all-notifications settings. Safe to call on any thread. */
    private fun applyRaisedVolume() {
        val audioManager = getSystemService(AudioManager::class.java)
        val notificationManager = getSystemService(NotificationManager::class.java)

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        notificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALL)
    }

    private fun resetVolume() {
        val audioManager = getSystemService(AudioManager::class.java)
        val notificationManager = getSystemService(NotificationManager::class.java)

        oldRingerMode?.let {
            audioManager.ringerMode = it
        }
        oldAlarmVolume?.let {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0)
        }

        oldInterruptionFiler?.let {
            notificationManager.setInterruptionFilter(it)
        }
        oldNotificationPolicy?.let {
            notificationManager.notificationPolicy = it
        }
    }
}
