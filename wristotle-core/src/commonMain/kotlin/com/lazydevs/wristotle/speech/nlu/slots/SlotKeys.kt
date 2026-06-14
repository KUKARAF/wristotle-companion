// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

/**
 * Canonical names for every key a [SlotExtractor] writes into its result
 * map AND every key a handler reads back. Until this object existed each
 * slot/handler used a raw string literal — renaming a key in the slot
 * without touching the matching read in the handler would silently break
 * the intent at runtime.
 *
 * Referenced from:
 *  - the slot extractor that writes the key
 *  - the handler that reads the key
 *  - `ConfirmSummaryBuilder` when the intent goes through the
 *    confirm-before-dispatch path
 *
 * Tied to the wire format: changing any value here is a breaking change
 * if learning data ever persists IntentResult slot dumps, so prefer
 * adding new keys over renaming existing ones.
 */
object SlotKeys {
    /** Free-form user query — used by intents that pass the residue
     *  straight to a backend (AskAgent, Calculate's pre-parse). */
    const val Query = "query"

    /** Resolved or spoken target — verbs that act on something the user
     *  named in the utterance. */
    const val Contact = "contact"
    const val App = "app"
    const val Body = "body"
    const val Target = "target"
    const val Location = "location"
    const val Title = "title"
    const val Attendee = "attendee"

    /** Numeric / structured values. */
    const val Time = "time"
    const val Date = "date"
    const val Seconds = "seconds"
    const val Count = "count"
    const val DurationMinutes = "durationMinutes"
    const val Expression = "expression"

    /** Discriminators / filter values. */
    const val Filter = "filter"

    /** Reminder opt-in: the user said "persistent reminder" / "nag me" / "keep
     *  reminding me" and wants the phone to re-fire the reminder until they
     *  dismiss it. Value is a [Boolean]. */
    const val Persistent = "persistent"

    /** Filter values used with [Filter]. Discriminate the list-style
     *  intents (ListTasks pending vs completed). Not slot keys themselves
     *  — string values written as a slot's *value*. */
    const val FilterCompleted = "completed"
    const val FilterPending = "pending"

    /** Sports intent: which kind of query (value is the SportKind enum —
     *  NEXT / LAST / LIVE / STANDINGS) + the spoken team/league subject
     *  (String; omitted when the user means a saved favorite, e.g. "did
     *  we win"). */
    const val SportKind = "sportKind"
    const val Subject = "subject"

    /** Resolved contact match — held as a [com.lazydevs.wristotle.phone.ContactsRepository.Contact]
     *  (name + number). Populated by whichever upstream pass first
     *  matches the spoken name against the address book; either the
     *  slot extractor (when it pre-validates while extracting, e.g.
     *  [SendMessageSlots]'s multi-word loop) or
     *  `PebbleListenerService.enrichResolvedContact` as a fallback.
     *  Downstream handlers and `ConfirmSummaryBuilder` read it instead
     *  of re-querying the Contacts provider. */
    const val ResolvedContact = "resolvedContact"

    /** Path to a captured audio clip — set by [NoteHandler] /
     *  [AppendNoteHandler] when the dictation flow attached audio. */
    const val AudioPath = "audioPath"
}