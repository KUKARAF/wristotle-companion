package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.ui.nav.Screen

/**
 * Top-level app shell: TopAppBar + bottom NavigationBar + NavHost.
 *
 * Each tab is a focused feature screen (Watch / Voice / Models). The
 * NavigationBar surfaces a badge on a tab whenever something on that
 * screen needs the user's attention — missing perms, no active model,
 * etc. — so users know which tabs they need to visit on first launch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    modelsVm: WhisperModelsViewModel,
    nluModelsVm: NluModelsViewModel,
    nluSettingsVm: NluSettingsViewModel,
    conversationVm: ConversationViewModel,
    appIndexVm: AppIndexViewModel,
    onRequestWatchPermissions: () -> Unit,
    onRequestVoicePermissions: () -> Unit,
) {
    val navController = rememberNavController()
    val perms by vm.permissions.collectAsState()
    val isDefaultVoiceProvider by vm.isDefaultVoiceProvider.collectAsState()
    val models by modelsVm.models.collectAsState()

    // Permissions tab badges if any of the watch perms (Contacts/Phone/SMS),
    // voice perms (Record Audio + battery exemption), or the default-voice-
    // provider setting still need user attention. Settings tab badges if no
    // Whisper model is downloaded/active yet — the only blocking thing in
    // Settings at the moment.
    val permissionsAttention = !(perms.contacts && perms.callPhone && perms.sendSms) ||
        !(perms.recordAudio && perms.ignoringBatteryOptimizations) ||
        !isDefaultVoiceProvider
    val settingsAttention = models.none { it.isDownloaded } || models.none { it.isActive }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.main_screen_title)) }) },
        bottomBar = {
            NavigationBar {
                val currentRoute = navController.currentBackStackEntryAsState()
                    .value?.destination?.route
                Screen.entries.forEach { screen ->
                    val attention = when (screen) {
                        Screen.Conversation -> false
                        Screen.Permissions  -> permissionsAttention
                        Screen.Settings     -> settingsAttention
                    }
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                // Standard bottom-nav navigation options: pop back to the
                                // start destination so the back stack doesn't pile up, and
                                // restore the destination's saved state if we've been there
                                // before.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (attention) {
                                BadgedBox(badge = {
                                    Badge(
                                        modifier = Modifier,
                                        containerColor = MaterialTheme.colorScheme.error,
                                    )
                                }) {
                                    Icon(
                                        screen.icon,
                                        contentDescription = stringResource(R.string.nav_attention_badge_desc),
                                    )
                                }
                            } else {
                                Icon(screen.icon, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(screen.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Start.route,
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            composable(Screen.Conversation.route) {
                Box(padding) {
                    ConversationScreen(vm = conversationVm)
                }
            }
            composable(Screen.Permissions.route) {
                Box(padding) {
                    PermissionsScreen(
                        vm = vm,
                        onRequestWatchPermissions = onRequestWatchPermissions,
                        onRequestVoicePermissions = onRequestVoicePermissions,
                    )
                }
            }
            composable(Screen.Settings.route) {
                Box(padding) {
                    SettingsScreen(
                        modelsVm = modelsVm,
                        nluModelsVm = nluModelsVm,
                        nluSettingsVm = nluSettingsVm,
                        conversationVm = conversationVm,
                        appIndexVm = appIndexVm,
                    )
                }
            }
        }
    }
}

/** Tiny wrapper that applies the Scaffold's content padding to a screen. */
@Composable
private fun Box(
    padding: androidx.compose.foundation.layout.PaddingValues,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
        content()
    }
}

/** A single permission row with label / description / grant-state icon. */
@Composable
internal fun PermissionRow(label: String, description: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (granted) Icons.Default.Check else Icons.Default.Close,
            contentDescription = if (granted) stringResource(R.string.content_desc_granted)
                                 else stringResource(R.string.content_desc_denied),
            tint = if (granted) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.error,
        )
    }
}
