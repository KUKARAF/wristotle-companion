// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.help.FeatureEntry
import com.lazydevs.wristotle.help.HelpContent
import com.lazydevs.wristotle.help.Tip

/**
 * Settings → ❓ Help card. Four sections, all fed from the static
 * [HelpContent] tables:
 *
 *  1. **Search** — a single OutlinedTextField that filters tips and
 *     timeline rows by case-insensitive substring match.
 *  2. **Tips** — a handful of action-oriented pointers a user might
 *     not discover by tapping around.
 *  3. **Feature history** — every user-facing release, newest first,
 *     with the version + date, a single-line description, an optional
 *     sample voice query, and an "Open docs ↗" deep-link.
 *  4. **Troubleshooting CTA** — bottom-of-card button to the docs
 *     site's troubleshooting page.
 *
 * When [highlightVersion] is non-null, the matching FeatureEntry rows
 * get a "✨ New" chip + accent border, and the card auto-scrolls them
 * into view on first composition. Used by the first-launch-after-
 * install/update flow ([com.lazydevs.wristotle.help.WhatsNewState] →
 * [com.lazydevs.wristotle.ui.SettingsScreen]) to point the user at
 * what they just got without hiding the rest of the page behind a
 * filter. User-driven re-entries don't replay the highlight — the
 * scroll fires once via `LaunchedEffect(Unit)` and the highlight
 * state is `remember`ed for the composable's lifetime.
 *
 * Stateless apart from the search query (`remember`ed inside the
 * composable — fresh query each time the user re-enters the card).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HelpCard(highlightVersion: String? = null) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }

    val visibleTips = remember(search) { HelpContent.tips.filteredBy(search) }
    val visibleEntries = remember(search) { HelpContent.timeline.filteredBy(search) }
    val empty = visibleTips.isEmpty() && visibleEntries.isEmpty()

    // Captured once — subsequent recompositions / user navigation
    // doesn't re-arm the scroll-into-view behaviour.
    val initialHighlight = remember { highlightVersion }
    val highlightRequester = remember { BringIntoViewRequester() }
    // Which entry to mark + scroll to. Tries an exact version match
    // first; falls back to the newest entry when there's no match.
    // The fallback covers two cases:
    //   1. Debug builds — `BuildConfig.VERSION_NAME` is "1.0" (the
    //      defaultConfig default), never present in features.json.
    //      The release variant overrides versionName from the git
    //      tag so the match works.
    //   2. Production safety net — if a release ever ships without
    //      its features.json entry (the Gradle verifyHelpTimeline
    //      task + the pre-push hook should both reject this, but
    //      belt-and-suspenders), the user still gets pointed at the
    //      most recent known feature instead of a silent no-op.
    val highlightTarget: FeatureEntry? = remember(initialHighlight) {
        if (initialHighlight == null) null
        else HelpContent.timeline.firstOrNull { it.companionVersion == initialHighlight || it.watchVersion == initialHighlight }
            ?: HelpContent.timeline.firstOrNull()
    }
    LaunchedEffect(highlightTarget) {
        if (highlightTarget != null) {
            // Yield once so layout finishes measuring the modifier-
            // attached rows before we request the scroll — otherwise
            // the requester has no bounds to bring into view.
            highlightRequester.bringIntoView()
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_help_header),
                description = stringResource(R.string.settings_help_desc),
            )

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(stringResource(R.string.settings_help_search_label)) },
                placeholder = { Text(stringResource(R.string.settings_help_search_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (empty) {
                Text(
                    stringResource(R.string.settings_help_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (visibleTips.isNotEmpty()) {
                Text(
                    stringResource(R.string.settings_help_tips_header),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                visibleTips.forEach { tip -> TipRow(tip, context) }
            }

            if (visibleEntries.isNotEmpty()) {
                if (visibleTips.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                Text(
                    stringResource(R.string.settings_help_history_header),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.settings_help_history_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                visibleEntries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    val isHighlighted = entry == highlightTarget
                    val rowModifier = if (isHighlighted) {
                        Modifier.bringIntoViewRequester(highlightRequester)
                    } else Modifier
                    FeatureRow(
                        entry = entry,
                        context = context,
                        highlighted = isHighlighted,
                        modifier = rowModifier,
                    )
                }
            }

            // Troubleshooting CTA — always visible regardless of the
            // search filter, since "I'm stuck and need help" is the
            // exact state where the user gives up scrolling.
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.settings_help_troubleshooting_prompt),
                style = MaterialTheme.typography.bodyMedium,
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        openUrl(context, HelpContent.DOCS_BASE_URL + HelpContent.TROUBLESHOOTING_PATH)
                    },
                ) {
                    Text(stringResource(R.string.settings_help_troubleshooting_button))
                }
            }
        }
    }
}

@Composable
private fun TipRow(tip: Tip, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            tip.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            tip.body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        tip.docsPath?.let { path ->
            DocsLink(path, context)
        }
    }
}

@Composable
private fun FeatureRow(
    entry: FeatureEntry,
    context: Context,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Highlighted rows wrap in an outlined Card with a primary border
    // so they stand out without fighting with the surrounding row
    // styling. Non-highlighted rows render as plain columns to keep
    // the page light.
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // "✨ New" chip on highlighted rows — tells the user this
            // is the entry the auto-open landed on.
            if (highlighted) {
                Text(
                    stringResource(R.string.settings_help_new_chip),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // Version + date as the meta line — small + muted so the
            // title gets the visual weight. Paired releases render
            // together (e.g. "v0.14.0 + Watch v0.8.0 · 2026-05-31").
            Text(
                "${formatVersions(entry)}  ·  ${entry.date}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                entry.description,
                style = MaterialTheme.typography.bodySmall,
            )
            entry.sampleQuery?.let { query ->
                Text(
                    "Try: \"$query\"",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            entry.docsPath?.let { path ->
                DocsLink(path, context)
            }
        }
    }

    if (highlighted) {
        Card(
            modifier = modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f),
            ),
        ) {
            Box(modifier = Modifier.padding(12.dp)) { content() }
        }
    } else {
        Box(modifier = modifier) { content() }
    }
}

@Composable
private fun DocsLink(path: String, context: Context) {
    TextButton(
        onClick = { openUrl(context, HelpContent.DOCS_BASE_URL + path) },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
    ) {
        Text(stringResource(R.string.settings_help_open_docs))
    }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (t: Throwable) {
        Log.w("HelpCard", "no browser to open $url", t)
    }
}

/**
 * Render the version meta line for one feature, matching the changelog
 * convention so users see the same shape in-app and on the docs site:
 *  - both sides shipped     → `"v0.14.0 + Watch v0.8.0"`
 *  - companion only         → `"v0.16.0"`
 *  - watch only             → `"Watch v0.6.1"`
 *
 * features.json validates at codegen time that at least one of the
 * two version fields is set, so the "both null" case is unreachable.
 */
