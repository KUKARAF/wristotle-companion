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

    /** Fallback when no other intent matches with sufficient confidence. */
    Unknown,
    ;

    companion object {
        /** Case-insensitive lookup by name; returns [Unknown] if not found. */
        fun fromName(name: String): Intent =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Unknown
    }
}
