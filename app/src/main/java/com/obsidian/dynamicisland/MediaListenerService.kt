package com.obsidian.dynamicisland

import android.service.notification.NotificationListenerService

/**
 * Enabling this listener in system settings is what grants
 * MediaSessionManager.getActiveSessions() access to other apps' media sessions.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        onConnected?.invoke()
    }

    companion object {
        /** Set by the overlay so it can re-read sessions once access is granted. */
        @Volatile
        var onConnected: (() -> Unit)? = null
    }
}