private fun formatVersions(entry: FeatureEntry): String {
    val c = entry.companionVersion
    val w = entry.watchVersion
    return when {
        c != null && w != null -> "$c + Watch $w"
        c != null -> c
        w != null -> "Watch $w"
        else -> entry.date   // unreachable per features.json validation
    }
}

/**
 * Case-insensitive substring filter — empty query returns the list
 * unchanged. Matches across every text field that's actually visible
 * to the user so search behaviour matches their mental model.
 */
private fun List<FeatureEntry>.filteredBy(query: String): List<FeatureEntry> {
    if (query.isBlank()) return this
    val needle = query.trim().lowercase()
    return filter { entry ->
        entry.title.lowercase().contains(needle) ||
            entry.description.lowercase().contains(needle) ||
            (entry.companionVersion?.lowercase()?.contains(needle) == true) ||
            (entry.watchVersion?.lowercase()?.contains(needle) == true) ||
            (entry.sampleQuery?.lowercase()?.contains(needle) == true)
    }
}

@JvmName("filteredByTips")
private fun List<Tip>.filteredBy(query: String): List<Tip> {
    if (query.isBlank()) return this
    val needle = query.trim().lowercase()
    return filter { tip ->
        tip.title.lowercase().contains(needle) ||
            tip.body.lowercase().contains(needle)
    }
}