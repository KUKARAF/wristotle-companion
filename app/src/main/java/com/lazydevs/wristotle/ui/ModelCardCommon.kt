package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

/**
 * Shared bits used by both [WhisperModelsCard] and [NluModelsCard] —
 * the two cards intentionally stay separate (different ViewModels +
 * row content), but small visual atoms like the "[Active]" pill and
 * the bytes→MB conversion are identical and worth a single home.
 */

/** Round bytes to the nearest MB for display in model rows. */
internal fun approxSizeMb(bytes: Long): Int = (bytes / 1_000_000L).toInt()

/** Small "[Active]" badge shown on the currently-selected model row. */
@Composable
internal fun ActiveModelPill() {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        contentColor = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(
                stringResource(R.string.whisper_model_status_active),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
