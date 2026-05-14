package de.nulide.findmydevice.ui.access

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.nulide.findmydevice.R
import de.nulide.findmydevice.data.AccessRepository
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.database.NotificationPassword
import de.nulide.findmydevice.database.PhoneNumber
import de.nulide.findmydevice.database.SmsPassword
import de.nulide.findmydevice.ui.FmdActivity
import de.nulide.findmydevice.ui.common.validatePassword
import de.nulide.findmydevice.utils.normalizePhoneNumber
import kotlinx.coroutines.launch

class AccessControlActivity : FmdActivity(), AccessControlFuns {

    companion object {
        const val EXTRA_NOTIF = "EXTRA_NOTIF"
    }

    private lateinit var accessRepo: AccessRepository
    private lateinit var settings: SettingsRepository

    private val viewModel: AccessControlViewModel by viewModels { AccessControlViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        accessRepo = AccessRepository.getInstance(this)
        settings = SettingsRepository.getInstance(this)

        val initialPage = if (intent.extras?.getInt(EXTRA_NOTIF) != null) 2 else 0

        setContent {
            AccessControlTabsScreen(
                onBackClicked = { finish() },
                accessFuns = this,
                initialPage,
            )
        }
    }

    /* ------- Phone numbers ------- */

    // XXX: Migrate these adding dialogs to Compose??
    override fun onAddPhoneNumberClicked() {
        val context = this
        val layout = layoutInflater.inflate(R.layout.dialog_phone_number, null)
        val nameInput = layout.findViewById<EditText>(R.id.editTextName)
        val phoneNumberInput = layout.findViewById<EditText>(R.id.editTextPhoneNumber)

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.allowlist_add_phone_number))
            .setView(layout)
            .setPositiveButton(
                getString(R.string.add),
                { _, _ ->
                    val name = nameInput.getText().toString()
                    val number = phoneNumberInput.getText().toString()
                    lifecycleScope.launch {
                        addContactToAllowList(number, name)
                    }
                })
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onAddContactClicked() {
        var intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            pickContactLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try {
                pickContactLauncher.launch(intent)
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, R.string.WhiteList_no_contact_picker, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            return@registerForActivityResult
        }
        val data = result.data ?: return@registerForActivityResult

        // Multiple items selected
        val clipData = data.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let { addContactFromUri(it) }
            }
        }

        // Single item selected
        data.data?.let { addContactFromUri(it) }
    }

    private fun addContactFromUri(uri: Uri) {
        val projection = arrayOf<String>(
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = managedQuery(uri, projection, null, null, null)

        if (!cursor.moveToFirst()) {
            // cursor is empty
            return
        }
        do {
            val nameIdx = cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
            var cName = ""
            if (nameIdx >= 0) {
                cName = cursor.getString(nameIdx)
            }

            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            var cNumber = ""
            if (numIdx >= 0) {
                cNumber = cursor.getString(numIdx)
            }

            lifecycleScope.launch {
                addContactToAllowList(cNumber, cName)
            }
        } while (cursor.moveToNext())
    }

    private suspend fun addContactToAllowList(rawNumber: String, name: String) {
        val normNumber = normalizePhoneNumber(this, rawNumber)
        if (normNumber == null) {
            Toast.makeText(this, R.string.allowlist_invalid_number, Toast.LENGTH_LONG).show()
            return
        }
        if (accessRepo.getPhoneNumber(normNumber) != null) {
            Toast.makeText(this, R.string.Toast_Duplicate_contact, Toast.LENGTH_LONG).show()
            return
        }

        val newNumber = PhoneNumber(0, name, normNumber)
        accessRepo.insertPhoneNumber(newNumber)

        if (!(settings.get(Settings.SET_FIRST_TIME_CONTACT_ADDED) as Boolean)) {
            val keyword = settings.get(Settings.SET_FMD_COMMAND) as String
            val message =
                getString(R.string.tip_first_contact_added, keyword, keyword, keyword)
            MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    settings.set<Boolean>(Settings.SET_FIRST_TIME_CONTACT_ADDED, true)
                }
                .show()
        }
    }

    /* ------- SMS Passwords ------- */

    private fun showAddPasswordDialog(
        @StringRes title: Int,
        onSaveClicked: suspend (label: String, password: String) -> Unit,
    ) {
        val context = this
        val layout = layoutInflater.inflate(R.layout.dialog_password_labelled, null)
        val labelInput = layout.findViewById<EditText>(R.id.editTextLabel)
        val passwordInput = layout.findViewById<EditText>(R.id.editTextPassword)

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton(
                getString(R.string.add),
                { _, _ ->
                    val label = labelInput.getText().toString()
                    val password = passwordInput.getText().toString()

                    validatePassword(context, password, forceMinLength = true, allowEmpty = false) {
                        lifecycleScope.launch {
                            onSaveClicked(label, password)
                        }
                    }
                })
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onAddSmsPasswordClicked() {
        showAddPasswordDialog(R.string.access_sms_password_add, { label, password ->
            onSubmitSmsPassword(SmsPassword(0, label, password))
        })
    }

    private suspend fun onSubmitSmsPassword(password: SmsPassword) {
        val old = accessRepo.getSmsPassword(password.password)
        if (old != null) {
            val msg = getString(R.string.access_password_exists, old.label)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        accessRepo.insertSmsPassword(password)
    }

    /* ------- Notification Passwords ------- */

    override fun onAddNotificationPasswordClicked() {
        showAddPasswordDialog(R.string.access_notification_password_add, { label, password ->
            onSubmitNotificationPassword(NotificationPassword(0, label, password))
        })
    }

    private suspend fun onSubmitNotificationPassword(password: NotificationPassword) {
        val old = accessRepo.getNotificationPassword(password.password)
        if (old != null) {
            val msg = getString(R.string.access_password_exists, old.label)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        accessRepo.insertNotificationPassword(password)
    }

}
