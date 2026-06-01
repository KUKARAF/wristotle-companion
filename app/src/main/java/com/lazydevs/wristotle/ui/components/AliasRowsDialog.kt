package com.lazydevs.wristotle.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Two-line list-with-delete dialog. Shared between the App Aliases and
 * Contact Aliases cards — both render the same shape: scrollable column
 * of (primary text + secondary line + trash icon) rows under a "Done"
 * confirm button.
 *
 * Generic on the row type so each caller passes its own data class +
 * primary/secondary formatters.
 */
@Composable
fun <T> AliasRowsDialog(
    title: String,
    rows: List<T>,
    primaryOf: (T) -> String,
    secondaryOf: (T) -> SecondaryLine,
    deleteContentDescription: String,
    doneLabel: String,
    onDelete: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(doneLabel) }
        },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                rows.forEach { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("“${primaryOf(row)}”", style = MaterialTheme.typography.bodyMedium)
                            val secondary = secondaryOf(row)
                            Text(
                                secondary.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (secondary.warn) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(row) }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = deleteContentDescription,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Sub-line of a [AliasRowsDialog] row. [warn] flips the colour to
 *  surface that the alias is broken (app not installed / contact gone). */
data class SecondaryLine(val text: String, val warn: Boolean = false)
