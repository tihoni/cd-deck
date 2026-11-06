# Workspace Instructions & Memory: CD/Deck & Android Audio

This repository uses the patterns and architectural findings documented in [MEMORY.md](MEMORY.md).

### Core Rules for this Project:
1. **Toolchain (Zero Android Studio)**:
   - Build via `./build_apk.ps1` or `gradlew assembleDebug --offline --no-daemon`.
   - Never commit `.apk`, `.gradle/`, `.gradle-dist/`, or `.android-sdk/`.
2. **Material 3 Expressive**:
   - Compose BOM `2024.10.00`+, Material 3 `1.4.0-alpha02`+, Kotlin 2.0.0.
   - Wavy Slider strokes: `7.dp` active, `6.dp` inactive.
   - 3D Card flip: `graphicsLayer { rotationY = ...; cameraDistance = 14f * density }` and un-mirror back face with `rotationY = 180f`.
3. **Networking & Sockets**:
   - Dual-socket architecture for MPD (command socket with auto-reconnect retry + dedicated idle socket).
   - **Never** write tag mutations back to MPD over TCP (`addtagid` triggers infinite `idle playlist` loops). Store resolved metadata in local `SharedPreferences`.
4. **Audio & Clock Sync**:
   - Physical drive seeks take 3.5–4.5s. Keep the app scrubber pinned at `0:00` during `isDriveSeeking`.
   - Anchor playback elapsed time to MPD's live `status.elapsed` (polled at 300ms during seeks, 1000ms during play), compensating for ALSA burst-buffer offset.
   - PipeWire HDMI output with WirePlumber `node.pause-on-idle = false` and `suspend-timeout-seconds = 0` keeps TV/AVR HDMI clock locked during track changes.
5. **Hardware TOC & Metadata**:
   - Zero-seeking hardware TOC reader via Linux kernel `ioctl` on `/dev/sr0` in `arm64_mpd_cd_toc_helper.py`.
   - 804-byte ASCII hex SHA-1 DiscID calculation for MusicBrainz & Cover Art Archive queries.
   - LRCLIB for synchronized time-stamped lyrics.
