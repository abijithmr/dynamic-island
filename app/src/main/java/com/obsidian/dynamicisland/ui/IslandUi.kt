package com.obsidian.dynamicisland.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.obsidian.dynamicisland.IslandGeometry
import com.obsidian.dynamicisland.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val DefaultAccent = Color(0xFF8FB4FF)
private val ExpandedW = 360.dp
private val ExpandedH = 190.dp

/** Entry point of the overlay. Renders nothing when no media session is active. */
@Composable
fun IslandRoot(
    np: NowPlaying?,
    geo: IslandGeometry,
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
) {
    var pausedTooLong by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }

    // Hide the island after 15 s of pause, and bring it back the moment playback resumes.
    LaunchedEffect(np?.isPlaying, np?.title) {
        pausedTooLong = false
        if (np != null && !np.isPlaying) {
            delay(15_000)
            pausedTooLong = true
            setExpanded(false)
        }
    }
    LaunchedEffect(np == null) { if (np == null) setExpanded(false) }
    // Auto-collapse six seconds after the last touch.
    LaunchedEffect(expanded, interaction) {
        if (expanded) {
            delay(6_000)
            setExpanded(false)
        }
    }

    if (np != null && !pausedTooLong) {
        IslandBody(np, geo, expanded, setExpanded) { interaction++ }
    }
}

@Composable
private fun IslandBody(
    np: NowPlaying,
    geo: IslandGeometry,
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
    bump: () -> Unit,
) {
    val accent = rememberAccent(np.art)
    val t = updateTransition(expanded, label = "island")

    // Spring-based morph of width, height and corner radius.
    val width by t.animateDp({ spring(dampingRatio = 0.74f, stiffness = 380f) }, label = "w") {
        if (it) ExpandedW else geo.compactWidthDp.dp
    }
    val height by t.animateDp({ spring(dampingRatio = 0.74f, stiffness = 380f) }, label = "h") {
        if (it) ExpandedH else geo.compactHeightDp.dp
    }
    val radius by t.animateDp({ spring(dampingRatio = 0.8f, stiffness = 380f) }, label = "r") {
        if (it) 34.dp else (geo.compactHeightDp / 2f).dp
    }
    // Content cross-fades instead of snapping.
    val expandedAlpha by t.animateFloat(
        { if (targetState) tween(240, delayMillis = 110) else tween(110) },
        label = "ea",
    ) { if (it) 1f else 0f }
    val compactAlpha by t.animateFloat(
        { if (targetState) tween(110) else tween(220, delayMillis = 120) },
        label = "ca",
    ) { if (it) 0f else 1f }

    Box(
        Modifier
            .size(width, height)
            .clip(RoundedCornerShape(radius))
            .background(Color.Black)
            .pointerInput(expanded) {
                detectTapGestures(
                    onTap = { if (!expanded) setExpanded(true) },
                    onLongPress = { if (!expanded) openSource(np) },
                )
            },
    ) {
        if (compactAlpha > 0.001f) {
            CompactContent(np, accent, geo, Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = compactAlpha })
        }
        if (expandedAlpha > 0.001f) {
            ExpandedContent(np, accent, Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = expandedAlpha }, bump)
        }
    }
}

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
private fun ExpandedContent(np: NowPlaying, accent: Color, modifier: Modifier, bump: () -> Unit) {
    val controls = np.controller.transportControls
    Column(
        modifier
            .requiredSize(ExpandedW, ExpandedH)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                np.art, 56.dp, 14.dp,
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { openSource(np) },
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
private fun SeekBar(np: NowPlaying, accent: Color, bump: () -> Unit) {
    val current by rememberUpdatedState(np)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(np.isPlaying, np.positionMs, np.updateTimeMs) {
        now = SystemClock.elapsedRealtime()
        while (np.isPlaying) {
            delay(250)
            now = SystemClock.elapsedRealtime()
        }
    }

    val duration = np.durationMs
    val seekable = np.canSeek && duration > 0
    var drag by remember { mutableStateOf<Float?>(null) }
    val position = np.positionAt(now)
    val fraction = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownPosition = drag?.let { (it * duration).toLong() } ?: position

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
                Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
            )
            Box(
                Modifier.align(Alignment.CenterStart)
                    .fillMaxWidth(fraction).height(4.dp).clip(CircleShape)
                    .background(accent),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val label = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
            BasicText(formatTime(shownPosition), style = label)
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
