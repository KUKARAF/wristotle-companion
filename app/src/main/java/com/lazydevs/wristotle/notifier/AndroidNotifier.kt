// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notifier

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.speech.nlu.notifier.NotificationRequest
import com.lazydevs.wristotle.speech.nlu.notifier.Notifier

/**
 * Android impl of [Notifier]. Posts on a single shared
 * "wristotle_default" channel and uses [String.hashCode] to derive the
 * int notification ID from the request's string ID. Best-effort: if the
 * post throws (channel disabled, permission denied), returns false.
 *
 * R4 batch 2. The persistent-reminder receiver does NOT use this — it
 * keeps its own NotificationCompat.Builder call so it can wire custom
 * action-button PendingIntents that don't fit the interface. Use this
 * adapter for future generic post-a-notification flows.
 */
class AndroidNotifier(context: Context, private val channelId: String = DEFAULT_CHANNEL_ID) : Notifier {

    private val appContext = context.applicationContext

    override fun post(request: NotificationRequest): Boolean {
        return runCatching {
            val notification = NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentTitle(request.title)
                .setContentText(request.body)
                .setAutoCancel(true)
                .build()
            val nm = appContext.getSystemService(NotificationManager::class.java)
            nm.notify(request.id.hashCode(), notification)
            true
        }.getOrElse { false }
    }

    override fun cancel(id: String) {
        runCatching {
            appContext.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
        }
    }

    companion object {
        const val DEFAULT_CHANNEL_ID = "wristotle_default"
    }
}
