package de.nulide.findmydevice.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import de.nulide.findmydevice.R
import de.nulide.findmydevice.commands.RING_DURATION_DEFAULT_SECS
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.tasks.RingerTimerTask
import de.nulide.findmydevice.utils.RingerUtils
import java.util.Timer


const val EXTRA_RING_DURATION: String = "EXTRA_RING_DURATION"

class RingerActivity : FmdActivity() {

    private var ringerTask: RingerTimerTask? = null

    companion object {
        fun newInstance(context: Context, duration: Int) {
            val intent = Intent(context, RingerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_RING_DURATION, duration)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ring)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val settings = SettingsRepository.Companion.getInstance(this)
        val ringtone =
            RingerUtils.getRingtone(this, settings.get(Settings.SET_RINGER_TONE) as String)

        val bundle = intent.extras
        val durationSec: Int = bundle?.getInt(EXTRA_RING_DURATION) ?: RING_DURATION_DEFAULT_SECS
        val durationPeriod = durationSec * 100L

        val t = Timer()
        ringerTask = RingerTimerTask(t, ringtone, this)
        t.schedule(ringerTask, 0, durationPeriod)
        ringtone.play()

        val buttonStopRinging = findViewById<Button>(R.id.buttonStopRinging)
        buttonStopRinging.setOnClickListener { stopRinging() }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        stopRinging()
    }

    private fun stopRinging() {
        ringerTask?.stop()
        finish()
    }
}
