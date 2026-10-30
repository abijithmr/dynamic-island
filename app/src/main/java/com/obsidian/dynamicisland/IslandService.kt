package com.obsidian.dynamicisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.obsidian.dynamicisland.ui.IslandRoot
import kotlinx.coroutines.flow.MutableStateFlow

/** Size and position of the compact pill, derived from the camera cutout. */
data class IslandGeometry(
    val compactWidthDp: Float,
    val compactHeightDp: Float,
    val yPx: Int,
)

private class OverlayFrame(context: Context, private val onOutside: () -> Unit) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_OUTSIDE) onOutside()
        return super.dispatchTouchEvent(ev)
    }
}

class IslandService : Service() {
    private lateinit var windowManager: WindowManager
    private val owner = OverlayOwner()
    private var repo: MediaRepository? = null
    private var frame: OverlayFrame? = null
    private var params: WindowManager.LayoutParams? = null

    private val expanded = MutableStateFlow(false)
    private val visible = MutableStateFlow(true)
    private val geometry = MutableStateFlow(IslandGeometry(118f, 36f, 0))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(WindowManager::class.java)
        createChannel()
        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        owner.create()
        val repository = MediaRepository(this).also { it.start() }
        repo = repository
        visible.value = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        geometry.value = computeGeometry()
        addOverlay(repository)
        owner.resume()
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
        // Landscape moves the cutout to a side edge, so hide the island there.
        visible.value = newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE
        expanded.value = false
        geometry.value = computeGeometry()
        val p = params ?: return
        p.y = geometry.value.yPx
        frame?.let { runCatching { windowManager.updateViewLayout(it, p) } }
    }

    override fun onDestroy() {
        running = false
        frame?.let { runCatching { windowManager.removeView(it) } }
        frame = null
        repo?.stop()
        repo = null
        owner.destroy()
        super.onDestroy()
    }

    private fun computeGeometry(): IslandGeometry {
        val density = resources.displayMetrics.density
        val cutout = windowManager.maximumWindowMetrics.windowInsets.displayCutout
        val rect = cutout?.boundingRectTop?.takeIf { !it.isEmpty }
        return if (rect != null) {
            val cutoutWdp = rect.width() / density
            val heightDp = (rect.height() / density + 10f).coerceIn(32f, 44f)
            val y = (rect.exactCenterY() - heightDp * density / 2f).toInt().coerceAtLeast(0)
            IslandGeometry(cutoutWdp + 2f * (heightDp + 8f), heightDp, y)
        } else {
            // No top cutout reported: sit just below the top edge.
            IslandGeometry(118f, 34f, (8 * density).toInt())
        }
    }

    private fun addOverlay(repository: MediaRepository) {
        val root = OverlayFrame(this) { expanded.value = false }
        val compose = ComposeView(this).apply {
            setContent {
                val np by repository.state.collectAsState()
                val isExpanded by expanded.collectAsState()
                val isVisible by visible.collectAsState()
                val geo by geometry.collectAsState()
                if (isVisible) {
                    IslandRoot(
                        np = np,
                        geo = geo,
                        expanded = isExpanded,
                        setExpanded = { expanded.value = it },
                    )
                }
            }
        }
        root.addView(compose)
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = geometry.value.yPx
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        params = lp
        frame = root
        windowManager.addView(root, lp)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Island service", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun startAsForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, IslandService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island)
            .setContentTitle("Dynamic Island is running")
            .setContentText("Showing what's playing at the camera cutout")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_island), "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "island"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.obsidian.dynamicisland.STOP"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            Prefs.setEnabled(context, true)
            ContextCompat.startForegroundService(context, Intent(context, IslandService::class.java))
        }

        fun stop(context: Context) {
            Prefs.setEnabled(context, false)
            context.stopService(Intent(context, IslandService::class.java))
        }
    }
}
