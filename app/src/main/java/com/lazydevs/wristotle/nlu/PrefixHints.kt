package com.lazydevs.wristotle.nlu

import com.lazydevs.wristotle.speech.nlu.Intent

/**
 * Cheap, deterministic intent hint from a query's opening token.
 *
 * Consulted by `PebbleListenerService.resolveIntent` whenever the
 * embedding classifier comes back EITHER below the confidence floor
 * OR above it but within margin of the runner-up. In both cases, if
 * the user opened with an unambiguous verb ("text", "call", "play",
 * etc.), trust the verb over the uncertain classifier output.
 *
 * Not a primary router — the embedding classifier is the main signal;
 * this just rescues the cases where the embedder is uncertain.
 *
 * Order matters: rules are tried top-down and the first match wins.
 * Put more-specific patterns above more-general ones (e.g. the seek
 * variants before the bare `previous/back` rule that would otherwise
 * swallow "back ten seconds").
 */
internal object PrefixHints {

    private val HINTS: List<Pair<Regex, Intent>> = listOf(
        // Media — seek variants first so "skip ahead 30 seconds" doesn't
        // get caught by the bare "skip" → MediaNext rule below.
        Regex("(?i)^\\s*(skip (ahead|forward)|fast ?forward|jump (ahead|forward)|forward (\\d+|a |one |two |three |five |ten |fifteen |twenty |thirty ))") to Intent.MediaSeekForward,
        Regex("(?i)^\\s*(rewind|skip back(ward)?|go back (\\d+|a |one |two |three |five |ten |fifteen |twenty |thirty )|back (\\d+|one |two |three |five |ten |fifteen |twenty |thirty ))") to Intent.MediaSeekBackward,
        // Watch dictation: "pause" is sometimes heard from short audio
        // before "play" because Whisper biases toward the short word; we
        // still want to honour what was actually said, so pause is its
        // own pattern (not part of the play rule).
        Regex("(?i)^\\s*(pause|halt|stop) (music|playback|the music|the song|playing)?\\s*$") to Intent.MediaPause,
        Regex("(?i)^\\s*pause\\b") to Intent.MediaPause,
        Regex("(?i)^\\s*(play|resume|start|continue)\\s+(music|playback|the music|the song|playing)") to Intent.MediaPlay,
        Regex("(?i)^\\s*(play|resume)\\s*$") to Intent.MediaPlay,
        Regex("(?i)^\\s*(next|skip)\\s*(song|track|episode|this)?\\s*$") to Intent.MediaNext,
        Regex("(?i)^\\s*(previous|last)\\s+(song|track|episode)") to Intent.MediaPrevious,
        Regex("(?i)^\\s*previous\\s*$") to Intent.MediaPrevious,
        // Existing intents
        Regex("(?i)^\\s*(text|sms|message|send (a |an )?(text|message|sms))\\b") to Intent.Sms,
        Regex("(?i)^\\s*(call|dial|phone|ring)\\b") to Intent.Call,
        Regex("(?i)^\\s*(remind|set (a )?reminder|reminder)\\b") to Intent.Reminder,
        Regex("(?i)^\\s*(cancel|delete|remove|clear)\\b") to Intent.Cancel,
        Regex("(?i)^\\s*(find|locate|where('?s| is)) (my )?phone\\b") to Intent.FindPhone,
    )

    /**
     * Returns the intent suggested by the opening of [query], or null
     * when no rule fires.
     */
    fun hintFor(query: String): Intent? =
        HINTS.firstOrNull { (re, _) -> re.containsMatchIn(query) }?.second
}
