// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.lazydevs.wristotle.R

/**
 * Settings card that surfaces the project's funding link plus the
 * zero-cost ways users can boost the project (repo stars + appstore
 * "like" votes). Stateless — every row just fires an
 * `Intent.ACTION_VIEW` on a hard-coded URL. No VM, no state hoisting,
 * no string resource for the URLs themselves (they're build-time
 * constants — keeping them in code is simpler than a resource lookup).
 *
 * URL list deliberately mirrors the docs site's Support page so users
 * see the same options whether they're in the app or on the web.
 */
@Composable
fun SupportCard() {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_support_header),
                description = stringResource(R.string.settings_support_desc),
            )

            // Free options first — most users land here without
            // disposable income to spare, and the project's framing
            // is "no pressure to pay." Free actions (star, like, share)
            // matter just as much to discovery + momentum.
            Text(
                stringResource(R.string.settings_support_free_ways),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            TextButton(onClick = { openUrl(context, URL_REPO_COMPANION) }) {
                Text(stringResource(R.string.settings_support_star_companion))
            }
            TextButton(onClick = { openUrl(context, URL_REPO_WATCH) }) {
                Text(stringResource(R.string.settings_support_star_watch))
            }
            TextButton(onClick = { openUrl(context, URL_APPSTORE_REBBLE) }) {
                Text(stringResource(R.string.settings_support_like_rebble))
            }
            TextButton(onClick = { openUrl(context, URL_APPSTORE_REPEBBLE) }) {
                Text(stringResource(R.string.settings_support_like_repebble))
            }
            TextButton(onClick = { openUrl(context, URL_DOCS_SUPPORT) }) {
                Text(stringResource(R.string.settings_support_learn_more))
            }

            // Paid option last, with an explicit "skip this if money's
            // tight" caveat so the framing matches the docs site.
            Text(
                stringResource(R.string.settings_support_bmc_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                stringResource(R.string.settings_support_bmc_caveat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { openUrl(context, URL_BMC) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_support_bmc_button))
            }
            Button(
                onClick = { openUrl(context, URL_LIBERAPAY) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_support_liberapay_button))
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (t: Throwable) {
        Log.w("SupportCard", "no browser to open $url", t)
    }
}

private const val URL_BMC = "https://buymeacoffee.com/lazydevs"
private const val URL_LIBERAPAY = "https://liberapay.com/lazydevs/donate"
private const val URL_REPO_COMPANION = "https://codeberg.org/wristotle/wristotle-companion"
private const val URL_REPO_WATCH = "https://codeberg.org/wristotle/wristotle"
private const val URL_APPSTORE_REBBLE =
    "https://apps.rebble.io/en_US/application/6a0e71faced0bb000943bc90"
private const val URL_APPSTORE_REPEBBLE =
    "https://apps.repebble.com/wristotle_6a0e71faced0bb000943bc90"
private const val URL_DOCS_SUPPORT = "https://wristotle.codeberg.page/support/"