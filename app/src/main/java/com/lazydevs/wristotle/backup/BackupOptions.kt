package com.lazydevs.wristotle.backup

/**
 * The two dials a user turns when running an export or restore:
 *
 *  - `selection` — which categories ride along, see [BackupSelection].
 *  - `password` — null/blank → plaintext ZIP; non-blank → AES-256.
 *
 * Bundled so callers (BackupViewModel, BackupExporter, BackupImporter)
 * share one "options" shape instead of carrying the two fields as
 * parallel state. Future dials (compression level, schema pin, etc.)
 * land here without re-threading every signature.
 */
data class BackupOptions(
    val selection: BackupSelection = BackupSelection(),
    val password: String? = null,
)
