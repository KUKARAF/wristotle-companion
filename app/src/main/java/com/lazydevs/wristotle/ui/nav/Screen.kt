package com.lazydevs.wristotle.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.ui.graphics.vector.ImageVector
import com.lazydevs.wristotle.R

/**
 * Top-level destinations for the bottom-navigation shell.
 *
 * Order in [entries] is also the tab order in the bar — `Conversation` first
 * so it's the landing screen and the default tab a returning user sees.
 * Permissions consolidates the former Watch + Voice tabs; Settings owns the
 * Whisper model catalog and conversation-history maintenance.
 */
enum class Screen(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Conversation("conversation", R.string.nav_conversation, Icons.AutoMirrored.Filled.Chat),
    Notes("notes", R.string.nav_notes, Icons.Default.Description),
    Permissions("permissions", R.string.nav_permissions, Icons.Default.Shield),
    Settings("settings", R.string.nav_settings, Icons.Default.Settings),
    ;

    companion object {
        /** The default landing tab. */
        val Start: Screen = Conversation
    }
}
