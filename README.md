# BHUT: Music Player

A personal fork of [Echo](https://github.com/brahmkshatriya/echo) — an extension-based music player for Android — with enhanced Android Auto support, bundled Deezer integration, and various stability and UX improvements.

> **Note:** BHUT is a personal project and is not affiliated with any content providers, streaming services, or the original Echo developers. This application hosts zero content. The user is responsible for managing their own sources and complying with applicable terms of service.

---

## What's Different from Echo

### Android Auto
- Full browse tree support — Home, Search, and Library tabs load correctly per extension
- Search returns real results from Deezer
- Voice search support via Google Assistant ("Hey Google, play X on BHUT")
- Queue view in Now Playing screen
- Shuffle and Repeat buttons in Now Playing controls
- Auto-pause when disconnecting from car
- Playback resumption when reconnecting
- Auto-skip unavailable/region-locked tracks with circuit breaker
- Proper error messages instead of silent failures
- Fixed browse breaking when playback initiated from phone
- "Playing from" subtitle in Now Playing is tappable (navigates to album/playlist/artist)
- Last played track shown in AA thumbnail instead of "Tap to open"
- Battery optimization exemption prompt on first launch (prevents Android killing the service after ~1.5h idle)
- Fixed race condition where AA resumption overwrote a user-initiated queue

### Bundled Deezer Extension
- Deezer is included out of the box — no separate extension install required
- Spotify catalogue access and account login are built directly into BHUT — no second APK or extension install required
- Additional optional extensions can still be installed on top
- Paginated artist albums, top tracks, and related artists (full catalog, not just first page)

### Audio
- Track Fade — configurable volume fade between tracks (1–12 seconds)
- Accessible directly from Audio Effects sheet while listening
- Fixed audio focus — other players pause when BHUT starts playing, BHUT pauses when others start playing
- Buffering watchdog — 8s retry then skip instead of hanging forever
- Deezer quality fallback fixed: FLAC → 320kbps → 128kbps (was skipping 320)
- Retry loop with backoff for transient Deezer shared server failures
- HTTP 404 CDN errors now retry before skipping

### UI/UX
- Compact Spotify-style context menus (icon left, text right)
- Shuffle Play option in playlist/album context menus
- Voice search microphone in all search bars
- Listening history — clock icon on home screen shows recently played tracks
- Confirmation dialog before removing a playlist from library
- Bookmark icon hidden for playlists you own (can't be unsaved)
- Fullscreen album art with Ken Burns pan/zoom animation
- Fixed Lyrics/Queue tab overlap in player
- Fixed fullscreen album art requiring two back gestures to dismiss
- Approved gold-and-red Makara branding across the launcher, splash screen, and settings
- Tappable 'Playing from' subtitle navigates to album, playlist, or artist

### Stability & Performance
- Fixed StreamableMediaSource race conditions causing play button spinning
- Fixed Bluetooth queue size crash on large playlists
- Fixed foreground service startup timing crashes
- Fixed PlayerInfo$Builder crash triggered by Bluetooth volume buttons
- Fixed second track hang after Bluetooth reconnect
- Fixed recreate() infinite loop with dynamic player colors
- Fixed WeakHashMap GC bug causing intermittent empty playlists in Android Auto
- Replaced unsafe concurrent map access with thread-safe alternatives
- Fixed coroutine scope cancellation issues across multiple services
- Fixed Room database schema versioning for listening history
- Faster app startup (deferred ExtensionLoader initialization)
- Fixed infinite STATE_BUFFERING hang on session restore (watchdog + stop/prepare cycle)
- Fixed SocketException / connection reset errors treated as silent skips (not reported as crashes)
- Fixed ExceptionFragment crash when tapping "View" on error toast (screen capture permission)
- Fixed AA resumption race condition using active load tracking

### Crash Reporting
- Firebase Crashlytics integrated for automatic crash reporting

---

## Installation

Build the single BHUT APK using the included GitHub Actions workflow, or install the included locally validated debug APK. You may need to allow installation from unknown sources in your device settings.

**To build from source:**
1. Clone this repository
2. Open in Android Studio
3. Build and run on your Android device

To install optional extensions, download their `.eapk` files from the Echo community and open them on your device — BHUT will offer to install them. Spotify does not require this step.

### Recommended Extensions

**[Last.fm Scrobbler](https://github.com/rebelonion/echo-lastfm)** — Scrobbles your listening history to Last.fm in real time. Install the `.eapk` from that repo, open it on your device, and sign in with your Last.fm account. Once connected, every track you play in BHUT is logged to your Last.fm profile automatically.

**[EchoDown](https://github.com/LuftVerbot/echo-echodown-extension)** — Adds download capability to BHUT. Once installed, open any track, album, or playlist, tap the menu, and select Download. Supports quality selection and tags downloads with artist, album, and lyrics metadata. Install code: `echodown`.


---

## Credits

BHUT is built on top of [Echo](https://github.com/brahmkshatriya/echo) by [brahmkshatriya](https://github.com/brahmkshatriya). All core architecture, extension system, and base functionality are their work. Please support the original project.

The bundled Deezer extension is based on [echo-deezer-extension](https://github.com/LuftVerbot/echo-deezer-extension) by LuftVerbot.

---

## Disclaimer

BHUT is intended for personal use only. The developer is not liable for any misuse or legal issues arising from its use. This application hosts zero content — all content is sourced from user-configured extensions and external services.
