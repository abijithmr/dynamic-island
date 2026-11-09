package com.obsidian.dynamicisland

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap)

sealed interface AppsUi {
    data object Loading : AppsUi

    /** [personalised] is false while there isn't enough history yet and recent apps fill in. */
    data class Ready(val apps: List<AppEntry>, val personalised: Boolean) : AppsUi
}

/**
 * Counts which app comes to the front, using the accessibility service's window events.
 * No Usage access permission is needed. Only package names and counts are stored, on-device.
 */
class AppTracker(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_usage", Context.MODE_PRIVATE)
    private var current: String? = null
    private var since = 0L

    private val homePackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet() +
            "com.android.systemui"
    }
    private val ignored: Set<String> by lazy {
        val keyboards = runCatching {
            context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        keyboards.toSet() + context.packageName + "android"
    }

    fun onForeground(pkg: String) {
        if (pkg == current) return
        if (pkg in homePackages) {
            // Back on the home screen or shade: stop the clock for the app we were in.
            settle()
            current = null
            return
        }
        if (pkg in ignored) return
        settle()
        prefs.edit().putInt("n_$pkg", prefs.getInt("n_$pkg", 0) + 1).apply()
        current = pkg
        since = SystemClock.elapsedRealtime()
    }

    private fun settle() {
        val prev = current ?: return
        val spent = (SystemClock.elapsedRealtime() - since).coerceIn(0L, MAX_SPAN_MS)
        prefs.edit().putLong("t_$prev", prefs.getLong("t_$prev", 0L) + spent).apply()
    }

    /** Package names, most used first (launch count, then time spent). */
    fun ranked(): List<String> {
        settle()
        since = SystemClock.elapsedRealtime()
        return prefs.all.keys
            .filter { it.startsWith("n_") }
            .map { it.removePrefix("n_") }
            .sortedWith(
                compareByDescending<String> { prefs.getInt("n_$it", 0) }
                    .thenByDescending { prefs.getLong("t_$it", 0L) },
            )
    }

    private companion object {
        const val MAX_SPAN_MS = 30L * 60 * 1000
    }
}

class FrequentApps(private val context: Context, private val tracker: AppTracker) {
    private val icons = HashMap<String, AppEntry>()

    suspend fun load(limit: Int = 8): AppsUi.Ready = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val result = ArrayList<AppEntry>()
        val seen = HashSet<String>()

        for (pkg in tracker.ranked()) {
            if (result.size >= limit) break
            entry(pkg)?.let { result.add(it); seen.add(pkg) }
        }
        val personalised = result.size >= 4

        if (result.size < limit) {
            // Not enough history yet: fill with the most recently installed or updated apps.
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName && it !in seen }
                .sortedByDescending { runCatching { pm.getPackageInfo(it, 0).lastUpdateTime }.getOrDefault(0L) }
                .forEach { pkg ->
                    if (result.size >= limit) return@forEach
                    entry(pkg)?.let { result.add(it) }
                }
        }
        AppsUi.Ready(result, personalised)
    }

    private fun entry(pkg: String): AppEntry? {
        icons[pkg]?.let { return it }
        val pm = context.packageManager
        return runCatching {
            if (pm.getLaunchIntentForPackage(pkg) == null) return null
            val info = pm.getApplicationInfo(pkg, 0)
            AppEntry(
                pkg,
                pm.getApplicationLabel(info).toString(),
                pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap(),
            ).also { icons[pkg] = it }
        }.getOrNull()
    }
}
