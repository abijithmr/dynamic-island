# Dynamic Island

A native Android "dynamic island" for the camera cutout.

- Kotlin + Jetpack Compose, `minSdk 30` (Android 11+)
- Package: `com.obsidian.dynamicisland`

## What it does

| State | What you see |
|---|---|
| Idle | A plain black pill hugging the camera cutout |
| Music playing | Compact pill: album art left, equalizer right |
| Tap while music plays | Player: art, title, artist, seek bar, previous / play-pause / next |
| Tap with nothing playing | Panel with your most-used apps. Tap one to open it, swipe up to close |

Long-press the compact pill to open the music app. The island collapses after a few seconds, or when you tap outside it.

## Why an accessibility service

Android draws normal app overlays **below** the status bar, so taps at the cutout go to the status bar instead of the island. An accessibility overlay window sits above the status bar and receives those touches. The service only hosts the window. It does not read the screen (`canRetrieveWindowContent="false"`). It also stays alive on its own, so no foreground service or boot receiver is needed.

## Architecture

| Piece | Implementation |
|---|---|
| Overlay window | `IslandOverlay` adds a `ComposeView` as `TYPE_ACCESSIBILITY_OVERLAY`, hardware accelerated |
| Smooth animation | The window only resizes at the collapsed / expanded endpoints. Everything between them is animated inside Compose (one spring for width, height and radius, read in layout and draw so no recomposition per frame) |
| Now playing | `MediaRepository` watches active sessions (optional Notification access via `MediaListenerService`) |
| Progress bar | Driven per frame with `withFrameNanos`, drawn in the draw phase only |
| Frequent apps | `AppTracker` counts app launches from accessibility window events. Until there is history, recently updated apps fill in |
| Position | Centered on `DisplayCutout.boundingRectTop` |

## Build

```
./gradlew assembleDebug
```

Codemagic builds this on every push to `main` (`codemagic.yaml`).

## Permissions

Only one is required: the **accessibility service**. It hosts the overlay and notices which app comes to the front, so the island can rank your frequent apps (no Usage access needed). Only package names and counts are stored on the device.

Music is optional: Android only gives track info and controls to apps with **notification access**, and an accessibility service can't read them. Turn it on if you want the player.

## First run

1. Accessibility: turn on **Dynamic Island** (on Android 13+ first use App info, three dots, **Allow restricted settings**)
2. Optional: notification access, for music
3. On iQOO, if apps won't open from the island: App info, Battery **Unrestricted**, allow background pop-ups

## Roadmap

- [x] Compact, player and frequent-apps states
- [x] Spring morph, equalizer, accent color from album art, haptic and press feedback
- [ ] Fullscreen / game hiding
- [ ] Settings (position offset, size, collapse time, pinned apps)
- [ ] Auto-expand on track change
