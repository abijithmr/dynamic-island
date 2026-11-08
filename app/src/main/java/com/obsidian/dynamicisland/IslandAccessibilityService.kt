package com.obsidian.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service is only used as a window host: its overlay type draws above the
 * status bar and receives touches there. It does not read the screen.
 */
class IslandAccessibilityService : AccessibilityService() {
    private var overlay: IslandOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        overlay?.hide()
        overlay = IslandOverlay(this).also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

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
