package de.nulide.findmydevice.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.nulide.findmydevice.R
import de.nulide.findmydevice.commands.Command
import de.nulide.findmydevice.commands.availableCommands
import de.nulide.findmydevice.utils.CypherUtils

class PasswordSetDialog(
    val context: Context,
    val onSuccess: (String) -> Unit,
    @StringRes val title: Int = R.string.password_enter,
    val message: String? = null,
    val forceMinLength: Boolean = true,
    val allowEmpty: Boolean = false,
) {

    var dialog: AlertDialog

    init {
        val passwordLayout: View =
            LayoutInflater.from(context).inflate(R.layout.dialog_password_set, null)
        val editTextPassword = passwordLayout.findViewById<EditText>(R.id.editTextPassword)

        if (!message.isNullOrBlank()) {
            val textView = passwordLayout.findViewById<TextView>(R.id.messagePassword)
            textView.text = message
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(passwordLayout)
            .setPositiveButton(R.string.Ok, { _, _ ->
                val password = editTextPassword.getText().toString()

                if (password.isBlank()) {
                    if (allowEmpty) {
                        onSuccess("")
                    } else {
                        Toast.makeText(context, R.string.pw_change_empty, Toast.LENGTH_LONG).show()
                    }
                } else if (availableCommands(context).stream()
                        .anyMatch { cmd: Command? -> cmd!!.keyword == password }
                ) {
                    Toast.makeText(
                        context,
                        R.string.password_match_command_keyword,
                        Toast.LENGTH_LONG
                    ).show()
                } else if (forceMinLength && password.length < CypherUtils.MIN_PASSWORD_LENGTH) {
                    Toast.makeText(context, R.string.password_min_length, Toast.LENGTH_LONG)
                        .show()
                } else {
                    onSuccess(password)
                }
            })
            .setNegativeButton(context.getString(R.string.cancel), { _, _ -> })
            .setCancelable(false)
            .create()
    }

    fun show() {
        dialog.show();
    }
}
