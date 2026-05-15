package com.lazydevs.wristotle

import android.app.Application
import com.lazydevs.wristotle.transport.PebbleTransport

/**
 * Process-wide setup hook.
 *
 * Owns the single [PebbleTransport] used by both [com.lazydevs.wristotle.service.PebbleListenerService]
 * (inbound) and [com.lazydevs.wristotle.service.WatchMessageService] (outbound startup ping).
 * Without this, each service constructs its own [io.rebble.pebblekit2.client.DefaultPebbleSender]
 * and the two binders can race on shutdown.
 *
 * Phase 2 will swap in a real Whisper backend here, e.g.
 * ```
 * Recognizers.provider = { ctx -> WhisperRecognizer(ctx, modelPath = ...) }
 * ```
 * This is the only place that change needs to land — the :speech module and the
 * RecognitionService class never reference a concrete recognizer.
 */
class WristotleApplication : Application() {

    /** Shared sender — Application-scoped, lifetime matches the process. */
    lateinit var transport: PebbleTransport
        private set

    override fun onCreate() {
        super.onCreate()
        transport = PebbleTransport(this)
    }

    override fun onTerminate() {
        // Rarely called in production (only in emulator/test). Best-effort cleanup.
        transport.close()
        super.onTerminate()
    }
}
