package com.obsidian.dynamicisland

import android.service.notification.NotificationListenerService

/**
 * Empty on purpose. Enabling this listener in system settings is what grants
 * MediaSessionManager.getActiveSessions() access to other apps' media sessions.
 */
class MediaListenerService : NotificationListenerService()
