package com.lazydevs.wristotle.setup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Derivation-logic tests for [SetupHealthProvider]. Every input is a
 * lambda by design — no Android dependencies leak through — so each
 * case can name exactly the trigger condition it's exercising.
 *
 * Default fixture = "fresh microPebble install with nothing done."
 * Each test overrides one or two knobs and asserts which actions
 * appear (or don't).
 */
class SetupHealthProviderTest {

    @Test fun `fresh microPebble install emits all four essentials`() = runBlocking {
        val provider = buildProvider()
        provider.refresh()
        val actions = provider.actions.value
        assertEquals(
            setOf(
                ActionId.DownloadWhisperModel,
                ActionId.DownloadNluModel,
                ActionId.GrantPermissions,
                ActionId.ScanInstalledApps,
            ),
            actions.filter { it.priority == Priority.Essential }.map { it.id }.toSet(),
        )
    }

    @Test fun `Core Devices hides the Whisper recommendation`() = runBlocking {
        // whisperInWatchPath = false models the rePebble / Core Devices
        // case where the audio never reaches Wristotle's recognizer.
        val provider = buildProvider(whisperInWatchPath = { false })
        provider.refresh()
        assertNoEssential(provider, ActionId.DownloadWhisperModel)
    }

    @Test fun `unknown companion shows the Whisper recommendation (default-safe)`() = runBlocking {
        // Unknown returns true from whisperAppliesToWatchDictation —
        // we don't pre-hide functionality based on no evidence.
        // Mirrored here by passing { true } directly.
        val provider = buildProvider(whisperInWatchPath = { true })
        provider.refresh()
        assertHasEssential(provider, ActionId.DownloadWhisperModel)
    }

    @Test fun `low-RAM device hides the NLU recommendation`() = runBlocking {
        // The NLU embedder is the heaviest optional thing — nudging it
        // on a memory-constrained phone would just frustrate the user.
        val provider = buildProvider(isLowRamDevice = { true })
        provider.refresh()
        assertNoEssential(provider, ActionId.DownloadNluModel)
    }

    @Test fun `granted permissions drop the GrantPermissions row`() = runBlocking {
        val provider = buildProvider(hasCorePermissions = { true })
        provider.refresh()
        assertNoEssential(provider, ActionId.GrantPermissions)
    }

    @Test fun `previously-scanned app index drops the ScanInstalledApps row`() = runBlocking {
        val provider = buildProvider(latestAppScanAt = { 1_700_000_000_000L })
        provider.refresh()
        assertNoEssential(provider, ActionId.ScanInstalledApps)
    }

    @Test fun `active Whisper + NLU models drop the download rows`() = runBlocking {
        val provider = buildProvider(
            whisperActiveModelId = { "tiny.en-q5_1" },
            nluActiveModelId = { "minilm-l6-v2" },
        )
        provider.refresh()
        assertNoEssential(provider, ActionId.DownloadWhisperModel)
        assertNoEssential(provider, ActionId.DownloadNluModel)
    }

    @Test fun `existing aliases drop the Quality rows`() = runBlocking {
        val provider = buildProvider(
            appAliasCount = { 5 },
            contactAliasCount = { 3 },
        )
        provider.refresh()
        val qualityIds = provider.actions.value
            .filter { it.priority == Priority.Quality }
            .map { it.id }
            .toSet()
        assertFalse(ActionId.AddAppAlias in qualityIds)
        assertFalse(ActionId.AddContactAlias in qualityIds)
    }

    @Test fun `configured speech provider + ask agent drop their Quality rows`() = runBlocking {
        val provider = buildProvider(
            isSpeechProviderConfigured = { true },
            isAskAgentConfigured = { true },
        )
        provider.refresh()
        val qualityIds = provider.actions.value
            .filter { it.priority == Priority.Quality }
            .map { it.id }
            .toSet()
        assertFalse(ActionId.ConfigureSpeechProvider in qualityIds)
        assertFalse(ActionId.SetUpAskAgent in qualityIds)
    }

    @Test fun `power user with everything set has an empty list`() = runBlocking {
        val provider = buildProvider(
            whisperActiveModelId = { "tiny.en-q5_1" },
            nluActiveModelId = { "minilm-l6-v2" },
            latestAppScanAt = { 1_700_000_000_000L },
            hasCorePermissions = { true },
            appAliasCount = { 1 },
            contactAliasCount = { 1 },
            isSpeechProviderConfigured = { true },
            isAskAgentConfigured = { true },
        )
        provider.refresh()
        assertEquals(emptyList<RecommendedAction>(), provider.actions.value)
    }

    @Test fun `essentials sort before quality and are stable within bucket`() = runBlocking {
        // Default fixture has all 4 essentials + 4 quality items. Within
        // each bucket, sort order is ActionId.ordinal — pin both buckets
        // so a future ActionId reorder doesn't silently shuffle the
        // wizard's step sequence.
        val provider = buildProvider()
        provider.refresh()
        val orderedIds = provider.actions.value.map { it.id }
        val expectedEssentials = listOf(
            ActionId.DownloadWhisperModel,
            ActionId.DownloadNluModel,
            ActionId.GrantPermissions,
            ActionId.ScanInstalledApps,
        )
        val expectedQuality = listOf(
            ActionId.AddContactAlias,
            ActionId.AddAppAlias,
            ActionId.ConfigureSpeechProvider,
            ActionId.SetUpAskAgent,
        )
        assertEquals(expectedEssentials + expectedQuality, orderedIds)
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** Unconfined dispatcher means `launch { … }` runs synchronously
     *  on the calling thread, so the StateFlow update from
     *  [SetupHealthProvider.refresh] is visible the next line down. */
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun buildProvider(
        whisperActiveModelId: () -> String? = { null },
        nluActiveModelId: () -> String? = { null },
        latestAppScanAt: suspend () -> Long? = { null },
        whisperInWatchPath: () -> Boolean = { true },
        appAliasCount: () -> Int = { 0 },
        contactAliasCount: () -> Int = { 0 },
        isSpeechProviderConfigured: () -> Boolean = { false },
        isAskAgentConfigured: () -> Boolean = { false },
        hasCorePermissions: () -> Boolean = { false },
        isLowRamDevice: () -> Boolean = { false },
    ) = SetupHealthProvider(
        scope = scope,
        whisperActiveModelId = whisperActiveModelId,
        nluActiveModelId = nluActiveModelId,
        latestAppScanAt = latestAppScanAt,
        whisperInWatchPath = whisperInWatchPath,
        appAliasCount = appAliasCount,
        contactAliasCount = contactAliasCount,
        isSpeechProviderConfigured = isSpeechProviderConfigured,
        isAskAgentConfigured = isAskAgentConfigured,
        hasCorePermissions = hasCorePermissions,
        isLowRamDevice = isLowRamDevice,
    )

    private fun assertHasEssential(provider: SetupHealthProvider, id: ActionId) {
        val ids = provider.actions.value.filter { it.priority == Priority.Essential }.map { it.id }
        assertTrue("expected $id in essentials, got $ids", id in ids)
    }

    private fun assertNoEssential(provider: SetupHealthProvider, id: ActionId) {
        val ids = provider.actions.value.filter { it.priority == Priority.Essential }.map { it.id }
        assertFalse("expected $id NOT in essentials, got $ids", id in ids)
    }
}
