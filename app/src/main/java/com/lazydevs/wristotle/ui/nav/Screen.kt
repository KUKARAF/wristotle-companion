package com.lazydevs.wristotle.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.lazydevs.wristotle.R

/**
 * Top-level destinations for the bottom-navigation shell.
 *
 * Order in [entries] is also the tab order in the bar — `Conversation` first
 * so it's the landing screen and the default tab a returning user sees.
 * Permissions consolidates the former Watch + Voice tabs; Settings owns the
 * Whisper model catalog and conversation-history maintenance.
 *
 * Each screen carries a [tint] that the bottom-navigation bar uses as the
 * icon's selected color (and a low-alpha tint of the same hue as the
 * selection-indicator background). Picked to be distinct from each other
 * while still reading at small sizes:
 *   - Conversation: cool blue — communication
 *   - Notes:        warm amber — paper / writing
 *   - Tasks:        teal — checklist / "do"
 *   - Permissions:  green — safety / "go"
 *   - Settings:     brand purple — matches the docs-site primary
 */
enum class Screen(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val tint: Color,
) {
    Conversation("conversation", R.string.nav_conversation, Icons.AutoMirrored.Filled.Chat, Color(0xFF1976D2)),
    Notes("notes", R.string.nav_notes, Icons.Default.Description, Color(0xFFF59E0B)),
    Tasks("tasks", R.string.nav_tasks, Icons.Default.CheckCircleOutline, Color(0xFF14B8A6)),
    Permissions("permissions", R.string.nav_permissions, Icons.Default.Shield, Color(0xFF10B981)),
    Settings("settings", R.string.nav_settings, Icons.Default.Settings, Color(0xFF7C3AED)),
    ;

    companion object {
        /** The default landing tab. */
        val Start: Screen = Conversation
    }
}
