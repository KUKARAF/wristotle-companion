package com.lazydevs.wristotle.transport

import android.content.Context
import android.content.SharedPreferences
import com.lazydevs.wristotle.logging.WristotleLog as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "PebbleCompanionDetector"
private const val PREFS_NAME = "wristotle-companion-detector"
private const val KEY_LAST_BINDER_PACKAGE = "last_binder_package"

/**
 * Which BLE companion is in front of Wristotle — used by the UI to
 * hide / downrank Whisper-related settings when the answer is
 * [Companion.RePebble], whose dictation pipeline goes Watch →
 * rePebble → Rebble cloud and never touches Android's
 * `SpeechRecognizer`. See `apps-and-media.md` for the empirical
 * investigation.
 *
 * Detection is hybrid: scan installed packages on construction
 * (catches the common single-companion-installed case immediately,
 * without waiting for a bind), then refine via [recordBinderUid]
 * the first time the BLE companion binds our `PebbleListenerService`
 * — the active-binder signal is authoritative when both apps are
 * installed and only one is actually paired with the watch.
 *
 * The last observed binder package id is persisted in a small
 * SharedPreferences so the UI state is correct on cold start before
 * the first bind arrives.
 */
class PebbleCompanionDetector(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Called from `PebbleListenerService.onBind` with the UID of the
     * binding caller. Maps the UID to a package name, classifies, and
     * persists. Idempotent — repeated calls with the same UID no-op.
     */
    fun recordBinderUid(uid: Int) {
        val pkg = packageFromUid(uid) ?: run {
            Log.d(TAG, "no package found for binder uid=$uid; ignoring")
            return
        }
        recordBinderPackage(pkg)
    }

    /**
     * Test-friendly path: skip the UID → package lookup. Production
     * callers should prefer [recordBinderUid].
     */
    internal fun recordBinderPackage(packageId: String) {
        val current = prefs.getString(KEY_LAST_BINDER_PACKAGE, null)
        if (current == packageId) return
        Log.d(TAG, "binder package updated: '$current' → '$packageId'")
        prefs.edit().putString(KEY_LAST_BINDER_PACKAGE, packageId).apply()
        _state.value = stateFor(packageId, installedNow())
    }

    private fun initialState(): State {
        val installed = installedNow()
        val lastBinder = prefs.getString(KEY_LAST_BINDER_PACKAGE, null)
        return if (lastBinder != null) {
            stateFor(lastBinder, installed)
        } else {
            // No bind observed yet — use installed-package presence.
            stateFromInstalled(installed)
        }
    }

    private fun stateFor(binderPackage: String, installed: InstalledPackages): State {
        val activeCompanion = classify(binderPackage)
        return State(
            active = activeCompanion,
            installed = installed,
        )
    }

    private fun stateFromInstalled(installed: InstalledPackages): State {
        // No active-binder signal yet. Inference: if exactly one
        // companion is installed, assume it's the one in use. Otherwise
        // mark as Unknown so the UI defaults to its safest behaviour
        // (show everything; don't pre-hide based on guesses).
        val active = when {
            installed.repebble && !installed.micropebble -> Companion.RePebble
            installed.micropebble && !installed.repebble -> Companion.MicroPebble
            else -> Companion.Unknown
        }
        return State(active = active, installed = installed)
    }

    private fun installedNow(): InstalledPackages {
        val pm = context.packageManager
        val packages = try {
            pm.getInstalledPackages(0).mapNotNull { it.packageName }
        } catch (t: Throwable) {
            Log.w(TAG, "getInstalledPackages failed", t)
            return InstalledPackages(repebble = false, micropebble = false)
        }
        return InstalledPackages(
            repebble = packages.any { matchesRePebble(it) },
            micropebble = packages.any { matchesMicroPebble(it) },
        )
    }

    private fun packageFromUid(uid: Int): String? {
        val packages = try {
            context.packageManager.getPackagesForUid(uid)
        } catch (t: Throwable) {
            Log.w(TAG, "getPackagesForUid($uid) failed", t)
            return null
        }
        // First package belonging to the UID that isn't us. (UID can
        // map to multiple packages when an app declares a shared user
        // id — rare for Pebble companions but defensive.)
        return packages?.firstOrNull { it != context.packageName }
            ?: packages?.firstOrNull()
    }

    private fun classify(packageId: String): Companion = when {
        matchesRePebble(packageId) -> Companion.RePebble
        matchesMicroPebble(packageId) -> Companion.MicroPebble
        else -> Companion.Unknown
    }

    /**
     * Permissive substring match for the rePebble family. Real-world
     * package ids include `io.rebble.cobble` (Cobble), `com.rebble.*`
     * for other builds, etc. The `cobble` token catches the original
     * Flutter-based rePebble; `rebble` catches the broader family.
     */
    private fun matchesRePebble(packageId: String): Boolean {
        val lower = packageId.lowercase()
        // Exclude Wristotle's own package + our IPC namespace.
        if (lower == context.packageName.lowercase()) return false
        if (lower.startsWith("io.rebble.pebblekit2")) return false
        return "rebble" in lower || "cobble" in lower
    }

    /**
     * microPebble lives under matejdro's namespace — `si.matejdro.micropebble`
     * is the published applicationId. The `micropebble` substring is
     * the reliable token.
     */
    private fun matchesMicroPebble(packageId: String): Boolean {
        val lower = packageId.lowercase()
        return "micropebble" in lower
    }

    /**
     * High-level state for the UI: what's *currently* in use vs. what's
     * available. The UI cares mostly about [active]; [installed] is
     * surfaced for "switch to microPebble" hints and similar nudges.
     */
    data class State(
        val active: Companion,
        val installed: InstalledPackages,
    ) {
        /**
         * True when watch dictation flows through a path where
         * Wristotle's on-device Whisper *can* be used (microPebble
         * routes through Android's `SpeechRecognizer`). False when
         * rePebble is in front (its cloud pipeline bypasses
         * `SpeechRecognizer` entirely). Unknown defaults to true
         * so we don't pre-hide functionality based on no evidence.
         */
        val whisperAppliesToWatchDictation: Boolean
            get() = active != Companion.RePebble
    }

    data class InstalledPackages(
        val repebble: Boolean,
        val micropebble: Boolean,
    )

    enum class Companion { RePebble, MicroPebble, Unknown }
}
