package com.lazydevs.wristotle.help

/**
 * Static help content surfaced under Settings → ❓ Help.
 *
 * Hand-maintained alongside the docs site `changelog.md` — kept in code
 * rather than fetched at runtime because the project is offline-first
 * and release cadence is slow enough (≈ weekly) that drift is easy to
 * keep an eye on. When you ship a new user-facing feature, add a
 * [FeatureEntry] at the TOP of [HelpContent.timeline] (newest first).
 *
 * NOT for: bug fixes, internal refactors, build / CI changes. Those
 * live in the changelog but not here. The Help page answers "what can
 * Wristotle do for me?" — only features that pass that bar.
 *
 * ## Style guide for new entries
 *
 * Keep every [FeatureEntry] in the same shape as its neighbours so the
 * Help page reads as one document, not 20 different voices:
 *
 *  - **`title`** — 3-7 words, capitalised feature name. Subtitle goes
 *    after an em-dash (`—`) or in parens, not in `description`.
 *    Examples: *"Ask Agent (LLM + MCP tools)"*, *"Reminder list &
 *    reschedule"*, *"World clock & calculator"*.
 *  - **`description`** — ONE sentence, ~10-20 words. Subject implicit
 *    (the feature itself). Critical caveats land in parens at the
 *    tail. Don't recap the title — describe what it DOES. The
 *    `docsPath` link is the long-form answer; resist the urge to
 *    over-explain here.
 *  - **`sampleQuery`** — exactly what a user would say, lowercase, no
 *    surrounding quotes (the renderer adds `Try: "…"`). Skip for
 *    features without a natural voice command (Settings UX, backup,
 *    aliases — anything you reach by tap, not speech).
 *  - **`docsPath`** — most specific anchor that exists on the docs
 *    site. Voice-driven features → `voice-commands/#<anchor>`; UX +
 *    Settings → `features/#<anchor>`; problem-solving tips →
 *    `troubleshooting/#<anchor>`. Verify the anchor exists by
 *    grepping `docs/<page>.md` for the heading text.
 *  - **`version` + `date`** — match the git tag exactly (`v0.X.Y`,
 *    ISO date). For combined companion+watch releases, use the
 *    companion version — the page is companion-side.
 *
 * Tips ([Tip]) follow the same compactness rule:
 *
 *  - **`title`** — action verb or question, 2-5 words. *"Add your own
 *    wake words"*, *"Whisper hears the wrong name?"*. Imperative if
 *    you're nudging an action; question form if you're flagging a
 *    problem the user might be hitting.
 *  - **`body`** — 1-2 sentences. Say what to tap AND why. Always
 *    include the Settings path (`Settings → 🎓 X → Y`) when the tip
 *    points at a setting.
 *  - **`docsPath`** — `troubleshooting/#anchor` if the tip is about
 *    fixing a problem; `voice-commands/` or `features/` for
 *    action-oriented tips.
 *
 * When in doubt, copy a neighbour and adapt. The point of the style
 * guide is consistency, not novelty.
 */
data class FeatureEntry(
    /** Release tag, e.g. `"v0.16.0"`. */
    val version: String,
    /** ISO date, e.g. `"2026-06-01"`. */
    val date: String,
    /** One-line headline, plain English. */
    val title: String,
    /** Single-sentence description. Keep it short — the docs link
     *  carries the depth. */
    val description: String,
    /** Optional voice-command example. Renders as "Try: …". */
    val sampleQuery: String? = null,
    /** Relative path on the docs site, including any `#anchor`.
     *  Resolved against [HelpContent.DOCS_BASE_URL]. */
    val docsPath: String? = null,
)

/**
 * A short, action-oriented tip — the kind of thing a user might not
 * discover by tapping around. Surfaced at the top of the Help page.
 */
data class Tip(
    val title: String,
    val body: String,
    /** Optional docs deep-link. Resolved against [HelpContent.DOCS_BASE_URL]. */
    val docsPath: String? = null,
)

object HelpContent {

