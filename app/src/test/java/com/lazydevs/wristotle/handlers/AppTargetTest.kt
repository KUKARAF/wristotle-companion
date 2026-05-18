package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.apps.AppIndex
import com.lazydevs.wristotle.apps.FakeInstalledAppDao
import com.lazydevs.wristotle.apps.InstalledApp
import com.lazydevs.wristotle.apps.normalizeForIndex
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTargetTest {

    private fun intent(slots: Map<String, Any> = emptyMap(), intent: Intent = Intent.MediaPlay): IntentResult =
        IntentResult(
            intent = intent,
            slots = slots,
            confidence = 1f,
            alternates = emptyList(),
            rawQuery = "",
        )

    private fun index(): AppIndex = AppIndex(FakeInstalledAppDao(listOf(
        InstalledApp(
            packageId = "com.spotify.music",
            label = "Spotify",
            normalizedLabel = normalizeForIndex("Spotify"),
            lastScannedAtMs = 0L,
        ),
    )))

    private fun resolve(result: IntentResult): AppTarget =
        runBlocking { result.resolveAppTarget(index()) }

    @Test fun `missing app slot resolves to Fallback`() {
        assertEquals(AppTarget.Fallback, resolve(intent()))
    }

    @Test fun `blank app slot resolves to Fallback`() {
        assertEquals(AppTarget.Fallback, resolve(intent(slots = mapOf("app" to "   "))))
    }

    @Test fun `app slot matching an installed app resolves to Specific`() {
        val r = resolve(intent(slots = mapOf("app" to "spotify"))) as AppTarget.Specific
        assertEquals("com.spotify.music", r.packageId)
    }

    @Test fun `app slot that is a generic noun resolves to Fallback`() {
        // "music" is a generic noun in AppIndex.GENERIC_NOUNS → fall
        // through to the active session.
        assertEquals(AppTarget.Fallback, resolve(intent(slots = mapOf("app" to "music"))))
    }

    @Test fun `app slot that is unknown resolves to NotFound carrying the spoken name`() {
        val r = resolve(intent(slots = mapOf("app" to "absolpt"))) as AppTarget.NotFound
        assertEquals("absolpt", r.spoken)
    }

    @Test fun `non-string slot value resolves to Fallback`() {
        // Defensive: cast failure on the slot map shouldn't crash —
        // treat as no slot, fall through to active-session behaviour.
        assertTrue(resolve(intent(slots = mapOf("app" to 42))) is AppTarget.Fallback)
    }
}
