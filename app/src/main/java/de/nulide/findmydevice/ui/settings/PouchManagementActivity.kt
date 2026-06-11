package de.nulide.findmydevice.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
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

        binding.btnPair.setOnClickListener { showPairDialog() }
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

        val btnRemove = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(android.R.string.cancel).replace("Cancel", "Remove")
            text = "Remove"
            setOnClickListener { confirmRemove(uid) }
        }

        textBlock.addView(tvName)
        textBlock.addView(tvLastSeen)
        inner.addView(textBlock)
        inner.addView(btnRemove)
        card.addView(inner)
        parent.addView(card)
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

    private fun showPairDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pair_pouch, null)
        val etUid = view.findViewById<TextInputEditText>(R.id.editDeviceId)
        val etPw = view.findViewById<TextInputEditText>(R.id.editPassword)
        val etToken = view.findViewById<TextInputEditText>(R.id.editToken)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sp_pair_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.sp_pair_button) { _, _ ->
                val uid = etUid.text?.toString()?.trim() ?: ""
                val pw = etPw.text?.toString() ?: ""
                val token = etToken.text?.toString()?.trim() ?: ""
                if (uid.isEmpty() || pw.isEmpty() || token.isEmpty()) {
                    Toast.makeText(this, R.string.sp_pair_error_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                registerPouch(uid, pw, token)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun registerPouch(uid: String, password: String, token: String) {
        val toast = Toast.makeText(this, R.string.sp_pair_registering, Toast.LENGTH_LONG)
        toast.show()

        Thread {
            bleRepo.registerPouch(
                bleUid = uid,
                password = password,
                registrationToken = token,
                onSuccess = {
                    runOnUiThread {
                        toast.cancel()
                        Toast.makeText(this, R.string.sp_pair_success, Toast.LENGTH_SHORT).show()
                        BleTrackerScanService.start(this)
                        refreshList()
                    }
                },
                onError = { msg ->
                    runOnUiThread {
                        toast.cancel()
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }.start()
    }
}
