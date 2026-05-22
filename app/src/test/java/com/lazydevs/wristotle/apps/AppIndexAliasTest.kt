package com.lazydevs.wristotle.apps

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AppIndexAliasTest {

    private fun app(pkg: String, label: String): InstalledApp = InstalledApp(
        packageId = pkg,
        label = label,
        normalizedLabel = normalizeForIndex(label),
        normalizedPackage = normalizeForPackageId(pkg),
        lastScannedAtMs = 0L,
    )

    private fun index(aliases: Map<String, String>, vararg installed: InstalledApp): AppIndex =
        AppIndex(FakeInstalledAppDao(installed.toList()), aliasResolver = { aliases[it] })

    private fun lookup(idx: AppIndex, q: String): AppLookup = runBlocking { idx.lookup(q) }

    @Test fun `alias beats a wrong fuzzy match`() {
        // Whisper hears "adobe"; user pinned it to Audible. Without the alias
        // this would NotFound (or mis-match Adobe if installed).
        val idx = index(
            aliases = mapOf("adobe" to "com.audible.application"),
            app("com.audible.application", "Audible"),
            app("com.adobe.reader", "Adobe Acrobat"),
        )
        assertEquals(AppLookup.Match("com.audible.application"), lookup(idx, "adobe"))
    }

    @Test fun `alias beats the generic-noun denylist`() {
        // "music" is normally Generic (fall through to active session); an
        // explicit alias overrides that.
        val idx = index(
            aliases = mapOf("music" to "com.spotify.music"),
            app("com.spotify.music", "Spotify"),
        )
        assertEquals(AppLookup.Match("com.spotify.music"), lookup(idx, "music"))
    }

    @Test fun `alias matches after leading-article stripping`() {
        val idx = index(
            aliases = mapOf("podcasts" to "au.com.shiftyjelly.pocketcasts"),
            app("au.com.shiftyjelly.pocketcasts", "Pocket Casts"),
        )
        assertEquals(
            AppLookup.Match("au.com.shiftyjelly.pocketcasts"),
            lookup(idx, "the podcasts"),
        )
    }

    @Test fun `dangling alias (uninstalled target) falls through to fuzzy`() {
        // Alias points at an app that's no longer installed → ignore the alias
        // and let the normal matcher run (here it finds the real Spotify).
        val idx = index(
            aliases = mapOf("spotify" to "com.gone.uninstalled"),
            app("com.spotify.music", "Spotify"),
        )
        assertEquals(AppLookup.Match("com.spotify.music"), lookup(idx, "spotify"))
    }

    @Test fun `no alias behaves like before`() {
        val idx = index(aliases = emptyMap(), app("com.spotify.music", "Spotify"))
        assertEquals(AppLookup.Match("com.spotify.music"), lookup(idx, "spotify"))
        assertEquals(AppLookup.Generic, lookup(idx, "music"))
    }
}
