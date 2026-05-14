package de.nulide.findmydevice.ui.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nulide.findmydevice.FmdApplication
import de.nulide.findmydevice.data.AccessRepository
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.database.AccessItem
import de.nulide.findmydevice.database.NotificationPassword
import de.nulide.findmydevice.database.PhoneNumber
import de.nulide.findmydevice.database.SmsPassword
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AccessControlViewModel(
    private val accessRepo: AccessRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    companion object {
        // https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-factories
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[APPLICATION_KEY] as FmdApplication)
                AccessControlViewModel(
                    accessRepo = AccessRepository.getInstance(app.applicationContext),
                    settingsRepo = SettingsRepository.getInstance(app.applicationContext),
                )
            }
        }
    }

    val phoneNumbers = accessRepo.getPhoneNumbers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000L), emptyList())
    val smsPasswords = accessRepo.getSmsPasswords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000L), emptyList())
    val notificationPasswords = accessRepo.getNotificationPasswords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000L), emptyList())

    // The AccessItem provides minimal type-safety. We don't use its interface methods.
    // But because only the entities listed here should implement AccessItem, it reduces the
    // risk of callers accidentally passing a wrong item.
    fun updatePermissionForItem(item: AccessItem, newPerm: Long) {
        viewModelScope.launch {
            when (item) {
                is PhoneNumber -> accessRepo.updatePhoneNumber(item.copy(permission = newPerm))
                is SmsPassword -> accessRepo.updateSmsPassword(item.copy(permission = newPerm))
                is NotificationPassword -> accessRepo.updateNotificationPassword(
                    item.copy(permission = newPerm)
                )

                else -> throw IllegalStateException("Cannot update perms for unsupported item: $item")
            }
        }
    }

    fun deleteItem(item: AccessItem) {
        viewModelScope.launch {
            when (item) {
                is PhoneNumber -> accessRepo.deletePhoneNumber(item)
                is SmsPassword -> accessRepo.deleteSmsPassword(item)
                is NotificationPassword -> accessRepo.deleteNotificationPassword(item)
                else -> throw IllegalStateException("Cannot delete unsupported item: $item")
            }
        }
    }

    fun getCommandKeyword(): String {
        return settingsRepo.get(Settings.SET_FMD_COMMAND) as String
    }
}
