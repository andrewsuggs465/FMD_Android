package de.nulide.findmydevice.ui.access

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.nulide.findmydevice.R
import de.nulide.findmydevice.data.AllowlistRepository
import de.nulide.findmydevice.data.Contact
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.ui.FmdActivity
import de.nulide.findmydevice.ui.UiUtil.Companion.setupEdgeToEdge
import de.nulide.findmydevice.ui.allowlist.AllowlistAdapter

class AccessControlActivity : FmdActivity() {
    private lateinit var allowlistRepository: AllowlistRepository
    private lateinit var settings: SettingsRepository

    private lateinit var allowlistAdapter: AllowlistAdapter

    private lateinit var textWhitelistEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_allowlist)

        setupEdgeToEdge(findViewById(android.R.id.content))

        allowlistRepository = AllowlistRepository.getInstance(this)
        settings = SettingsRepository.getInstance(this)

        allowlistAdapter = AllowlistAdapter(::onDeleteContact)
        val recyclerView = findViewById<RecyclerView>(R.id.recycler_allowlist)
        recyclerView.setAdapter(allowlistAdapter)

        textWhitelistEmpty = findViewById(R.id.whitelistEmpty)
        findViewById<View>(R.id.buttonAddContact).setOnClickListener(::onAddContactClicked)
        findViewById<View>(R.id.buttonAddPhoneNumber).setOnClickListener(::onAddPhoneNumberClicked)

        updateScreen()
    }

    private fun updateScreen() {
        textWhitelistEmpty.isVisible = allowlistRepository.list.isEmpty()

        allowlistAdapter.submitContactList(allowlistRepository.list)
    }

    private fun onAddPhoneNumberClicked(v: View) {
        val context = v.context
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
                    val dummyContact = Contact.from(context, name, number)
                    addContactToAllowList(dummyContact)
                })
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun onAddContactClicked(v: View) {
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

            val contact = Contact.from(this, cName, cNumber)
            if (contact != null) {
                addContactToAllowList(contact)
            }
        } while (cursor.moveToNext())
    }

    private fun addContactToAllowList(contact: Contact?) {
        if (contact == null) {
            Toast.makeText(this, R.string.allowlist_invalid_number, Toast.LENGTH_LONG).show()
            return
        }
        if (allowlistRepository.contains(contact)) {
            Toast.makeText(this, R.string.Toast_Duplicate_contact, Toast.LENGTH_LONG).show()
            return
        }

        allowlistRepository.add(contact)
        updateScreen()

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

    private fun onDeleteContact(phoneNumber: String) {
        allowlistRepository.remove(phoneNumber)
        updateScreen()
    }
}