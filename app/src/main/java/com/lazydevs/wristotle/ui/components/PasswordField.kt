package com.lazydevs.wristotle.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.lazydevs.wristotle.R

/**
 * Masked text field with an eye-toggle, used for every secret the user
 * types (backup password, API keys, MCP `Authorization` header). Lives
 * here so a single place owns the "show + mask + toggle" UX — earlier
 * the AskAgent / Weather / MCP cards each rolled their own plain
 * `OutlinedTextField` and one of them (MCP auth header) was shipping
 * the value un-masked.
 *
 * Visibility state is owned internally — callers that need to lift it
 * (e.g. share visibility across multiple linked fields) can hoist via
 * the [PasswordField] overload that takes `visible` + `onToggleVisible`.
 */
@Composable
fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var visible by remember { mutableStateOf(false) }
    PasswordField(
        value = value,
        onChange = onChange,
        label = label,
        visible = visible,
        onToggleVisible = { visible = !visible },
        modifier = modifier,
    )
}

/** Hoisted-visibility variant for callers that own the show/hide state. */
@Composable
fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None
                               else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff
                                  else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.settings_backup_password_hide
                        else R.string.settings_backup_password_show
                    ),
                )
            }
        },
        modifier = modifier,
    )
}
