// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.apps.AliasStore
import com.lazydevs.wristotle.apps.AppIndex
import com.lazydevs.wristotle.apps.InstalledApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One alias for the list UI: the spoken phrase + the app it points at. */
data class AliasRow(
    val phrase: String,
    val packageId: String,
    val appLabel: String,
    val installed: Boolean,
)

/**
 * State for the "App aliases" Settings card. Lists current aliases (resolving
 * each target package to its label) and exposes the installed-app list for the
 * add-form picker. Add/remove write through [AliasStore] and re-read.
 */
class AppAliasesViewModel(app: Application) : AndroidViewModel(app) {

    private val store: AliasStore = (app as WristotleApplication).aliasStore
    private val appIndex: AppIndex = (app as WristotleApplication).appIndex

    private val _aliases = MutableStateFlow<List<AliasRow>>(emptyList())
    val aliases: StateFlow<List<AliasRow>> = _aliases

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            // Prune aliases whose target is no longer installed using a live
            // PackageManager check — so an uninstall takes effect the next time
            // this screen loads (onResume), without waiting for a manual app
            // rescan. The index-based prune in AppIndexer only fires on rescan
            // and reads a possibly-stale index, which left dangling aliases.
            val installedPackages = store.all().values.filter(::isInstalled).toSet()
            store.retainInstalled(installedPackages)

            val apps = appIndex.installedApps()
            _installedApps.value = apps
            val byPkg = apps.associateBy { it.packageId }
            _aliases.value = store.all().entries
                .sortedBy { it.key }
                .map { (phrase, pkg) ->
                    AliasRow(
                        phrase = phrase,
                        packageId = pkg,
                        appLabel = byPkg[pkg]?.label ?: pkg,
                        installed = isInstalled(pkg),
                    )
                }
        }
    }

    private fun isInstalled(packageId: String): Boolean = try {
        getApplication<Application>().packageManager.getApplicationInfo(packageId, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun add(phrase: String, packageId: String) {
        store.put(phrase, packageId)
        refresh()
    }

    fun remove(phrase: String) {
        store.remove(phrase)
        refresh()
    }
}