package com.obsidian.dynamicisland.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.obsidian.dynamicisland.AppEntry
import com.obsidian.dynamicisland.AppsUi
import com.obsidian.dynamicisland.IslandActions
import com.obsidian.dynamicisland.IslandGeometry
import com.obsidian.dynamicisland.IslandSpec
import com.obsidian.dynamicisland.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val DefaultAccent = Color(0xFF8FB4FF)

private enum class Mode { Idle, Music, MusicOpen, AppsOpen }

/** One spring for every size change, so width, height and radius move as a single gesture. */
private fun <T> morph(): SpringSpec<T> = spring(dampingRatio = 0.78f, stiffness = 420f)

/**
 * Entry point of the overlay.
 *
 * - Idle: a plain black pill hugging the camera cutout
 * - Music: compact pill with art and equalizer; tap to open the player
 * - Nothing playing + tap: panel with your most-used apps
 */
@Composable
fun IslandRoot(
    np: NowPlaying?,
    geo: IslandGeometry,
    expanded: Boolean,
    apps: AppsUi,
    actions: IslandActions,
) {
    // Keep the last track around so the player can fade out instead of vanishing.
    var last by remember { mutableStateOf(np) }
    LaunchedEffect(np) { if (np != null) last = np }
    val shown = np ?: last

    val mode = when {
        expanded && np != null -> Mode.MusicOpen
        expanded -> Mode.AppsOpen
        np != null -> Mode.Music
        else -> Mode.Idle
    }

    var interaction by remember { mutableIntStateOf(0) }
    LaunchedEffect(expanded, interaction, np == null) {
        if (expanded) {
            delay(if (np != null) 6_000L else 9_000L)
            actions.collapse()
        }
    }

    val accent = rememberAccent(shown?.art)
    val view = LocalView.current
    val t = updateTransition(mode, label = "island")

    // Animated values are read in layout/draw only, so a frame of motion never recomposes the tree.
    val width = t.animateDp({ morph() }, label = "w") { m ->
        when (m) {
            Mode.Idle -> geo.idleWidthDp.dp
            Mode.Music -> geo.compactWidthDp.dp
            else -> geo.expandedWidthDp.dp
        }
    }
    val height = t.animateDp({ morph() }, label = "h") { m ->
        when (m) {
            Mode.Idle, Mode.Music -> geo.compactHeightDp.dp
            Mode.MusicOpen -> IslandSpec.MUSIC_H_DP.dp
            Mode.AppsOpen -> IslandSpec.APPS_H_DP.dp
        }
    }
    val radius = t.animateDp({ morph() }, label = "r") { m ->
        when (m) {
            Mode.Idle, Mode.Music -> (geo.compactHeightDp / 2f).dp
            else -> 34.dp
        }
    }
    val compactAlpha = t.contentAlpha(Mode.Music)
    val musicAlpha = t.contentAlpha(Mode.MusicOpen)
    val appsAlpha = t.contentAlpha(Mode.AppsOpen)

    var pressed by remember { mutableStateOf(false) }
    val press = animateFloatAsState(
        targetValue = if (pressed && !expanded) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "press",
    )

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(expanded, np) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = {
                        if (!expanded) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            actions.expand()
                        }
                    },
                    onLongPress = { if (!expanded) np?.let { openSource(it) } },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .layout { measurable, _ ->
                    val w = width.value.roundToPx()
                    val h = height.value.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    shape = RoundedCornerShape(radius.value.toPx())
                    clip = true
                    scaleX = press.value
                    scaleY = press.value
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .background(Color.Black),
        ) {
            if (shown != null && (mode == Mode.Music || compactAlpha.value > 0.001f)) {
                CompactContent(
                    shown, accent, geo,
                    Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = compactAlpha.value },
                )
            }
            if (shown != null && (mode == Mode.MusicOpen || musicAlpha.value > 0.001f)) {
                MusicPanel(
                    shown, accent, geo,
                    Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = musicAlpha.value },
                    bump = { interaction++ },
                    onOpenSource = { openSource(it); actions.collapse() },
                )
            }
            if (mode == Mode.AppsOpen || appsAlpha.value > 0.001f) {
                AppsPanel(
                    apps, geo,
                    Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = appsAlpha.value },
                    onLaunch = actions.launch,
                    onGrantUsage = actions.grantUsage,
                )
            }
        }
    }
}