    /** Docs site base. The MkDocs Material site uses `use_directory_urls`,
     *  so every entry's [FeatureEntry.docsPath] starts with the page slug
     *  (no `.md`) and may include a `#anchor` fragment. */
    const val DOCS_BASE_URL = "https://wristotle.codeberg.page/"

    /** Troubleshooting page — surfaced as a CTA at the bottom of the
     *  Help card. */
    const val TROUBLESHOOTING_PATH = "troubleshooting/"

    /** Top-of-page tips — the things a user is most likely to miss. */
    val tips: List<Tip> = listOf(
        Tip(
            title = "Add your own wake words",
            body = "Settings → ✨ Ask Agent → Custom trigger words lets you pick the name you'd call your assistant (e.g. \"Jarvis\"). Then bare or with \"ask\" — both work.",
            docsPath = "voice-commands/#ask-agent-llm-passthrough",
        ),
        Tip(
            title = "Confirm before sending",
            body = "Worried about voice misfires? Turn on Settings → ⌚ Watch → Confirm before sending and the watch prompts before calls / texts / alarms fire.",
            docsPath = "troubleshooting/#confirm-before-send-toggle-is-on-but-my-command-didnt-prompt",
        ),
        Tip(
            title = "Whisper hears the wrong name?",
            body = "Map the spoken phrase to the right contact under Settings → ⌚ Watch → Contact aliases. No need to rename the contact itself.",
            docsPath = "troubleshooting/#wristotle-keeps-mishearing-the-same-contact-or-app-name",
        ),
        Tip(
            title = "Encrypt your backups",
            body = "Set a password under Settings → 💾 Backup and the ZIP encrypts with AES-256. Without one, anyone with the file can read its contents.",
            docsPath = "features/#companion-side-features",
        ),
    )

