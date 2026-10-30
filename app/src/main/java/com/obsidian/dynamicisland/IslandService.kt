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
import android.util.Log
import android.widget.Toast
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
        running = false
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
            visible.value = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            geometry.value = computeGeometry()
            addOverlay(repository)
            owner.resume()
            // Mark running only after WindowManager successfully attaches the overlay.
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
