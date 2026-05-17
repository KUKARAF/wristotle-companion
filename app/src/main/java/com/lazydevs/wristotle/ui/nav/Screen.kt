package com.lazydevs.wristotle.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Watch
import androidx.compose.ui.graphics.vector.ImageVector
import com.lazydevs.wristotle.R

/**
 * Top-level destinations for the bottom-navigation shell.
 *
 * Order in [entries] is also the tab order in the bar — `Conversation` first
 * so it's the landing screen and the default tab a returning user sees.
 */
enum class Screen(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Conversation("conversation", R.string.nav_conversation, Icons.AutoMirrored.Filled.Chat),
    Watch("watch", R.string.nav_watch, Icons.Default.Watch),
    Voice("voice", R.string.nav_voice, Icons.Default.Mic),
    Models("models", R.string.nav_models, Icons.Default.Download),
    ;

    companion object {
        /** The default landing tab. */
        val Start: Screen = Conversation
    }
}
