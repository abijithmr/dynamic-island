package com.obsidian.dynamicisland

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat

private val Bg = Color(0xFF08090B)
private val Surface = Color(0xFF14161A)
private val Accent = Color(0xFF8FB4FF)
private val TextPrimary = Color(0xFFECEEF2)
private val TextMuted = Color(0xFF8D93A0)

private data class Status(
    val accessibility: Boolean,
    val listener: Boolean,
    val usage: Boolean,
    val overlay: Boolean,
)

class MainActivity : ComponentActivity() {
    private val refresh = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val tick = refresh.intValue
            val status = remember(tick) { readStatus(this) }
            Screen(
                status = status,
                onAccessibility = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onListener = { openListenerSettings() },
                onUsage = { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                onOverlay = { openOverlaySettings() },
                onAppInfo = { openAppInfo() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }

    private fun open(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess

    private fun openAppInfo() {
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openOverlaySettings() {
        if (!open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) &&
            !open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        ) {
            openAppInfo()
        }
    }

    private fun openListenerSettings() {
        val component = ComponentName(this, MediaListenerService::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        if (!open(detail) && !open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) openAppInfo()
    }
}

private fun readStatus(context: Context): Status {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    val accessibility = enabled.split(':').any {
        val c = ComponentName.unflattenFromString(it)
        c != null &&
            c.packageName == context.packageName &&
            c.className == IslandAccessibilityService::class.java.name
    }
    return Status(
        accessibility = accessibility,
        listener = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
        usage = UsageAccess.isGranted(context),
        overlay = Settings.canDrawOverlays(context),
    )
}

@Composable
private fun Screen(
    status: Status,
    onAccessibility: () -> Unit,
    onListener: () -> Unit,
    onUsage: () -> Unit,
    onOverlay: () -> Unit,
    onAppInfo: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 28.dp),
    ) {
        BasicText(
            "Dynamic Island",
            style = TextStyle(color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            "What's playing, at your camera cutout.",
            style = TextStyle(color = TextMuted, fontSize = 15.sp),
        )
        Spacer(Modifier.height(24.dp))

        StatusBanner(status.accessibility)
        Spacer(Modifier.height(20.dp))

        StepCard(
            "Accessibility service",
            "Required. Draws the island above the status bar so taps reach it. Open Accessibility, find Dynamic Island under Installed apps, and turn it on.",
            status.accessibility, "Open", onAccessibility,
        )
        Spacer(Modifier.height(12.dp))
        StepCard(
            "Notification access",
            "Required for music. Lets the island read what's playing and control it.",
            status.listener, "Allow", onListener,
        )
        Spacer(Modifier.height(12.dp))
        StepCard(
            "Usage access",
            "Optional. Shows your most-used apps when nothing is playing.",
            status.usage, "Allow", onUsage,
        )
        Spacer(Modifier.height(12.dp))
        StepCard(
            "Display over other apps",
            "Optional. Helps the island open apps from the background on some phones.",
            status.overlay, "Allow", onOverlay,
        )
        Spacer(Modifier.height(12.dp))
        StepCard(
            "Switch greyed out or blocked?",
            "Open App info, tap the three dots at the top right, choose Allow restricted settings, then try again.",
            false, "App info", onAppInfo,
        )
        Spacer(Modifier.height(12.dp))
        StepCard(
            "iQOO / Funtouch OS",
            "In App info, set Battery to Unrestricted and allow pop-ups while running in the background, so apps can open from the island.",
            false, "App info", onAppInfo,
        )
    }
}

@Composable
private fun StatusBanner(active: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(if (active) Accent else Surface)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                if (active) "Island is running" else "Island is off",
                style = TextStyle(
                    color = if (active) Color.Black else TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(3.dp))
            BasicText(
                if (active) {
                    "Play music, or tap the island with nothing playing. To stop it, turn the service off in Accessibility."
                } else {
                    "Turn on the accessibility service below to start."
                },
                style = TextStyle(color = if (active) Color(0xFF1B2433) else TextMuted, fontSize = 13.sp),
            )
        }
    }
}

@Composable
private fun StepCard(title: String, body: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (done) Accent else Color(0xFF3A3E47)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(3.dp))
            BasicText(body, style = TextStyle(color = TextMuted, fontSize = 13.sp))
        }
        Spacer(Modifier.width(12.dp))
        if (!done) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF23262D))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                BasicText(action, style = TextStyle(color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}
