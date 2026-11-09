package com.obsidian.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service is the only permission the island needs. It hosts the overlay
 * window (which sits above the status bar, so taps at the cutout reach it) and notices which
 * app comes to the front so the island can show your most-used apps. It never reads screen text.
 */
class IslandAccessibilityService : AccessibilityService() {
    private val tracker by lazy { AppTracker(this) }
    private var overlay: IslandOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        overlay?.hide()
        overlay = IslandOverlay(this, tracker).also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let(tracker::onForeground)
        }
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged(newConfig)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        overlay?.hide()
        overlay = null
        running = false
    }

    companion object {
        @Volatile
        var running = false
            private set
    }
}
