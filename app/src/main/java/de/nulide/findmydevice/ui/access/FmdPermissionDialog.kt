package de.nulide.findmydevice.ui.access

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.nulide.findmydevice.R
import de.nulide.findmydevice.commands.Command
import de.nulide.findmydevice.commands.FmdPermission
import de.nulide.findmydevice.commands.availableCommandsWithoutHelp
import de.nulide.findmydevice.commands.hasPermission
import de.nulide.findmydevice.commands.setPermission

@Composable
fun FmdPermissionDialog(
    initialPermission: Long,
    onCancelClicked: () -> Unit = {},
    onSaveClicked: (Long) -> Unit = {},
) {
    var permission by rememberSaveable { mutableLongStateOf(initialPermission) }
    val commands = availableCommandsWithoutHelp(LocalContext.current)

    // https://m3.material.io/components/dialogs/specs
    Dialog(onCancelClicked) {
        Card(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.fmd_permission_allowed_commands_title),
                    style = MaterialTheme.typography.headlineSmall,
                )

                // Column of commands/permissions
                LazyColumn(
                    modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
                ) {
                    items(commands) { command ->
                        CommandRow(
                            command,
                            checked = permission.hasPermission(command.permission),
                            onCheckedChange = { checked ->
                                permission = permission.setPermission(command.permission, checked)
                            },
                        )
                    }
                }

                // Button row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancelClicked) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.padding(4.dp))
                    TextButton(onClick = { onSaveClicked(permission) }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(
    command: Command,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = { onCheckedChange(!checked) })
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked,
            onCheckedChange = null, // handled by the row
            modifier = Modifier
                .padding(start = 8.dp, end = 32.dp)
                .size(12.dp),
        )
        Icon(
            painterResource(command.icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .weight(1f),
            text = command.keyword,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Preview
@Composable
private fun FmdPermissionDialogPreview() {
    Surface {
        FmdPermissionDialog(FmdPermission.ALL)
    }
}
