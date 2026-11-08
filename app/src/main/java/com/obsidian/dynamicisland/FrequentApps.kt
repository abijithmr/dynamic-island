package com.obsidian.dynamicisland

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap)

sealed interface AppsUi {
    data object NoAccess : AppsUi
    data object Loading : AppsUi
    data class Ready(val apps: List<AppEntry>) : AppsUi
}

object UsageAccess {
    fun isGranted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }
}

/** Ranks installed apps by foreground time over the last week. Needs Usage access. */
class FrequentApps(private val context: Context) {
    private var cache: List<AppEntry> = emptyList()
    private var cachedAt = 0L

    suspend fun load(limit: Int = 8): List<AppEntry> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cachedAt < 60_000L) return@withContext cache

        val usm = context.getSystemService(UsageStatsManager::class.java)
        val stats = usm.queryAndAggregateUsageStats(now - WEEK_MS, now)
        val pm = context.packageManager

        val skip = HashSet<String>().apply {
            add(context.packageName)
            add("com.android.systemui")
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(home, 0).forEach { add(it.activityInfo.packageName) }
        }

        val result = ArrayList<AppEntry>()
        stats.values
            .filter { it.totalTimeInForeground > 0 && it.packageName !in skip }
            .sortedByDescending { it.totalTimeInForeground }
            .forEach { s ->
                if (result.size >= limit) return@forEach
                runCatching {
                    if (pm.getLaunchIntentForPackage(s.packageName) == null) return@runCatching
                    val info = pm.getApplicationInfo(s.packageName, 0)
                    val label = pm.getApplicationLabel(info).toString()
                    val icon = pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
                    result.add(AppEntry(s.packageName, label, icon))
                }
            }
        cache = result
        cachedAt = now
        result
    }

    private companion object {
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
