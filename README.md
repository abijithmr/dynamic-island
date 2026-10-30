# Dynamic Island

A native Android "dynamic island" for the camera cutout. Phase 1 scope: the music that is currently playing.

- Kotlin + Jetpack Compose, `minSdk 30` (Android 11+)
- Package: `com.obsidian.dynamicisland`
- Reads any media app (Spotify, YouTube Music, local players) through `MediaSessionManager`

## How it works

| Piece | Implementation |
|---|---|
| Overlay window | `IslandService` adds a `ComposeView` with `TYPE_APPLICATION_OVERLAY`, wrapped to content so touches outside the pill reach the app underneath |
| Now playing | `MediaRepository` watches active sessions (needs Notification Access via `MediaListenerService`) |
| Controls | `MediaController.transportControls` (play/pause, previous, next, seek) |
| Animation | Spring morph of width, height and corner radius, content cross-fade, equalizer, accent color from album art (Palette) |
| Position | Centered on the display cutout reported by `DisplayCutout.boundingRectTop` |
| Keep-alive | Foreground service, battery-exemption prompt, boot receiver |

## States

- **Idle:** hidden (no media, or paused for more than 15 s)
- **Compact:** album art left, equalizer right. Tap to expand, long-press to open the media app
- **Expanded:** art, title, artist, seek bar, previous / play-pause / next. Collapses after 6 s, or when you tap outside

## Build

```
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Codemagic builds the same on every push to `main` (`codemagic.yaml`).

## First run

1. Allow **Display over other apps**
2. Allow **Notification access** for Dynamic Island
3. Tap **Start island**, then play something

On iQOO / Funtouch OS also allow auto-start and unrestricted battery use in App info.

## Roadmap

- [x] Permissions onboarding
- [x] Compact and expanded views with controls and seek
- [x] Spring animations, equalizer, accent color
- [ ] Fullscreen / game detection (Accessibility Service)
- [ ] Settings (position offset, size, auto-collapse time)
- [ ] Auto-expand on track change
