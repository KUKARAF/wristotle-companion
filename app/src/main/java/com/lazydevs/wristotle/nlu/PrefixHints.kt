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
        // SendMessage — every "send a message" / "text …" style query
        // routes here after Phase A2 subsumed SMS into the messaging
        // registry. The single SendMessageHandler dispatches via the
        // resolved MessagingTarget — SMS (programmatic SmsManager) when
        // no app is named, or WhatsApp / Telegram / Signal (assisted-
        // send deep-link) when an app name appears.
        //
        // The app-named patterns sit FIRST so "send a WhatsApp message
        // to Mom …" matches the named-app shape before the bare verb
        // pattern below. Their alias list MUST stay in sync with
        // MessagingTargets.NAMED so the prefix-hint can rescue the
        // classifier on Whisper-mistranscribed variants like "what's
        // up" / "whats app".
        Regex("(?i)^\\s*(whatsapp|whats\\s*app|what'?s\\s*up|whatapp|watsapp|whatsap|telegram|signal)\\b") to Intent.SendMessage,
        Regex("(?i)^\\s*send\\s+(a\\s+|an\\s+)?(whatsapp|telegram|signal)\\b") to Intent.SendMessage,
        Regex("(?i)\\b(text|message)\\s+\\S.*\\bon\\s+(whatsapp|whats\\s*app|telegram|signal)\\b") to Intent.SendMessage,
        // Bare-verb shape (text / message / sms / send-a-text) — SMS
        // is the default target inside the handler when no app name
        // is parsed out of the body. Pre-A2 this routed to Intent.Sms
        // (a separate intent with its own handler); the Phase A2
        // migration relabels every Sms example_bank row to
        // SendMessage so the centroid stays meaningful.
        Regex("(?i)^\\s*(text|sms|message|send (a |an )?(text|message|sms))\\b") to Intent.SendMessage,
        Regex("(?i)^\\s*(call|dial|phone|ring)\\b") to Intent.Call,
        // AppendNote — sits ABOVE CreateEvent (and Note) because it's the most
        // specific rule. Two safe shapes:
        //  - "append" or "amend" as a leading verb (both semantically
        //    unambiguous — you can only append/amend something that exists).
        //    "amend" is included because Whisper consistently mishears
        //    "append" as "amend" — caught when a Core Devices alpha-tester
        //    said "append to the notes" and the watch chat showed
        //    "Amend to the notes: ...".
        //  - "(add|append|amend) to (the|my)? (previous|last|latest|recent)
        //    notes" — the qualifier guards against "add to my notes" routing
        //    here when the user meant a new note. Without the qualifier "add"
        //    stays Note, and "add to my meeting…" stays CreateEvent.
        Regex("(?i)^\\s*((append|amend)\\b|(add|append|amend)\\s+to\\s+(the\\s+|my\\s+)?(previous|last|latest|recent)\\s+notes?\\b)") to Intent.AppendNote,
        // AddTask — sits ABOVE CreateEvent because CreateEvent's noun
        // set includes "call" (for "schedule a call with X"), and the
        // user's task body often mentions "call the dentist". Without
        // this ordering, "add task to call the dentist" matches the
        // CreateEvent rule (^add … \bcall\b) and routes to schedule a
        // meeting. AddTask's regex requires the explicit `task[s]` /
        // `todo` anchor so it can't false-match "add a meeting".
        //
        // Narrow trigger words only, so we don't bleed into Note
        // ("note: buy milk") or Reminder ("remind me to buy milk").
        // Anti-rule context: the `remind` / `remember` / `note` rules
        // sit even higher in this list, so those openers win over
        // AddTask even when "task" appears in the body. `tasks?`
        // (singular or plural) because Whisper routinely pluralises.
        Regex("(?i)^\\s*((add|create|make|new)\\s+(a\\s+|an\\s+)?tasks?\\b|tasks?\\b|(add|put)\\s+(to|on|in|onto)\\s+(my|the)\\s+(tasks?|to[- ]?do(\\s+list)?|todos?)\\b|(add|put)\\s+\\S.*\\b(to|on|in|onto)\\s+(my|the)\\s+(task|tasks|to[- ]?do(\\s+list)?|todos?)\\s*$)") to Intent.AddTask,
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
        // Note — unambiguous capture lead-ins. Placed above Reminder so a
        // "remember that …" opener routes to Note instead of falling through
        // to the embedding (which can drift to Unknown without a time). Leaves
        // "remind …" alone — that stays Reminder. "remember to …" without a
        // clear time is genuinely ambiguous (note vs reminder); we don't
        // prefix-route it and let the classifier seeds + slot extraction
        // decide.
        Regex("(?i)^\\s*(notes?[\\s.:,;!?\\-]|(make|take|save|store|keep|add)\\s+(a\\s+)?notes?\\b|(jot|write)\\s+(this|that|it)?\\s*down\\b|noted\\b|(for|to|add\\s+to)\\s+my\\s+notes?\\b|remember\\s+that\\b)") to Intent.Note,
        // SetAlarm + SetTimer — sit ABOVE Reminder. The embedding confuses
        // "wake me up at 7" / "set an alarm for 7" with "remind me at 7"
        // (the time dominates the cosine), so the alarm / timer keyword is
        // the deterministic discriminator. "wake me" has no keyword noun so
        // it gets its own opener. These don't collide with the Cancel rule
        // ("kill the alarm" / "delete the alarm" start with cancel verbs,
        // not set/start, so they stay Cancel).
        Regex("(?i)(^\\s*(set|start|put|create|new)\\b.*\\balarm\\b|\\balarm\\s+(for|at)\\b|^\\s*wake\\s+me\\b)") to Intent.SetAlarm,
        Regex("(?i)(^\\s*(set|start|put|create|new|countdown|give\\s+me)\\b.*\\btimer\\b|\\btimer\\s+for\\b)") to Intent.SetTimer,
        Regex("(?i)^\\s*(remind|set (a )?reminder|reminder)\\b") to Intent.Reminder,
        // ListTasks — interrogative / list / show + the `tasks` noun.
        // Mirrors the ListReminders shape. Sits ABOVE AddTask so a
        // "show my tasks" query never accidentally routes to create.
        Regex("(?i)^\\s*(what|which|when|do|does|did|have|has|had|any|list|show|read|tell)\\b.*\\btasks?\\b") to Intent.ListTasks,
        // ListTasks — bare "completed tasks" / "done tasks" / "pending
        // tasks" form. The past-tense verb here is a FILTER, not a
        // command — without this rule, "completed tasks" would match
        // the bare CompleteTask `^complete\b` regex below and try to
        // mark a task named "tasks" done. MUST sit ABOVE the
        // CompleteTask rules.
        Regex("(?i)^\\s*(completed|done|finished|pending|open|remaining|outstanding)\\s+tasks?\\b") to Intent.ListTasks,
        // AddTask is now declared higher up — ABOVE CreateEvent —
        // because *"add task to call the dentist"* contains "call"
        // which CreateEvent's noun set would otherwise catch. The
        // rule stays the same; only its position moved.
        // CompleteTask — task-specific verbs that don't collide with
        // any existing intent. "complete X" / "mark X done" / "finish X"
        // / "done with X" / "tick off X" — none of these are used for
        // reminders or media. The slot extractor strips the verb and
        // returns `target` for matching.
        Regex("(?i)^\\s*(complete|completed|finish|finished|tick off|check off|cross off|mark off|knock off)\\b") to Intent.CompleteTask,
        Regex("(?i)^\\s*mark\\b.*\\b(done|complete|completed|finished|off)\\b") to Intent.CompleteTask,
        Regex("(?i)^\\s*done\\s+(with|the)\\b") to Intent.CompleteTask,
        // DeleteTask — sits ABOVE Cancel so "delete X from my tasks"
        // routes here, not to Cancel (which is reminder-only). Requires
        // an explicit task[s] / todo keyword somewhere in the query so
        // "delete X" / "remove X" without that anchor stays with Cancel
        // (existing behaviour for reminder cancellation). Two shapes:
        //  - leading-noun: "delete task X" / "remove task X"
        //  - trailing-noun: "delete X from my tasks" / "scratch X off
        //    my todo"
        Regex("(?i)^\\s*(delete|remove|scratch|drop)\\s+(a\\s+|an\\s+|the\\s+|my\\s+)?tasks?\\b") to Intent.DeleteTask,
        Regex("(?i)^\\s*(delete|remove|scratch|drop)\\b.*\\b(from|off|out\\s+of)\\s+(my|the)\\s+(tasks?|to[- ]?do(\\s+list)?|todos?)\\s*$") to Intent.DeleteTask,
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
