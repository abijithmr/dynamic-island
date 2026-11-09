package com.obsidian.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.session.MediaSession
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.os.BundleCompat
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

    /** Touch area around the idle pill. The camera hole is tiny, so the target is much bigger. */
    const val IDLE_TOUCH_W_DP = 150f
    const val IDLE_TOUCH_EXTRA_H_DP = 14f
}

/** Size and position of the pill, derived from the camera cutout. */
data class IslandGeometry(
    val compactWidthDp: Float,
    val idleWidthDp: Float,
    val compactHeightDp: Float,
    val idleHeightDp: Float,
    val expandedWidthDp: Float,
    val yPx: Int,
)

class IslandActions(
    val expand: () -> Unit,
    val collapse: () -> Unit,
    val launch: (String) -> Unit,
)

private class OverlayFrame(
    context: Context,
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
class IslandOverlay(private val service: AccessibilityService, tracker: AppTracker) {
    private val windowManager: WindowManager = service.getSystemService(WindowManager::class.java)
    private val owner = OverlayOwner()
    private val repo = MediaRepository(service)
    private val tracker = AppUsageTracker(service)
    private val appsRepo = FrequentApps(service, tracker)
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
    )

    fun show() {
        owner.create()

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
        // Idle needs a different window size than music.
        scope.launch {
            nowPlaying.collectLatest { if (!windowExpanded && frame != null) setWindow(false) }
        }
        addWindow()
        owner.resume()
    }

    fun hide() {
        scope.cancel()
        frame?.let { runCatching { windowManager.removeView(it) } }
        frame = null
        repo.stop()
        owner.destroy()
    }

    /** Fed by the accessibility service. */
    fun onEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val notification = event.parcelableData as? Notification ?: return
                val token = BundleCompat.getParcelable(
                    notification.extras,
                    Notification.EXTRA_MEDIA_SESSION,
                    MediaSession.Token::class.java,
                ) ?: return
                repo.onSessionToken(token)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                tracker.onForeground(pkg)
            }
        }
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
        val playing = nowPlaying.value != null
        val widthDp = if (playing) g.compactWidthDp else maxOf(g.idleWidthDp, IslandSpec.IDLE_TOUCH_W_DP)
        val extraH = if (playing) IslandSpec.PAD_DP else IslandSpec.IDLE_TOUCH_EXTRA_H_DP
        return px(widthDp + 2 * IslandSpec.PAD_DP) to px(g.compactHeightDp + extraH)
    }

    private fun expandedSize(): Pair<Int, Int> =
        px(geometry.value.expandedWidthDp + 2 * IslandSpec.PAD_DP) to
            px(IslandSpec.APPS_H_DP + IslandSpec.PAD_DP + IslandSpec.TOP_OFFSET_DP)

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
            // Idle pill hugs the camera hole so it is hard to spot; the music pill is wider.
            IslandGeometry(
                cutoutWdp + 2f * (heightDp + 8f),
                cutoutWdp + 4f,
                heightDp,
                rect.height() / density + 2f,
                expandedW,
                y,
            )
        } else {
            IslandGeometry(118f, 40f, 34f, 24f, expandedW, (8 * density).toInt())
        }
    }

    private companion object {
        const val PAUSED_IDLE_MS = 30_000L
    }
}
