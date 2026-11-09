package com.obsidian.dynamicisland

import android.service.notification.NotificationListenerService

/**
 * Declares optional notification access so Android can expose the setting. Media sessions are
 * discovered from accessibility notification events by [IslandAccessibilityService].
 */
class MediaListenerService : NotificationListenerService()
