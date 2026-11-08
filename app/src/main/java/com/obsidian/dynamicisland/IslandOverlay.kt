package com.obsidian.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.obsidian.dynamicisland.ui.IslandRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object IslandSpec {
    const val EXPANDED_W_DP = 360f
    const val MUSIC_H_DP = 190f
    const val APPS_H_DP = 224f
    const val PAD_DP = 10f
}

/** Size and position of the pill, derived from the camera cutout. */
data class IslandGeometry(
    val compactWidthDp: Float,
    val idleWidthDp: Float,
    val compactHeightDp: Float,
    val expandedWidthDp: Float,
    val yPx: Int,
)

class IslandActions(
    val expand: () -> Unit,
    val collapse: () -> Unit,
    val launch: (String) -> Unit,
    val grantUsage: () -> Unit,
)

private class OverlayFrame(
    context: android.content.Context,
    private val onOutside: () -> Unit,
) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_OUTSIDE) onOutside()
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * Hosts the island in a TYPE_ACCESSIBILITY_OVERLAY window, which sits above the status bar
 * and therefore receives touches at the camera cutout.
 *
 * The window is only resized at the two endpoints (collapsed / expanded). All motion between
 * them happens inside Compose, so no WindowManager layout happens while animating.
 */
class IslandOverlay(private val service: AccessibilityService) {
    private val windowManager: WindowManager = service.getSystemService(WindowManager::class.java)
    private val owner = OverlayOwner()
    private val repo = MediaRepository(service)
    private val appsRepo = FrequentApps(service)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val uiExpanded = MutableStateFlow(false)
    private val geometry = MutableStateFlow(computeGeometry())
    private val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val apps = MutableStateFlow<AppsUi>(AppsUi.Loading)

    private var frame: OverlayFrame? = null
    private var params: WindowManager.LayoutParams? = null
    private var wantExpanded = false
    private var windowExpanded = false
    private var collapseJob: Job? = null

    private val actions = IslandActions(
        expand = ::requestExpand,
        collapse = ::requestCollapse,
        launch = ::launchApp,
        grantUsage = ::openUsageSettings,
    )

    fun show() {
        owner.create()
        repo.start()
        MediaListenerService.onConnected = { repo.restart() }

        // A paused session is kept for 30 s, then the island goes back to idle (frequent apps).
        scope.launch {
            repo.state.collectLatest { np ->
                nowPlaying.value = np
                if (np != null && !np.isPlaying) {
                    delay(PAUSED_IDLE_MS)
                    nowPlaying.value = null
                }
            }
        }
        // Idle needs a smaller window than music so it doesn't steal status-bar touches.
        scope.launch {
            nowPlaying.collectLatest { if (!windowExpanded && frame != null) setWindow(false) }
        }
        addWindow()
        owner.resume()
    }

    fun hide() {
        MediaListenerService.onConnected = null
        scope.cancel()
        frame?.let { runCatching { windowManager.removeView(it) } }
        frame = null
        repo.stop()
        owner.destroy()
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        // Landscape moves the cutout to a side edge, so hide the island there.
        frame?.visibility =
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) View.GONE else View.VISIBLE
        geometry.value = computeGeometry()
        wantExpanded = false
        uiExpanded.value = false
        setWindow(false)
    }

    // ---- state changes -------------------------------------------------------------------

    private fun requestExpand() {
        if (wantExpanded) return
        wantExpanded = true
        collapseJob?.cancel()
        if (nowPlaying.value == null) loadApps()
        // Grow the window first, then start the animation so nothing is clipped.
        setWindow(true)
        scope.launch {
            delay(48)
            if (wantExpanded) uiExpanded.value = true
        }
    }

    private fun requestCollapse() {
        if (!wantExpanded) return
        wantExpanded = false
        uiExpanded.value = false
        collapseJob?.cancel()
        // Shrink the window only after the spring has settled.
        collapseJob = scope.launch {
            delay(600)
            if (!wantExpanded) setWindow(false)
        }
    }

    private fun loadApps() {
        scope.launch {
            if (!UsageAccess.isGranted(service)) {
                apps.value = AppsUi.NoAccess
                return@launch
            }
            if (apps.value !is AppsUi.Ready) apps.value = AppsUi.Loading
            apps.value = AppsUi.Ready(appsRepo.load())
        }
    }

    private fun launchApp(packageName: String) {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        runCatching { service.startActivity(intent) }
        requestCollapse()
    }

    private fun openUsageSettings() {
        runCatching {
            service.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        requestCollapse()
    }

    // ---- window --------------------------------------------------------------------------

    private fun addWindow() {
        val root = OverlayFrame(service) { requestCollapse() }
        val compose = ComposeView(service).apply {
            setContent {
                val np by nowPlaying.collectAsState()
                val expanded by uiExpanded.collectAsState()
                val geo by geometry.collectAsState()
                val appsUi by apps.collectAsState()
                IslandRoot(np, geo, expanded, appsUi, actions)
            }
        }
        root.addView(
            compose,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)

        val (w, h) = collapsedSize()
        val lp = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = geometry.value.yPx
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "DynamicIsland"
        }
        params = lp
        frame = root
        if (service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            root.visibility = View.GONE
        }
        windowManager.addView(root, lp)
    }

    private fun setWindow(expanded: Boolean) {
        val p = params ?: return
        val f = frame ?: return
        val (w, h) = if (expanded) expandedSize() else collapsedSize()
        p.width = w
        p.height = h
        p.y = geometry.value.yPx
        windowExpanded = expanded
        runCatching { windowManager.updateViewLayout(f, p) }
    }

    private fun px(dp: Float) = (dp * service.resources.displayMetrics.density).toInt()

    private fun collapsedSize(): Pair<Int, Int> {
        val g = geometry.value
        val w = if (nowPlaying.value != null) g.compactWidthDp else g.idleWidthDp
        return px(w + 2 * IslandSpec.PAD_DP) to px(g.compactHeightDp + IslandSpec.PAD_DP)
    }

    private fun expandedSize(): Pair<Int, Int> =
        px(geometry.value.expandedWidthDp + 2 * IslandSpec.PAD_DP) to px(IslandSpec.APPS_H_DP + IslandSpec.PAD_DP)

    private fun computeGeometry(): IslandGeometry {
        val density = service.resources.displayMetrics.density
        val screenWdp = service.resources.displayMetrics.widthPixels / density
        val expandedW = minOf(IslandSpec.EXPANDED_W_DP, screenWdp - 24f)
        val cutout = windowManager.maximumWindowMetrics.windowInsets.displayCutout
        val rect = cutout?.boundingRectTop?.takeIf { !it.isEmpty }
        return if (rect != null) {
            val cutoutWdp = rect.width() / density
            val heightDp = (rect.height() / density + 10f).coerceIn(32f, 44f)
            val y = (rect.exactCenterY() - heightDp * density / 2f).toInt().coerceAtLeast(0)
            IslandGeometry(cutoutWdp + 2f * (heightDp + 8f), cutoutWdp + 14f, heightDp, expandedW, y)
        } else {
            IslandGeometry(118f, 64f, 34f, expandedW, (8 * density).toInt())
        }
    }

    private companion object {
        const val PAUSED_IDLE_MS = 30_000L
    }
}
