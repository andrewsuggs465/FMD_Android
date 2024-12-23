package de.nulide.findmydevice.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import de.nulide.findmydevice.R
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.tasks.RingerTimerTask
import de.nulide.findmydevice.utils.RingerUtils
import java.util.Timer


const val RING_DURATION: String = "rduration"

class RingerActivity : FmdActivity(), View.OnClickListener {

    private var ringerTask: RingerTimerTask? = null
    private var buttonStopRinging: Button? = null

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
        val t = Timer()
        ringerTask = RingerTimerTask(t, ringtone, this)
        t.schedule(ringerTask, 0, (bundle!!.getInt(RING_DURATION) * 100).toLong())
        ringtone.play()

        buttonStopRinging = findViewById<Button>(R.id.buttonStopRinging)
        buttonStopRinging!!.setOnClickListener(this)
    }

    override fun onClick(v: View?) {
        if (v === buttonStopRinging) {
            ringerTask!!.stop()
            finish()
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        ringerTask!!.stop()
        finish()
    }
}