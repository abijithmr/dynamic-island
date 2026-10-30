package com.obsidian.dynamicisland

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
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
    val overlay: Boolean,
    val listener: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
    val running: Boolean,
)

class MainActivity : ComponentActivity() {
    private val refresh = mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val tick = refresh.intValue
            val status = remember(tick) { readStatus(this) }
            Screen(
                status = status,
                onOverlay = {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    )
                },
                onListener = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onBattery = {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                        )
                    }
                },
                onAppInfo = {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                    )
                },
                onToggle = {
                    if (status.running) IslandService.stop(this) else IslandService.start(this)
                    refresh.intValue++
                    // The service starts asynchronously, so check again shortly after.
                    window.decorView.postDelayed({ refresh.intValue++ }, 500)
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }
}

private fun readStatus(context: Context): Status {
    val pm = context.getSystemService(PowerManager::class.java)
    return Status(
        overlay = Settings.canDrawOverlays(context),
        listener = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
        notifications = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        battery = pm.isIgnoringBatteryOptimizations(context.packageName),
        running = IslandService.running,
    )
}

@Composable
private fun Screen(
    status: Status,
    onOverlay: () -> Unit,
    onListener: () -> Unit,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
    onAppInfo: () -> Unit,
    onToggle: () -> Unit,
) {
    val ready = status.overlay && status.listener
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
        Spacer(Modifier.height(28.dp))

        StepCard("Display over other apps", "Lets the island draw above every app.", status.overlay, "Allow", onOverlay)
        Spacer(Modifier.height(12.dp))
        StepCard("Notification access", "Lets the island read what's playing and control it.", status.listener, "Allow", onListener)
        Spacer(Modifier.height(12.dp))
        StepCard("Notifications", "Shows the small \"running\" notification Android requires.", status.notifications, "Allow", onNotifications)
        Spacer(Modifier.height(12.dp))
        StepCard("Keep running in background", "Stops battery saver from closing the island.", status.battery, "Exempt", onBattery)
        Spacer(Modifier.height(12.dp))
        StepCard("Auto-start (iQOO / Funtouch OS)", "In App info, open Battery or Autostart and allow background activity.", false, "App info", onAppInfo)

        Spacer(Modifier.height(28.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(if (status.running) Surface else if (ready) Accent else Surface)
                .clickable(enabled = ready || status.running, onClick = onToggle)
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                if (status.running) "Stop island" else "Start island",
                style = TextStyle(
                    color = if (status.running) TextPrimary else if (ready) Color.Black else TextMuted,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
        if (!ready && !status.running) {
            Spacer(Modifier.height(10.dp))
            BasicText(
                "Allow display over other apps and notification access to start.",
                style = TextStyle(color = TextMuted, fontSize = 13.sp),
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
