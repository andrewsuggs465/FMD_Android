package de.nulide.findmydevice.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.nulide.findmydevice.R
import de.nulide.findmydevice.databinding.ActivityPouchManagementBinding
import de.nulide.findmydevice.securepouch.BleTrackerRepository
import de.nulide.findmydevice.services.BleTrackerScanService
import de.nulide.findmydevice.ui.FmdActivity
import de.nulide.findmydevice.ui.UiUtil.Companion.setupEdgeToEdgeAppBar
import de.nulide.findmydevice.ui.UiUtil.Companion.setupEdgeToEdgeScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PouchManagementActivity : FmdActivity() {

    private lateinit var binding: ActivityPouchManagementBinding
    private lateinit var bleRepo: BleTrackerRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPouchManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdgeAppBar(binding.appBar)
        setupEdgeToEdgeScrollView(binding.scrollView)

        bleRepo = BleTrackerRepository(this)

        binding.btnPair.text = getString(R.string.sp_pair_new)
        binding.btnPair.setOnClickListener {
            startActivity(Intent(this, BleScanActivity::class.java))
        }
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val list = binding.pouchList
        list.removeAllViews()

        val uids = bleRepo.getPouchUids()
        binding.textNoPouches.isVisible = uids.isEmpty()

        for (uid in uids.sorted()) {
            addPouchRow(list, uid)
        }
    }

    private fun addPouchRow(parent: LinearLayout, uid: String) {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = resources.getDimensionPixelSize(R.dimen.margin_small) }
            radius = resources.getDimension(R.dimen.margin_small)
            cardElevation = resources.getDimension(R.dimen.margin_small)
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                resources.getDimensionPixelSize(R.dimen.margin_normal),
                resources.getDimensionPixelSize(R.dimen.margin_normal),
                resources.getDimensionPixelSize(R.dimen.margin_small),
                resources.getDimensionPixelSize(R.dimen.margin_normal),
            )
        }

        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvName = TextView(this).apply {
            text = uid
            textSize = 16f
        }

        val tvLastSeen = TextView(this).apply {
            text = formatLastSeen(bleRepo.getLastSeen(uid))
            textSize = 13f
            alpha = 0.7f
        }

        val btnControls = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Controls"
            setOnClickListener { showControls(uid) }
        }

        val btnRemove = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Remove"
            setOnClickListener { confirmRemove(uid) }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        actions.addView(btnControls)
        actions.addView(btnRemove)

        textBlock.addView(tvName)
        textBlock.addView(tvLastSeen)
        inner.addView(textBlock)
        inner.addView(actions)
        card.addView(inner)
        parent.addView(card)
    }

    /**
     * Show the SecurePouch command menu. Each command is queued locally and
     * relayed to the pouch over BLE the next time the scan service sees it
     * (typically within the 30 s scan cycle), then echoed to the FMD server.
     */
    private fun showControls(uid: String) {
        // Labels paired with the FMD command strings (= firmware SP_CMD_*).
        val labels = arrayOf("Lock", "Unlock", "Arm", "Disarm", "Alarm", "Silence", "Locate")
        val commands = arrayOf("lock", "unlock", "arm", "disarm", "alarm", "silence", "locate")
        MaterialAlertDialogBuilder(this)
            .setTitle(uid)
            .setItems(labels) { _, which ->
                bleRepo.queueLocalCommand(uid, commands[which])
                // Make sure the scanner is running so the command gets delivered.
                BleTrackerScanService.start(this)
                android.widget.Toast.makeText(
                    this,
                    "${labels[which]} queued — will relay when the pouch is in range",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatLastSeen(timestampMs: Long?): String {
        if (timestampMs == null) return getString(R.string.sp_last_seen_never)
        val elapsed = System.currentTimeMillis() - timestampMs
        val ago = when {
            elapsed < TimeUnit.MINUTES.toMillis(2) -> "just now"
            elapsed < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(elapsed)} min"
            elapsed < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(elapsed)} h"
            else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestampMs))
        }
        return if (ago == "just now") "Last seen: just now"
        else getString(R.string.sp_last_seen_format, ago)
    }

    private fun confirmRemove(uid: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sp_unpair_confirm_title)
            .setMessage(getString(R.string.sp_unpair_confirm_message, uid))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                bleRepo.removePouch(uid)
                if (!bleRepo.hasPouches()) {
                    BleTrackerScanService.stop(this)
                }
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

}