/** Content fades in after the shape has started moving, and out before it shrinks. */
@Composable
private fun Transition<Mode>.contentAlpha(show: Mode): State<Float> =
    animateFloat(
        transitionSpec = { if (targetState == show) tween(240, delayMillis = 110) else tween(110) },
        label = "alpha",
    ) { if (it == show) 1f else 0f }

@Composable
private fun CompactContent(np: NowPlaying, accent: Color, geo: IslandGeometry, modifier: Modifier) {
    val h = geo.compactHeightDp.dp
    Row(
        modifier
            .requiredSize(geo.compactWidthDp.dp, h)
            .padding(horizontal = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(np.art, h - 14.dp, 6.dp)
        Equalizer(np.isPlaying, accent)
    }
}

@Composable
private fun MusicPanel(
    np: NowPlaying,
    accent: Color,
    geo: IslandGeometry,
    modifier: Modifier,
    bump: () -> Unit,
    onOpenSource: (NowPlaying) -> Unit,
) {
    val controls = np.controller.transportControls
    Column(
        modifier
            .requiredSize(geo.expandedWidthDp.dp, IslandSpec.MUSIC_H_DP.dp)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                np.art, 56.dp, 14.dp,
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onOpenSource(np) },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                BasicText(
                    np.title,
                    style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (np.artist.isNotEmpty()) {
                    BasicText(
                        np.artist,
                        style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Equalizer(np.isPlaying, accent)
        }
        Spacer(Modifier.height(12.dp))
        SeekBar(np, accent, bump)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PressBox(Modifier.size(44.dp), np.canPrev, onClick = { bump(); controls.skipToPrevious() }) {
                SkipIcon(false, Color.White.copy(alpha = if (np.canPrev) 1f else 0.35f))
            }
            Spacer(Modifier.width(26.dp))
            PressBox(
                Modifier.size(48.dp).clip(CircleShape).background(Color.White),
                true,
                onClick = {
                    bump()
                    if (np.isPlaying) controls.pause() else controls.play()
                },
            ) {
                PlayPauseIcon(np.isPlaying, Color.Black)
            }
            Spacer(Modifier.width(26.dp))
            PressBox(Modifier.size(44.dp), np.canNext, onClick = { bump(); controls.skipToNext() }) {
                SkipIcon(true, Color.White.copy(alpha = if (np.canNext) 1f else 0.35f))
            }
        }
    }
}

@Composable
private fun AppsPanel(
    apps: AppsUi,
    geo: IslandGeometry,
    modifier: Modifier,
    onLaunch: (String) -> Unit,
    onGrantUsage: () -> Unit,
) {
    Column(
        modifier
            .requiredSize(geo.expandedWidthDp.dp, IslandSpec.APPS_H_DP.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        BasicText(
            "Frequent apps",
            style = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.height(10.dp))
        when (apps) {
            AppsUi.Loading -> Hint("Loading…")
            AppsUi.NoAccess -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(
                        "Allow usage access to see the apps you use most.",
                        style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center),
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onGrantUsage,
                            )
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        BasicText("Allow", style = TextStyle(color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
            is AppsUi.Ready ->
                if (apps.apps.isEmpty()) {
                    Hint("Use your phone a little and your most-used apps will show up here.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        apps.apps.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                repeat(4) { i ->
                                    val app = row.getOrNull(i)
                                    if (app != null) AppTile(app, onLaunch) else Spacer(Modifier.width(72.dp))
                                }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(
            text,
            style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun AppTile(app: AppEntry, onLaunch: (String) -> Unit) {
    Column(
        Modifier
            .width(72.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onLaunch(app.packageName) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            app.icon,
            app.label,
            Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            app.label,
            style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SeekBar(np: NowPlaying, accent: Color, bump: () -> Unit) {
    val current by rememberUpdatedState(np)
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    // One state write per frame while playing. Only the bar's draw lambda reads it, so the
    // bar glides without recomposing anything.
    LaunchedEffect(np.isPlaying, np.positionMs, np.updateTimeMs) {
        nowMs = SystemClock.elapsedRealtime()
        while (np.isPlaying) {
            withFrameNanos { }
            nowMs = SystemClock.elapsedRealtime()
        }
    }

    val duration = np.durationMs
    val seekable = np.canSeek && duration > 0
    var drag by remember { mutableStateOf<Float?>(null) }
    val seconds by remember { derivedStateOf { current.positionAt(nowMs) / 1000 } }
    val shownSeconds = drag?.let { (it * duration / 1000).toLong() } ?: seconds

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(seekable, duration) {
                    if (seekable) {
                        detectTapGestures { o ->
                            bump()
                            val target = (o.x / size.width * duration).toLong().coerceIn(0L, duration)
                            current.controller.transportControls.seekTo(target)
                        }
                    }
                }
                .pointerInput(seekable, duration) {
                    if (seekable) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = {
                                drag?.let { current.controller.transportControls.seekTo((it * duration).toLong()) }
                                drag = null
                            },
                            onDragCancel = { drag = null },
                            onHorizontalDrag = { change, _ ->
                                bump()
                                drag = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
                    .drawBehind {
                        val f = drag
                            ?: if (duration > 0) (current.positionAt(nowMs).toFloat() / duration).coerceIn(0f, 1f) else 0f
                        drawRect(accent, size = Size(size.width * f, size.height))
                    },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val label = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
            BasicText(formatTime(shownSeconds * 1000), style = label)
            BasicText(if (duration > 0) formatTime(duration) else "--:--", style = label)
        }
    }
}

@Composable
private fun PressBox(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun Artwork(bitmap: Bitmap?, size: Dp, radius: Dp, modifier: Modifier = Modifier) {
    val base = modifier.size(size).clip(RoundedCornerShape(radius))
    if (bitmap != null) {
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        Image(image, null, base, contentScale = ContentScale.Crop)
    } else {
        Box(base.background(Color(0xFF23262D)))
    }
}

@Composable
private fun Equalizer(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val b0 by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b0")
    val b1 by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(380, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b1")
    val b2 by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(610, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b2")
    val b3 by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b3")
    val level by animateFloatAsState(if (active) 1f else 0f, tween(300), label = "level")

    Canvas(modifier.size(22.dp, 16.dp)) {
        val bars = floatArrayOf(b0, b1, b2, b3)
        val gap = size.width * 0.12f
        val barW = (size.width - gap * 3f) / 4f
        bars.forEachIndexed { i, v ->
            val frac = 0.22f + (v - 0.22f) * level
            val bh = size.height * frac
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gap), size.height - bh),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

@Composable
private fun SkipIcon(forward: Boolean, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val triangle = Path().apply {
            if (forward) {
                moveTo(0f, 0f); lineTo(w * 0.72f, h / 2f); lineTo(0f, h)
            } else {
                moveTo(w * 0.28f, h / 2f); lineTo(w, 0f); lineTo(w, h)
            }
            close()
        }
        drawPath(triangle, tint)
        drawRoundRect(
            color = tint,
            topLeft = Offset(if (forward) w * 0.82f else 0f, 0f),
            size = Size(w * 0.18f, h),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
}

@Composable
private fun PlayPauseIcon(playing: Boolean, tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        if (playing) {
            val barW = w * 0.3f
            drawRoundRect(tint, Offset(w * 0.1f, 0f), Size(barW, h), CornerRadius(2.dp.toPx()))
            drawRoundRect(tint, Offset(w * 0.6f, 0f), Size(barW, h), CornerRadius(2.dp.toPx()))
        } else {
            val p = Path().apply {
                moveTo(w * 0.15f, 0f); lineTo(w, h / 2f); lineTo(w * 0.15f, h); close()
            }
            drawPath(p, tint)
        }
    }
}

/** Accent color pulled from the album art, brightened if it would vanish on black. */
@Composable
private fun rememberAccent(bitmap: Bitmap?): Color {
    val state = produceState(DefaultAccent, bitmap) {
        value = if (bitmap == null) {
            DefaultAccent
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    val p = Palette.from(bitmap).maximumColorCount(12).generate()
                    val rgb = p.getVibrantColor(p.getLightVibrantColor(p.getDominantColor(0xFF8FB4FF.toInt())))
                    val c = Color(rgb).copy(alpha = 1f)
                    if (c.luminance() < 0.25f) lerp(c, Color.White, 0.45f) else c
                }.getOrDefault(DefaultAccent)
            }
        }
    }
    return state.value
}

private fun openSource(np: NowPlaying) {
    runCatching { np.controller.sessionActivity?.send() }
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
