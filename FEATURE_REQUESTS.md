# Feature requests

User-originated feature requests for Wristotle (watch + companion),
gathered from Reddit, Codeberg issues, and direct feedback. This is the
intake + triage list — internal engineering tasks are tracked separately.

To request a feature: open a [Codeberg issue](https://codeberg.org/kchinnasamy/wristotle-companion/issues/new)
or comment on the r/pebble thread.

## Status legend

| Status | Meaning |
|--------|---------|
| 🆕 New | Captured, not yet triaged |
| 🤔 Considering | Under evaluation / needs design |
| 📋 Planned | Accepted, scheduled |
| 🚧 In progress | Being built |
| ✅ Shipped | Released (note the version) |
| ❌ Declined | Won't do (reason in notes) |

## Requests

| # | Request | Which app | Source | Date | Status | Notes |
|---|---------|-----------|--------|------|--------|-------|
| 1 | Confirm the resolved **action** before dispatching (e.g. "Text Alex?"), distinct from confirming the transcription | companion + watch | Reddit r/pebble | 2026-05-19 | 🆕 New | Triggered by a contact mis-resolution — see detail §1 |

## Detail

Use a short section here for any request that needs more than a table
row — design notes, tradeoffs, why it was accepted or declined.

### 1 · Confirm the resolved action before dispatching

**What the user asked for.** A confirmation step *after* the command is
classified and its target resolved, but *before* it executes — e.g. they
asked to text a contact and the companion texted a different,
dissimilar-named contact and sent immediately. They'd rather confirm the
**action** ("Text \<resolved contact\>?") than confirm the
**transcription** (the existing `dictation_confirmation`, which previews
the recognised text). Their words: "I would rather have the option to
pause before dispatching the command rather than pausing to see what
text it thinks I said."

**Why this is distinct from existing confirmation.** Today we have
`dictation_confirmation` (watch-side, previews the transcribed text). The
ask is a *second*, later checkpoint on the resolved intent + slots —
specifically the contact/app/target the companion picked — so a correct
transcription that resolves to the wrong contact is still catchable.

**Two angles, probably both worth doing:**
1. **The requested feature** — an opt-in "confirm action before
   dispatch" step. The action is decided on the companion, so confirming
   it means a round-trip: companion sends a "confirm: \<action summary\>"
   back to the watch, watch shows it with a select-to-confirm / back-to-
   cancel, companion dispatches only on confirm. Likely a per-action-type
   setting — confirm destructive actions (call / text) by default, skip
   read-only ones (time / battery). Fits the new Settings → Watch surface.
2. **The underlying bug** — contact resolution chose a contact with *no
   common spelling*. That's a matching-quality problem in
   `ContactsRepository` (LIKE %query%, prefix-preferred): a poor/garbled
   transcription shouldn't silently snap to a dissimilar contact. Worth a
   confidence floor or "no good match → ask" rather than always picking
   the best-of-bad. Tracked-adjacent to, but separable from, the
   confirmation feature.

**Open questions for design:** where the confirm setting lives (watch
Clay + companion mirror?), how the watch renders the confirm prompt
within the existing dictation/chat flow, and whether the timeout that
governs reminder/cancel sends should also bound the confirm wait.
