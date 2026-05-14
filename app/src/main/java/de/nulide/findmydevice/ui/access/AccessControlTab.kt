package de.nulide.findmydevice.ui.access

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nulide.findmydevice.R
import de.nulide.findmydevice.database.AccessItem
import de.nulide.findmydevice.database.PhoneNumber
import de.nulide.findmydevice.ui.theme.AppTheme

// Help class so that we can both:
// 1. Pass a ViewModel to create a tab to keep the upper code simple.
// 2. Have a variant with explicit functions that we can write Previews for.
@Composable
fun <T : AccessItem> AccessControlTab(
    accessType: AccessType<T>,
    accessItems: List<T>,
    onAddClicked: () -> Unit,
    onAddSecondaryClicked: (() -> Unit)?,
    viewModel: AccessControlViewModel = viewModel(factory = AccessControlViewModel.Factory),
) {
    AccessControlTab(
        accessType,
        accessItems,
        onAddClicked,
        onAddSecondaryClicked,
        onDeleteClicked = { viewModel.deleteItem(it) },
        onSavePermissionClicked = { item, newPerm ->
            viewModel.updatePermissionForItem(item, newPerm)
        },
        commandKeyword = viewModel.getCommandKeyword(),
    )
}

@Composable
fun <T : AccessItem> AccessControlTab(
    accessType: AccessType<T>,
    accessItems: List<T>,
    onAddClicked: () -> Unit = {},
    onAddSecondaryClicked: (() -> Unit)? = null,
    onDeleteClicked: (T) -> Unit = {},
    onSavePermissionClicked: (T, Long) -> Unit = { _, _ -> },
    commandKeyword: String = "fmd",
) {
    var permissionToEdit: T? by rememberSaveable { mutableStateOf(null) }
    var itemToDelete: T? by rememberSaveable { mutableStateOf(null) }
    val dismissDialog = { permissionToEdit = null; itemToDelete = null }

    // Main body
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(all = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (accessItems.isEmpty()) {
            Text(stringResource(accessType.hintText, commandKeyword))
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(accessType.emptyText),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                item {
                    Text(stringResource(accessType.hintText, commandKeyword))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                }
                items(accessItems) { item ->
                    ItemRow(
                        item,
                        onEditPermissionsClicked = { permissionToEdit = item },
                        onDeleteClicked = { itemToDelete = item })
                }
            }
        }

        // Bottom buttons
        Button(
            onClick = onAddClicked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(accessType.addText))
        }

        if (onAddSecondaryClicked != null && accessType.addSecondaryText != null) {
            Button(
                onClick = onAddSecondaryClicked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(accessType.addSecondaryText))
            }
        }
    }

    // Dialogs
    permissionToEdit?.let { item ->
        FmdPermissionDialog(item.getItemPermission(), dismissDialog) { newPerm ->
            onSavePermissionClicked(item, newPerm)
            dismissDialog()
        }
    }
    itemToDelete?.let { toDelete ->
        DeleteDialog(toDelete, dismissDialog, onDeleteClicked, accessType.deleteTitle)
    }
}

@Composable
private fun <T : AccessItem> ItemRow(
    item: T,
    onEditPermissionsClicked: (T) -> Unit,
    onDeleteClicked: (T) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(text = item.toDisplayLabel(), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        OutlinedIconButton({ onEditPermissionsClicked(item) }) {
            Icon(
                painterResource(R.drawable.ic_shield),
                contentDescription = stringResource(R.string.fmd_permission_allowed_commands_title),
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedIconButton({ onDeleteClicked(item) }) {
            Icon(
                painterResource(R.drawable.ic_delete_outline),
                contentDescription = stringResource(R.string.Delete),
            )
        }
    }
}

@Composable
private fun <T> DeleteDialog(
    toDelete: T,
    dismissDialog: () -> Unit,
    onDeleteClicked: (T) -> Unit,
    @StringRes deleteTitle: Int,
) {
    AlertDialog(
        onDismissRequest = dismissDialog,
        confirmButton = {
            TextButton(
                onClick = {
                    dismissDialog()
                    onDeleteClicked(toDelete)
                }
            ) {
                Text(stringResource(R.string.Delete))
            }
        },
        dismissButton = {
            TextButton(onClick = dismissDialog) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(deleteTitle)) },
        text = { Text(stringResource(R.string.allowlist_delete_message)) },
    )
}

@Preview
@Composable
private fun TabEmptyPreview() {
    AppTheme {
        Surface {
            AccessControlTab(
                accessType = ACCESS_PHONE_NUMBER,
                accessItems = emptyList(),
            )
        }
    }
}

@Preview
@Composable
private fun TabPreview() {
    AppTheme {
        Surface {
            AccessControlTab(
                accessType = ACCESS_PHONE_NUMBER,
                accessItems = listOf(
                    PhoneNumber(0, "John Doe", "+1 234 567 89"),
                    PhoneNumber(0, "Max Muster", "+49 79 123 456 78"),
                )
            )
        }
    }
}
