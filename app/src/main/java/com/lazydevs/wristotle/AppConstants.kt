package com.lazydevs.wristotle

import java.util.UUID

/**
 * Global constants for the application.
 */
object AppConstants {
    /** UUID must match the `uuid` field in the watch app's package.json. */
    val PEBBLE_UUID: UUID = UUID.fromString("a48bf4be-be56-4afb-97a6-5a72ed2f0643")

    /** Notification configuration for the foreground service. */
    object Notifications {
        const val SERVICE_NOTIFICATION_ID = 1
        const val CHANNEL_ID = "wristotle_service"
    }
}