    /**
     * Feature timeline, newest first. Only user-facing features —
     * patch releases that ship pure internals, fixes, or CI/build
     * changes are deliberately omitted (the docs-site changelog
     * carries the full history).
     */
    val timeline: List<FeatureEntry> = listOf(
        FeatureEntry(
            version = "v0.16.0", date = "2026-06-01",
            title = "Custom Ask Agent trigger words",
            description = "Add your own wake words for Ask Agent — works as a verb (\"ask jarvis …\") or bare (\"Jarvis, …\").",
            sampleQuery = "Jarvis, what's the weather?",
            docsPath = "voice-commands/#ask-agent-llm-passthrough",
        ),
        FeatureEntry(
            version = "v0.15.6", date = "2026-06-01",
            title = "Faster Whisper models + RAM hint",
            description = "New q4_0 quantised Tiny / Base / Small variants. Each model row now shows the approximate RAM cost.",
            docsPath = "features/#models-optional",
        ),
        FeatureEntry(
            version = "v0.15.0", date = "2026-05-31",
            title = "Per-category backup selection",
            description = "Pick exactly what your backup includes. Sensitive items (API keys, headers) default to OFF.",
            docsPath = "features/#companion-side-features",
        ),
        FeatureEntry(
            version = "v0.14.0", date = "2026-05-31",
            title = "Ask Agent (LLM + MCP tools)",
            description = "Route voice queries to any LLM you configure. Optional MCP tool calling lets the LLM take live actions.",
            sampleQuery = "ask agent what's the capital of France",
            docsPath = "voice-commands/#ask-agent-llm-passthrough",
        ),
        FeatureEntry(
            version = "v0.13.0", date = "2026-05-31",
            title = "Weather",
            description = "Voice weather for any city or your current location. open-meteo by default, OpenWeather optional.",
            sampleQuery = "weather in Tokyo",
            docsPath = "voice-commands/#weather",
        ),
        FeatureEntry(
            version = "v0.12.0", date = "2026-05-29",
            title = "World clock & calculator",
            description = "Time in any city, plus on-device arithmetic with spoken operators. Both fully offline.",
            sampleQuery = "what time is it in Tokyo",
            docsPath = "voice-commands/#world-clock",
        ),
        FeatureEntry(
            version = "v0.11.0", date = "2026-05-28",
            title = "Alarms & timers",
            description = "Set alarms and countdown timers by voice. The system Clock app handles the ring.",
            sampleQuery = "set a timer for 5 minutes",
            docsPath = "voice-commands/#alarms-timers",
        ),
        FeatureEntry(
            version = "v0.10.1", date = "2026-05-25",
            title = "See and prune learned phrases",
            description = "View — and individually delete — phrases Wristotle has learned from your voice commands.",
            docsPath = "features/#settings-tab-whats-in-each-category",
        ),
        FeatureEntry(
            version = "v0.10.0", date = "2026-05-25",
            title = "Contact aliases",
            description = "Map a spoken phrase to the right contact — fixes the \"Whisper mishears my friend's name\" problem.",
            docsPath = "features/#settings-tab-whats-in-each-category",
        ),
        FeatureEntry(
            version = "v0.9.0", date = "2026-05-24",
            title = "Tasks",
            description = "Voice-controlled checklist with on-watch picker and a companion Tasks tab. Included in backups.",
            sampleQuery = "add task call mom",
            docsPath = "voice-commands/#tasks",
        ),
        FeatureEntry(
            version = "v0.8.5", date = "2026-05-24",
            title = "WhatsApp / Telegram / Signal messaging",
            description = "Voice messaging across SMS plus three third-party apps via one intent. (Third-party apps need a tap to send.)",
            sampleQuery = "WhatsApp mom on my way",
            docsPath = "voice-commands/#sms-and-other-messaging-apps",
        ),
        FeatureEntry(
            version = "v0.8.3", date = "2026-05-24",
            title = "Reliable \"open <app>\" from the watch",
            description = "Cold-start launch of any installed app from the watch (needs Display over other apps).",
            sampleQuery = "open Spotify",
            docsPath = "voice-commands/#open-any-app",
        ),
        FeatureEntry(
            version = "v0.8.0", date = "2026-05-23",
            title = "Settings drill-down navigation",
            description = "Settings reorganised into focused sub-screens you reach with one tap from a landing list.",
            docsPath = "features/#settings-tab-whats-in-each-category",
        ),
        FeatureEntry(
            version = "v0.7.0", date = "2026-05-23",
            title = "Confirm before dispatch",
            description = "Optional watch prompt before destructive actions (call, text, alarm). Configurable timeout + default.",
            docsPath = "features/#settings-tab-whats-in-each-category",
        ),
        FeatureEntry(
            version = "v0.6.0", date = "2026-05-23",
            title = "Backup & Restore",
            description = "Export your data to a single ZIP and import it on a fresh install. AES-256 encryption optional.",
            docsPath = "features/#companion-side-features",
        ),
        FeatureEntry(
            version = "v0.5.0", date = "2026-05-22",
            title = "Voice notes, Quick Launch, App aliases",
            description = "Take voice notes, jump to dictation with a single watch press, and alias app names you call things differently.",
            sampleQuery = "take a note buy milk",
            docsPath = "voice-commands/#notes",
        ),
        FeatureEntry(
            version = "v0.4.0", date = "2026-05-21",
            title = "Reminder list & reschedule",
            description = "Ask what's pending; push a reminder to a new time. The watch hint routes cleanly into list / set / reschedule.",
            sampleQuery = "is there a reminder at 2pm",
            docsPath = "voice-commands/#list-reminders",
        ),
        FeatureEntry(
            version = "v0.3.0", date = "2026-05-19",
            title = "Voice calendar",
            description = "Schedule events and ask what's coming up. Reads your default calendar.",
            sampleQuery = "schedule a meeting with Alex at 3pm",
            docsPath = "voice-commands/#calendar",
        ),
        FeatureEntry(
            version = "v0.2.0", date = "2026-05-19",
            title = "Watch settings from the phone",
            description = "Configure watch-side toggles (font sizes, hints, defaults) from the companion app.",
            docsPath = "features/#settings-sync",
        ),
        FeatureEntry(
            version = "v0.1.0", date = "2026-05-18",
            title = "Initial release",
            description = "Calls, SMS, time-based reminders, media play / pause / next / previous / seek, find phone. All on-device.",
            sampleQuery = "call mom",
            docsPath = "voice-commands/",
        ),
    )
}
