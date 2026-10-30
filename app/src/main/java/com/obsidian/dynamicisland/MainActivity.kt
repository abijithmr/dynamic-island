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
import android.widget.Toast
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
import androidx.compose.foundation.layout.weight
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
                onOverlay = { openOverlaySettings() },
                onListener = {
                    safelyOpenSettings(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                        "Could not open notification access settings. Open App info and check special app access."
                    )
                },
                onNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onBattery = {
                    safelyOpenSettings(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                        "Battery optimization settings are not available on this device."
                    )
                },
                onAppInfo = { openAppInfo() },
                onToggle = {
                    if (IslandService.running) {
                        stopService(Intent(this, IslandService::class.java))
                        Prefs.setEnabled(this, false)
                    } else if (!Settings.canDrawOverlays(this)) {
                        openOverlaySettings()
                    } else {
                        Prefs.setEnabled(this, true)
                        IslandService.start(this)
                    }
                    refresh.intValue++
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }

    private fun openOverlaySettings() {
        safelyOpenSettings(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            "Could not open overlay permission settings. Open App info and enable Display over other apps."
        )
    }

    private fun safelyOpenSettings(intent: Intent, error: String) {
        try {
            if (intent.resolveActivity(packageManager) != null) startActivity(intent)
            else Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppInfo() {
        safelyOpenSettings(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            "Could not open app info."
        )
    }

    @Suppress("DEPRECATION")
    private fun readStatus(context: Context): Status {
        val pm = context.getSystemService(PowerManager::class.java)
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        val listenerEnabled = enabledListeners.contains(context.packageName)
        val notificationsEnabled = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return Status(
            overlay = Settings.canDrawOverlays(context),
            listener = listenerEnabled,
            notifications = notificationsEnabled,
            battery = pm.isIgnoringBatteryOptimizations(context.packageName),
            running = IslandService.running,
        )
    }
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
    Column(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 24.dp),
    ) {
        BasicText("DYNAMIC ISLAND", style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(10.dp))
        BasicText("Make your phone feel alive.", style = TextStyle(color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(8.dp))
        BasicText(
            "A compact now-playing island that expands into media controls.",
            style = TextStyle(color = TextMuted, fontSize = 14.sp),
        )
        Spacer(Modifier.height(26.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surface).padding(18.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText("Island service", style = TextStyle(color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
                        BasicText(if (status.running) "Currently active" else "Currently stopped", style = TextStyle(color = TextMuted, fontSize = 13.sp))
                    }
                    Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(if (status.running) Color(0xFF62D69A) else TextMuted)
                    )
                }
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Accent).clickable(onClick = onToggle).padding(vertical = 15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        if (status.running) "Stop Dynamic Island" else "Start Dynamic Island",
                        style = TextStyle(color = Color(0xFF08090B), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        BasicText("REQUIRED ACCESS", style = TextStyle(color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(10.dp))
        AccessRow("Display over other apps", "Allows the island to appear above other apps", status.overlay, onOverlay)
        AccessRow("Notification access", "Reads active media sessions and playback metadata", status.listener, onListener)
        AccessRow("Notifications", "Shows the persistent service notification", status.notifications, onNotifications)
        AccessRow("Battery optimization", "Helps keep playback controls available", status.battery, onBattery)
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface)
                .clickable(onClick = onAppInfo).padding(16.dp)
        ) {
            BasicText("Open app info and special app access", style = TextStyle(color = TextPrimary, fontSize = 14.sp))
        }
        Spacer(Modifier.height(28.dp))
        BasicText("Enable overlay and notification access for the full experience.", style = TextStyle(color = TextMuted, fontSize = 12.sp))
    }
}

@Composable
private fun AccessRow(title: String, description: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(Surface).clickable(onClick = onClick).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            BasicText(description, style = TextStyle(color = TextMuted, fontSize = 12.sp))
        }
        Spacer(Modifier.width(10.dp))
        BasicText(
            if (granted) "Granted" else "Set up",
            style = TextStyle(color = if (granted) Color(0xFF62D69A) else Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold),
        )
    }
}
