package com.obsidian.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Prefs.isEnabled(context) &&
            Settings.canDrawOverlays(context)
        ) {
            IslandService.start(context)
        }
    }
}
