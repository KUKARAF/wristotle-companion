package com.lazydevs.wristotle.nlu

import com.lazydevs.wristotle.speech.nlu.Intent

/**
 * Cheap, deterministic intent hint from a query's opening token.
 *
 * Used **only as a tie-breaker** when the embedding classifier returns
 * a top pick that's confident enough on its own (>= [NluSettings.ROUTE_THRESHOLD])
 * but within margin of a runner-up. In that ambiguous zone, if the
 * user opened with an unambiguous verb ("text", "call", "remind",
 * "cancel"), trust the verb over the classifier coin-flip.
 *
 * Not used as a primary router — the embedding classifier is the
 * primary signal; this just disambiguates close calls. Below-threshold
 * predictions still route to [Intent.Unknown].
 */
internal object PrefixHints {

    private val HINTS: List<Pair<Regex, Intent>> = listOf(
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
