package com.obsidian.dynamicisland

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import kotlin.math.pow

/**
 * Counts how often each app is brought to the foreground, using the window-change events the
 * accessibility service already receives. Scores halve every two weeks so the list follows
 * what you use now. Everything stays on the device.
 */
class AppUsageTracker(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_usage", Context.MODE_PRIVATE)
    private val pm = context.packageManager
    private var last: String? = null

    private val homePackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    }

    private val keyboardPackages: Set<String> by lazy {
        context.getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.map { it.packageName }.toSet()
    }

    fun onForeground(pkg: String) {
        if (pkg in homePackages) {
            // Back on the home screen: the next launch of any app counts again.
            last = null
            return
        }
        if (pkg == last || pkg == context.packageName || pkg == "com.android.systemui" || pkg in keyboardPackages) return
        if (pm.getLaunchIntentForPackage(pkg) == null) return
        last = pkg
        val now = System.currentTimeMillis()
        prefs.edit().putString(pkg, "${decayed(pkg, now) + 1.0}|$now").apply()
    }

    /** Package names, most used first. */
    fun top(limit: Int): List<String> {
        val now = System.currentTimeMillis()
        return prefs.all.keys
            .map { it to decayed(it, now) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun decayed(pkg: String, now: Long): Double {
        val parts = prefs.getString(pkg, null)?.split('|') ?: return 0.0
        val score = parts.getOrNull(0)?.toDoubleOrNull() ?: return 0.0
        val stamp = parts.getOrNull(1)?.toLongOrNull() ?: now
        return score * 0.5.pow((now - stamp).toDouble() / HALF_LIFE_MS)
    }

    private companion object {
        const val HALF_LIFE_MS = 14.0 * 24 * 60 * 60 * 1000
    }
}
