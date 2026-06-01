package com.lazydevs.wristotle.ui.components

/**
 * How long a `SharingStarted.WhileSubscribed(...)` flow keeps running
 * after the last collector unsubscribes. Five seconds is the default
 * recommended by the Compose+Coroutines docs — survives Activity
 * recreation (rotation, theme change) without the cold-start cost of
 * rebuilding the upstream pipeline.
 *
 * Hoisted to one place so a future bump (e.g. to handle slow watch
 * round-trips) updates every VM in lockstep.
 */
const val WHILE_SUBSCRIBED_MS: Long = 5_000L
