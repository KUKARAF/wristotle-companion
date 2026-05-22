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
        // Media — seek variants FIRST so "skip ahead 30 seconds" /
        // "back ten seconds" / "rewind" route to seek before falling
        // into the broader next / previous rules below.
        Regex("(?i)^\\s*(skip (ahead|forward)|fast ?forward|jump (ahead|forward)|forward (\\d+|a |one |two |three |five |ten |fifteen |twenty |thirty ))") to Intent.MediaSeekForward,
        Regex("(?i)^\\s*(rewind|skip back(ward)?|go back (\\d+|a |one |two |three |five |ten |fifteen |twenty |thirty )|back (\\d+|one |two |three |five |ten |fifteen |twenty |thirty ))") to Intent.MediaSeekBackward,
        // Pause / halt — anything starting with these is unambiguously
        // a media pause command in our intent set.
        Regex("(?i)^\\s*(pause|halt)\\b") to Intent.MediaPause,
        // Play / resume / continue — match the verb alone regardless of
        // what follows. "play youtube" / "play that podcast" / "play the
        // song" all route to MediaPlay; the handler then triggers
        // whichever app currently has the active media session (or
        // falls back to the system MEDIA_PLAY key event so a paused-
        // but-loaded app resumes). Body word is irrelevant — none of
        // our other intents start with "play" so there's no conflict.
        Regex("(?i)^\\s*(play|resume|continue)\\b") to Intent.MediaPlay,
        // Next / skip without a seek modifier (the modifier-bearing
        // forms already matched above).
        Regex("(?i)^\\s*(next|skip)\\b") to Intent.MediaNext,
        // Previous / last — broad match. Backward seek already matched
        // above so "back ten seconds" doesn't land here.
        Regex("(?i)^\\s*(previous|last)\\b") to Intent.MediaPrevious,
        // OpenApp — sits BELOW Media* so "play youtube" stays MediaPlay
        // (the play verb keeps its meaning). "open" / "launch" / "start"
        // / "fire up" / "switch to" / "go to" are the open-app verbs.
        Regex("(?i)^\\s*(open|launch|fire up|bring up|switch to|go to)\\b") to Intent.OpenApp,
        // "start" is overloaded — it can mean start-app or start-music,
        // but if the body word is one of the generic media nouns the
        // MediaPlay rule above already won't have matched (it requires
        // the verb to be the strict play/resume/continue set), and
        // "start" alone is too vague to dispatch. Only fire OpenApp when
        // there's body content after start/run/load.
        Regex("(?i)^\\s*(start|run|load)\\s+\\S") to Intent.OpenApp,
        // Existing non-media intents
        Regex("(?i)^\\s*(text|sms|message|send (a |an )?(text|message|sms))\\b") to Intent.Sms,
        Regex("(?i)^\\s*(call|dial|phone|ring)\\b") to Intent.Call,
        // CreateEvent — "schedule/set up/create/add/book … meeting/event/…".
        // Requires an event noun, so "schedule a reminder" does NOT match here
        // (it has no event noun) and stays with the reminder path. Rescues the
        // classifier, which scores "schedule a meeting with X at Y for Z" as
        // ambiguous (event vs reminder) and otherwise drops it to Unknown.
        Regex("(?i)^\\s*(schedule|set up|create|add|book|put)\\b.*\\b(meeting|appointment|event|call)\\b") to Intent.CreateEvent,
        // ListReminders — a leading interrogative / "list" / "show" together
        // with a reminder noun is a QUERY ("is there a reminder at 2pm", "do I
        // have a reminder at 5"), NOT a create. MUST sit above the Reminder
        // rule so these don't fall through to creating a junk reminder. The
        // embedding can't reliably separate "is there a reminder at X" from
        // "remind me at X" — the time dominates the cosine — so this is the
        // deterministic guard. Matches the common "remainder" mishearing too.
        Regex("(?i)^\\s*(what|which|when|is|are|was|were|do|does|did|have|has|had|any|list|show|read|tell)\\b.*\\b(reminders?|remainders?)\\b") to Intent.ListReminders,
        // Reschedule — "snooze / push / move / reschedule … <reminder> to <time>".
        // The opening verb is the reliable signal; the embedding tends to read
        // "push my reminder to X" as a CREATE (it contains "reminder to …"), so
        // this deterministic rule routes it. In the reminder family, so the
        // watch's blanket Reminder hint can be refined to Reschedule. Sits above
        // the Reminder rule (which only matches a remind/reminder opener anyway).
        Regex("(?i)^\\s*(snooze|reschedule|postpone|delay|push|move|bump|shift)\\b") to Intent.Reschedule,
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
