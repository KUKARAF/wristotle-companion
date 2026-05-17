package com.lazydevs.wristotle.nlu.slots

/**
 * Shared cleanup helpers for slot extractors.
 *
 * Whisper transcripts often append emphatic affirmation runs to the end
 * of a query ("call john yes yes yes", "text alice please please please")
 * — either because the user trailed off, the watch silence detector took
 * a moment to fire, or Whisper hallucinated them. None of these tokens
 * are part of a contact name; dragging them into a contacts lookup
 * guarantees a "not found" response.
 *
 * Trigger requires **2 or more** consecutive emphatic words. One trailing
 * "please" can be ambiguous (filler vs. polite contact suffix); a run of
 * two or more is unambiguously trailing noise.
 */
private val TRAILING_EMPHASIS = Regex(
    "(?i)(?:[\\s,.!?]+\\b(yes|no|nope|yeah|ok|okay|sure|please|maybe|right|huh|um|uh|oh|whatever)\\b){2,}[\\s,.!?]*$"
)

/**
 * Returns [text] with any trailing run of emphatic affirmations and
 * residual punctuation removed. Idempotent; safe to apply multiple
 * times. Returns the original string unchanged when nothing matches.
 */
internal fun stripTrailingEmphasis(text: String): String {
    val withoutRun = TRAILING_EMPHASIS.replace(text, "")
    return withoutRun.trim().trimEnd('.', ',', '!', '?', ';', ':').trim()
}

/**
 * Strips leading and trailing punctuation from a candidate contact-name
 * token so contacts lookup matches the on-device "John" rather than
 * tripping over Whisper's "john,".
 */
internal fun cleanNameToken(token: String): String =
    token.trim().trim('.', ',', '!', '?', ';', ':', '"', '\'').trim()
