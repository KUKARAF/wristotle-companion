package com.lazydevs.wristotle.handlers

import java.util.TimeZone

/**
 * Maps a spoken place name ("tokyo", "new york", "japan", "uk") to a
 * [TimeZone]. Pure `java.util` — no Android, no network, no `java.time`
 * (the app's minSdk is 24 without core-library desugaring), so it works on
 * every supported device and unit-tests directly on the host JVM.
 *
 * Resolution order:
 *  1. [ALIASES] — curated spoken forms the raw zone table can't satisfy:
 *     country names ("japan" → Asia/Tokyo), abbreviations ("nyc", "la",
 *     "uk"), and common nicknames. Checked first so "uk" beats any literal
 *     zone whose city happens to be "uk".
 *  2. [cityMap] — derived once from [TimeZone.getAvailableIDs]: the last
 *     path segment of every "Region/City" id, underscores → spaces,
 *     lowercased ("Asia/Tokyo" → "tokyo", "America/New_York" → "new york").
 *     This covers hundreds of cities for free.
 *
 * Returns null when nothing matches; the handler turns that into a
 * user-facing "couldn't find that time zone".
 */
object TimeZoneResolver {

    fun resolve(spoken: String): TimeZone? {
        val key = normalize(spoken)
        if (key.isEmpty()) return null
        val id = ALIASES[key] ?: cityMap[key] ?: return null
        return TimeZone.getTimeZone(id)
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(NON_ALNUM, " ")
            .replace(MULTI_SPACE, " ")
            .trim()

    /** Lowercased city name → Olson id, built from the running JVM's tz table. */
    private val cityMap: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (id in TimeZone.getAvailableIDs()) {
            val firstSlash = id.indexOf('/')
            if (firstSlash < 0) continue
            val region = id.substring(0, firstSlash)
            if (region in EXCLUDED_REGIONS) continue
            val city = id.substringAfterLast('/').replace('_', ' ').lowercase()
            // First writer wins so a later duplicate city name (rare across
            // regions) doesn't clobber the canonical continent zone.
            map.putIfAbsent(city, id)
        }
        map
    }

    // Legacy / compatibility groupings whose leaf segments ("eastern",
    // "general", "east") aren't things a user would say for a time query —
    // skip them so the city map stays clean. The canonical "America/*",
    // "Asia/*" … zones still provide every real city.
    private val EXCLUDED_REGIONS = setOf(
        "Etc", "SystemV", "US", "Canada", "Brazil", "Chile", "Mexico", "Argentina",
    )

    private val NON_ALNUM = Regex("[^a-z0-9 ]")
    private val MULTI_SPACE = Regex("\\s+")

    // Curated spoken forms the raw zone table can't resolve on its own.
    // Many big cities (tokyo, london, paris, sydney …) already resolve via
    // cityMap; these add country names, abbreviations, and multi-word leaf
    // names so the common "what time is it in <country>" phrasing works.
    private val ALIASES: Map<String, String> = mapOf(
        // Countries → a representative zone.
        "japan" to "Asia/Tokyo",
        "uk" to "Europe/London", "england" to "Europe/London",
        "britain" to "Europe/London", "great britain" to "Europe/London",
        "scotland" to "Europe/London", "wales" to "Europe/London",
        "france" to "Europe/Paris",
        "germany" to "Europe/Berlin",
        "italy" to "Europe/Rome",
        "spain" to "Europe/Madrid",
        "portugal" to "Europe/Lisbon",
        "ireland" to "Europe/Dublin",
        "netherlands" to "Europe/Amsterdam", "holland" to "Europe/Amsterdam",
        "greece" to "Europe/Athens",
        "turkey" to "Europe/Istanbul",
        "sweden" to "Europe/Stockholm",
        "norway" to "Europe/Oslo",
        "poland" to "Europe/Warsaw",
        "switzerland" to "Europe/Zurich",
        "russia" to "Europe/Moscow",
        "india" to "Asia/Kolkata", "mumbai" to "Asia/Kolkata",
        "bombay" to "Asia/Kolkata", "bangalore" to "Asia/Kolkata",
        "delhi" to "Asia/Kolkata", "new delhi" to "Asia/Kolkata",
        "china" to "Asia/Shanghai", "beijing" to "Asia/Shanghai",
        "south korea" to "Asia/Seoul", "korea" to "Asia/Seoul",
        "thailand" to "Asia/Bangkok",
        "vietnam" to "Asia/Ho_Chi_Minh",
        "indonesia" to "Asia/Jakarta",
        "philippines" to "Asia/Manila",
        "pakistan" to "Asia/Karachi",
        "saudi arabia" to "Asia/Riyadh",
        "israel" to "Asia/Jerusalem",
        "uae" to "Asia/Dubai",
        "egypt" to "Africa/Cairo",
        "south africa" to "Africa/Johannesburg",
        "nigeria" to "Africa/Lagos",
        "kenya" to "Africa/Nairobi",
        "australia" to "Australia/Sydney",
        "new zealand" to "Pacific/Auckland",
        "brazil" to "America/Sao_Paulo",
        "mexico" to "America/Mexico_City",
        "canada" to "America/Toronto",
        "argentina" to "America/Argentina/Buenos_Aires",
        // US abbreviations / nicknames.
        "nyc" to "America/New_York", "new york city" to "America/New_York",
        "manhattan" to "America/New_York",
        "dc" to "America/New_York", "washington" to "America/New_York",
        "washington dc" to "America/New_York",
        "la" to "America/Los_Angeles", "sf" to "America/Los_Angeles",
        "san fran" to "America/Los_Angeles", "the bay area" to "America/Los_Angeles",
        "vegas" to "America/Los_Angeles", "las vegas" to "America/Los_Angeles",
        "hawaii" to "Pacific/Honolulu",
    )
}
