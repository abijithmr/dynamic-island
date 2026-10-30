package com.obsidian.dynamicisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.obsidian.dynamicisland.ui.IslandRoot
import kotlinx.coroutines.flow.MutableStateFlow

/** Size and position of the compact pill, derived from the display cutout. */
data class IslandGeometry(
    val compactWidthDp: Float,
    val compactHeightDp: Float,
    val yPx: Int,
)

class IslandService : Service() {
    private lateinit var windowManager: WindowManager
    private val owner = OverlayOwner()
    private var repo: MediaRepository? = null
    private var overlay: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    private val expanded = MutableStateFlow(false)
    private val geometry = MutableStateFlow(IslandGeometry(118f, 36f, 0))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createChannel()
        try {
            startAsForeground()
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow Display over other apps, then start Dynamic Island again.", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
            owner.create()
            val repository = MediaRepository(this).also { it.start() }
            repo = repository
            geometry.value = computeGeometry()
            addOverlay(repository)
            owner.resume()
            running = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Android denied permission while creating the island overlay", e)
            Toast.makeText(this, "Android denied overlay access. Check Display over other apps permission in Settings.", Toast.LENGTH_LONG).show()
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create island overlay", e)
            Toast.makeText(this, "Dynamic Island could not start: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.let {
            geometry.value = computeGeometry()
            params?.let { p ->
                p.y = geometry.value.yPx
                runCatching { windowManager.updateViewLayout(it, p) }
            }
        }
    }

    override fun onDestroy() {
        running = false
        repo?.stop()
        repo = null
        overlay?.let { view -> runCatching { windowManager.removeView(view) } }
        overlay = null
        owner.destroy()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Dynamic Island", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the now-playing island overlay running"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, IslandService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val launchIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island)
            .setContentTitle("Dynamic Island is active")
            .setContentText("Showing media playback controls")
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun computeGeometry(): IslandGeometry {
        val metrics = resources.displayMetrics
        val density = metrics.density.coerceAtLeast(1f)
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            windowManager.currentWindowMetrics.windowInsets.displayCutout
        } else null
        val bounds = cutout?.boundingRectTop
        val widthPx = bounds?.width()?.takeIf { it > 0 } ?: (118f * density).toInt()
        val heightPx = bounds?.height()?.takeIf { it > 0 } ?: (28f * density).toInt()
        val centerX = bounds?.centerX() ?: metrics.widthPixels / 2
        val y = (bounds?.top ?: 0).coerceAtLeast(0)
        return IslandGeometry(
            compactWidthDp = (widthPx / density).coerceIn(100f, 180f),
            compactHeightDp = (heightPx / density + 8f).coerceIn(32f, 48f),
            yPx = y,
        )
    }

    private fun addOverlay(repository: MediaRepository) {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val nowPlaying by repository.state.collectAsState()
                val isExpanded by expanded.collectAsState()
                val geo by geometry.collectAsState()
                IslandRoot(
                    np = nowPlaying,
                    geo = geo,
                    expanded = isExpanded,
                    setExpanded = { expanded.value = it },
                )
            }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = geometry.value.yPx
            setTitle("Dynamic Island")
        }
        windowManager.addView(view, p)
        overlay = view
        params = p
    }

    companion object {
        private const val TAG = "IslandService"
        private const val CHANNEL_ID = "dynamic_island"
        private const val NOTIFICATION_ID = 701
        const val ACTION_STOP = "com.obsidian.dynamicisland.STOP"
        @Volatile var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, IslandService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
