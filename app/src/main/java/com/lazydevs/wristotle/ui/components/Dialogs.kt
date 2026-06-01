package com.lazydevs.wristotle.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

/**
 * Single-button "got it" / acknowledgement dialog. Used for export-
 * success, failure surfaces, and the plaintext-secrets warning preview.
 */
@Composable
fun InfoDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.dialog_ok),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(confirmLabel) }
        },
    )
}

/**
 * Two-button confirm dialog. The body composable slot lets callers add
 * extra context (e.g. a count of items being deleted) without forcing
 * every callsite into a plain [Text].
 *
 * Used for destructive actions (clear learned phrases, delete all
 * notes / tasks, clear conversation audio) that the user should
 * acknowledge before they run.
 */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.dialog_cancel),
    body: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier) {
                body()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

/** Convenience overload — body is just a single line of text. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.dialog_cancel),
) {
    ConfirmDialog(
        title = title,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        dismissLabel = dismissLabel,
        body = { Text(message, style = MaterialTheme.typography.bodyMedium) },
    )
}
