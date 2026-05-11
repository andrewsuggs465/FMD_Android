package de.nulide.findmydevice.ui.access

import android.app.Dialog
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.DialogFragment
import de.nulide.findmydevice.ui.theme.AppTheme

/**
 * Simple wrapper class to use FmdPermissionDialog from traditional XML views.
 */
class FmdPermissionDialogFragment : DialogFragment() {

    interface Listener {
        fun onPermissionCancelClicked()
        fun onPermissionSaveClicked(newValue: Long)
    }

    companion object {
        private const val ARG_INITIAL_PERMISSION = "initial_permission"

        fun newInstance(initialPermission: Long): FmdPermissionDialogFragment {
            return FmdPermissionDialogFragment().apply {
                arguments = Bundle().apply { putLong(ARG_INITIAL_PERMISSION, initialPermission) }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val initialPermission = requireArguments().getLong(ARG_INITIAL_PERMISSION)
        val listener = parentFragment as? Listener
            ?: requireActivity() as? Listener
            ?: error("Host must implement FmdPermissionDialogFragment.Listener")

        return Dialog(context).apply {
            setContentView(ComposeView(context).apply {
                setContent {
                    AppTheme {
                        FmdPermissionDialog(
                            initialPermission,
                            onCancelClicked = { listener.onPermissionCancelClicked() },
                            onSaveClicked = { listener.onPermissionSaveClicked(it) },
                        )
                    }
                }
            })
        }
    }

}
