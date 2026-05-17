package com.lazydevs.wristotle.speech.nlu.bank

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One labeled training example for the intent classifier.
 *
 * Stored on-device only. Two sources today:
 *   - `seed`    bundled with the app (canonical phrasings per intent)
 *   - `learned` added at runtime by [LearningCollector] after a successful
 *               dispatch — implicit learning loop
 *
 * Intents are stored by name (not ordinal) so the Intent enum can grow
 * without a Room migration. The unique index on [normalizedText] handles
 * dedup at insert time.
 */
@Entity(
    tableName = "nlu_examples",
    indices = [
        Index(value = ["intent"]),
        Index(value = ["normalizedText"], unique = true),
    ],
)
data class ExampleEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val intent: String,
    val rawText: String,
    val normalizedText: String,
    val source: String,
    val addedAtEpochMs: Long,
    val usageCount: Int = 1,
)
