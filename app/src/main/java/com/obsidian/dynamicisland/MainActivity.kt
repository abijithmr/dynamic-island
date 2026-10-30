
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
