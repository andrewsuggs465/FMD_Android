package de.nulide.findmydevice.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ListView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mikepenz.aboutlibraries.LibsBuilder
import de.nulide.findmydevice.R
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.ui.TaggedFragment
import de.nulide.findmydevice.ui.common.PasswordSetDialog
import de.nulide.findmydevice.ui.helper.SettingsEntry
import de.nulide.findmydevice.ui.helper.SettingsViewAdapter
import de.nulide.findmydevice.utils.SettingsImportExporter
import de.nulide.findmydevice.utils.SettingsImportExporter.Companion.filenameForExport
import de.nulide.findmydevice.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.File

class SettingsFragment : TaggedFragment() {

    companion object {
        private const val EXPORT_REQ_CODE = 30
        private const val IMPORT_REQ_CODE = 40

        private const val TEMP_ZIP_NAME = "tmp_backup_import.zip"

        val TAG = SettingsFragment::class.simpleName
    }

    private lateinit var settings: SettingsRepository

    override fun getStaticTag(): String = "SettingsFragment"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.Companion.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val settingsEntries = SettingsEntry.getSettingsEntries(view.context)

        val listSettings = view.findViewById<ListView>(R.id.listSettings)
        listSettings.setAdapter(SettingsViewAdapter(view.context, settingsEntries))
        listSettings.onItemClickListener =
            OnItemClickListener { parent: AdapterView<*>, view: View, position: Int, id: Long ->
                this.onItemClick(parent, view, position, id)
            }
    }

    fun onItemClick(parent: AdapterView<*>, view: View, position: Int, id: Long) {
        val context = view.context

        var settingIntent: Intent? = null
        when (position) {
            0 -> settingIntent = Intent(context, FMDConfigActivity::class.java)
            1 -> settingIntent = if (!settings.serverAccountExists()) {
                Intent(context, AddAccountActivity::class.java)
            } else {
                Intent(context, FMDServerActivity::class.java)
            }

            2 -> settingIntent = Intent(context, AllowlistActivity::class.java)
            3 -> settingIntent = Intent(context, OpenCellIdActivity::class.java)
            4 -> settingIntent = Intent(context, AppearanceActivity::class.java)
            5 -> {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                intent.putExtra(Intent.EXTRA_TITLE, filenameForExport())
                intent.setType("*/*")
                startActivityForResult(intent, EXPORT_REQ_CODE)
            }

            6 -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.setType("*/*")
                startActivityForResult(intent, IMPORT_REQ_CODE)
            }

            7 -> settingIntent = Intent(context, LogViewActivity::class.java)
            8 -> {
                val activityTitle = getString(R.string.Settings_About)
                settingIntent = LibsBuilder().withActivityTitle(activityTitle)
                    .withListener(AboutLibsListener.listener).intent(context)
            }

            9 -> settingIntent = Intent(context, DebuggingActivity::class.java)
        }

        if (settingIntent != null) {
            startActivity(settingIntent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        val context = activity ?: return
        if (requestCode == IMPORT_REQ_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                val uri = data.data
                if (uri != null) {
                    handleImport(context, uri)
                } else {
                    context.log().w(TAG, "Cannot import: URI is null!")
                }
            }
        } else if (requestCode == EXPORT_REQ_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                val uri = data.data
                if (uri != null) {
                    showExportPasswordDialog(context, uri)
                } else {
                    context.log().w(TAG, "Cannot export: URI is null!")
                }
            }
        }
    }

    private fun showExportPasswordDialog(context: Context, uri: Uri) {
        val message = context.getString(R.string.export_password_message) +
                "\n\n" + context.getString(R.string.password_min_length) +
                "\n\n" + context.getString(R.string.export_password_message_empty_warning)

        PasswordSetDialog(
            context,
            { password ->
                lifecycleScope.launch {
                    SettingsImportExporter(context).exportData(uri, password)
                }
            },
            R.string.export_password_enter_title,
            message,
            allowEmpty = true,
        ).show()
    }

    private fun handleImport(context: Context, uri: Uri) = lifecycleScope.launch {
        // Copy to temporary file
        val tempFile = File(context.cacheDir, TEMP_ZIP_NAME)

        // Reading from the input stream can make a network request (if the URI comes from a cloud storage)
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        var isZipEncrypted = false
        var zipFile: ZipFile? = null
        try {
            zipFile = ZipFile(tempFile)
            isZipEncrypted = zipFile.isEncrypted
            context.log().d(TAG, "isZipEncrypted=$isZipEncrypted")
        } catch (e: ZipException) {
            context.log().w(TAG, "Failed to check if ZIP is encrypted:\n${e.message}")
        } finally {
            zipFile?.close()
        }

        if (isZipEncrypted) {
            showImportPasswordDialog(context)
        } else {
            executeImport(context, null)
        }
    }

    private fun showImportPasswordDialog(context: Context) {
        val message = context.getString(R.string.import_password_message)

        PasswordSetDialog(
            context,
            { password -> executeImport(context, password) },
            R.string.export_password_enter_title,
            message,
            forceMinLength = false,
        ).show()
    }

    private fun executeImport(context: Context, password: String?) = lifecycleScope.launch {
        val tempFile = File(context.cacheDir, TEMP_ZIP_NAME)
        val isSuccessful = SettingsImportExporter(context).importData(tempFile.toUri(), password)
        if (isSuccessful) {
            showImportSuccessDialog(context)
        } else {
            showImportFailedDialog(context)
        }

        // Delete after finishing the import
        tempFile.delete()
    }

    private fun showImportSuccessDialog(context: Context) {
        // TODO: The message_warning is temporary. We should include these in the backup again.
        val message =
            getString(R.string.import_success_message) + "\n\n" + getString(R.string.import_success_message_warning)
        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.Settings_Import_Success))
            .setMessage(message)
            .setPositiveButton(getString(R.string.Ok)) { _, _ -> }
            .setCancelable(false)
            .show()
    }

    private fun showImportFailedDialog(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.Settings_Import_Failed))
            .setMessage(R.string.import_failed_message)
            .setPositiveButton(getString(R.string.Ok)) { _, _ -> }
            .setCancelable(false)
            .show()
    }
}
