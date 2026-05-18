package com.lazydevs.wristotle.speech.nlu

/**
 * Discrete intents the NLU layer can route to. Each value corresponds to a
 * single [com.lazydevs.wristotle.handlers.ActionHandler] in the app module.
 *
 * Keep this enum stable — values are persisted by name (not ordinal) into
 * the example bank and conversation history, so reordering is safe but
 * renames require a Room migration.
 */
enum class Intent {
    Call,
    Sms,
    Reminder,
    Cancel,
    FindPhone,
    Time,
    Battery,
    Steps,
    Vibrate,

    /** Resume / start playback on the active media app (Spotify, YouTube, …). */
    MediaPlay,
    /** Pause the active media app. */
    MediaPause,
    /** Toggle play / pause — the handler reads current state to decide.
     *  Useful when the classifier is uncertain between play and pause. */
    MediaPlayPause,
    /** Skip to the next track / episode. */
    MediaNext,
    /** Skip to the previous track / episode. */
    MediaPrevious,
    /** Seek forward within the current track. Slot: `seconds: Int` (default 30). */
    MediaSeekForward,
    /** Seek backward within the current track. Slot: `seconds: Int` (default 10). */
    MediaSeekBackward,

    /** Fallback when no other intent matches with sufficient confidence. */
    Unknown,
    ;

    companion object {
        /** Case-insensitive lookup by name; returns [Unknown] if not found. */
        fun fromName(name: String): Intent =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Unknown
    }
}
