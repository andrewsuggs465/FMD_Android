package de.nulide.findmydevice.ui.access

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nulide.findmydevice.R
import de.nulide.findmydevice.ui.common.FmdTopAppBar
import de.nulide.findmydevice.ui.theme.AppTheme
import kotlinx.coroutines.launch

private data class TabItem(
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
)

private val TABS = listOf(
    TabItem(R.string.access_tab_phone_number, R.drawable.ic_phone),
    TabItem(R.string.access_tab_sms_password, R.drawable.ic_sms),
    TabItem(R.string.access_tab_notification_password, R.drawable.ic_notifications),
)

interface AccessControlFuns {
    fun onAddContactClicked()
    fun onAddPhoneNumberClicked()

    fun onAddSmsPasswordClicked()

    fun onAddNotificationPasswordClicked()
}

@Composable
fun AccessControlTabsScreen(
    onBackClicked: () -> Unit,
    accessFuns: AccessControlFuns,
    initialPage: Int = 0,
    viewModel: AccessControlViewModel = viewModel(factory = AccessControlViewModel.Factory),
) {
    AppTheme {
        Scaffold(
            topBar = { FmdTopAppBar(R.string.Settings_Access_Control, onBackClicked) }
        ) { innerPadding ->
            Column(
                modifier = Modifier.padding(innerPadding),
            ) {
                val pagerState = rememberPagerState(initialPage, pageCount = { TABS.size })
                val coroutineScope = rememberCoroutineScope()

                PrimaryTabRow(pagerState.currentPage) {
                    TABS.forEachIndexed { index, item ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = { Text(stringResource(item.title)) },
                            icon = {
                                Icon(
                                    painterResource(item.icon),
                                    contentDescription = stringResource(item.title),
                                )
                            },
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> {
                            val numbers by viewModel.phoneNumbers
                                .collectAsStateWithLifecycle(emptyList())
                            AccessControlTab(
                                ACCESS_PHONE_NUMBER,
                                numbers,
                                { accessFuns.onAddContactClicked() },
                                { accessFuns.onAddPhoneNumberClicked() },
                            )
                        }

                        1 -> {
                            val smsPasswords by viewModel.smsPasswords
                                .collectAsStateWithLifecycle(emptyList())
                            AccessControlTab(
                                ACCESS_SMS_PASS,
                                smsPasswords,
                                { accessFuns.onAddSmsPasswordClicked() },
                                null,
                            )
                        }

                        2 -> {
                            val notificationPasswords by viewModel.notificationPasswords
                                .collectAsStateWithLifecycle(emptyList())
                            AccessControlTab(
                                ACCESS_NOTIF_PASS,
                                notificationPasswords,
                                { accessFuns.onAddNotificationPasswordClicked() },
                                null,
                            )
                        }
                    }
                }
            }
        }
    }
}
