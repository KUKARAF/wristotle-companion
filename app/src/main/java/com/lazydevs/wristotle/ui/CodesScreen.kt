// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lazydevs.wristotle.codes.ScannedCodeFormats
import com.lazydevs.wristotle.speech.nlu.codes.CodeFormat
import com.lazydevs.wristotle.speech.nlu.codes.CodeGenerator
import com.lazydevs.wristotle.speech.nlu.codes.SavedCode

/**
 * Manage saved codes: scan a card/QR (ZXing live scanner — no Play Services),
 * name it, and it syncs to the watch for offline display. The watch is the
 * primary surface; this screen is for capture + management.
 */
@Composable
fun CodesScreen(vm: CodesViewModel) {
    val codes by vm.codes.collectAsState()
    var pendingScan by remember { mutableStateOf<Pair<CodeFormat, String>?>(null) }
    var showManual by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val data = result.contents
        val format = ScannedCodeFormats.fromZxing(result.formatName)
        if (data != null && format != null) pendingScan = format to data
    }

    // Plain Column (NOT fillMaxSize / LazyColumn): this screen renders inside
    // the Settings drill-down's own verticalScroll Column, so a nested scrollable
    // container would be measured with infinite height and crash.
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Saved codes", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Scan a loyalty card or QR code to keep it on your watch — handy when " +
                "your phone's in your pocket at the checkout.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                scanLauncher.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScannedCodeFormats.DESIRED)
                        // Force portrait via the pinned capture activity —
                        // setOrientationLocked alone can't stop the library's
                        // landscape default.
                        setCaptureActivity(com.lazydevs.wristotle.codes.PortraitCaptureActivity::class.java)
                        setOrientationLocked(true)
                        setBeepEnabled(false)
                        setPrompt("Point at a barcode or QR code")
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan a code")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Edit, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Enter manually")
        }
        Spacer(Modifier.height(16.dp))
        if (codes.isEmpty()) {
            Text(
                "No saved codes yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            codes.forEach { code ->
                CodeRow(code, onDelete = { vm.delete(code.id) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    pendingScan?.let { (format, data) ->
        LabelDialog(
            suggested = data.take(24),
            onConfirm = { label ->
                vm.add(label, format, data)
                pendingScan = null
            },
            onDismiss = { pendingScan = null },
        )
    }

    if (showManual) {
        ManualEntryDialog(
            onConfirm = { label, format, data ->
                vm.add(label, format, data)
                showManual = false
            },
            onDismiss = { showManual = false },
        )
    }
}

@Composable
private fun ManualEntryDialog(
    onConfirm: (label: String, format: CodeFormat, data: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var data by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(CodeFormat.CODE_128) }
    // Validate by actually trying to encode — null = unencodable in this format
    // (e.g. non-ASCII for Code 128, or too long for QR).
    val valid = remember(data, format) {
        data.isNotBlank() && CodeGenerator.matrix(SavedCode("", "", format, data, 0L)) != null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a code") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = data,
                    onValueChange = { data = it },
                    label = { Text("Card number, text, or URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = format == CodeFormat.CODE_128,
                        onClick = { format = CodeFormat.CODE_128 },
                        label = { Text("Barcode") },
                    )
                    FilterChip(
                        selected = format == CodeFormat.QR_CODE,
                        onClick = { format = CodeFormat.QR_CODE },
                        label = { Text("QR code") },
                    )
                }
                if (data.isNotBlank() && !valid) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "That can't be encoded as a ${format.displayName}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label, format, data) }, enabled = valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CodeRow(code: SavedCode, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CodePreview(code, Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    code.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    code.format.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${code.label}")
            }
        }
    }
}

@Composable
private fun LabelDialog(suggested: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(suggested) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this code") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Label") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
