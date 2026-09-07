// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

/**
 * How the voice pipeline routes a transcript relative to Ask Agent.
 *
 * - [OFF] — default. NLU-first: classify normally; unmatched speech is
 *   reported as "Unknown command". Ask Agent is reached only via its
 *   wake words.
 * - [FALLBACK] — classify first, but route anything that would land on
 *   `Unknown` to Ask Agent instead of reporting "Unknown command".
 *   Phone-control intents (call/text/timer/…) still resolve normally.
 * - [AGENT_ONLY] — bypass intent classification entirely; every spoken
 *   transcript goes straight to Ask Agent, no wake word. Deliberate
 *   watch-surface shortcuts (a button that names a surface) are still
 *   honoured — only free voice is forced through the agent.
 *
 * [FALLBACK] and [AGENT_ONLY] only take effect when Ask Agent is actually
 * configured (see [AskAgentSettings.isConfigured]); otherwise routing
 * behaves as [OFF] so flipping the mode without a provider can't silently
 * break every query.
 */
enum class AgentRoutingMode { OFF, FALLBACK, AGENT_ONLY }
