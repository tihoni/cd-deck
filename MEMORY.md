# Android & Headless Audio App Development Playbook (Memory & Lessons Learned)

> **Document Purpose**: Architectural blueprints, hard-won lessons, debugging findings, and reusable patterns from building **CD/Deck**—a standalone Material 3 Expressive Android application controlling a headless physical CD player running on Music Player Daemon (MPD) with MusicBrainz DiscID recognition, Cover Art Archive, and synchronized LRCLIB lyrics.

---

## 1. Studio-less CLI Android Build Toolchain

You do **not** need Android Studio installed to develop, compile, or package production/debug APKs.

### Architecture
- **JDK**: Portable OpenJDK 17 (Zulu or Temurin).
- **Gradle**: Portable Gradle 8.9 (`.gradle-dist`).
- **Android SDK Command-Line Tools**:
  - `cmdline-tools/latest/bin/sdkmanager.bat`
  - Required components: `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`.
- **`local.properties`**:
  ```properties
  sdk.dir=D\:\\Antigravity\\HA CD Player\\.android-sdk
  ```

### Key Build Flags & Automation (`build_apk.ps1`)
```powershell
# Fast, reproducible local CLI builds:
& "$GRADLE_BIN" assembleDebug --offline --no-daemon -q
```
- `--offline`: Prevents Gradle from re-querying Maven Central / Google repositories once cache is populated.
- `--no-daemon`: Crucial for CLI/agent environments to prevent orphan Java daemon processes from holding file locks on Windows.
- Always output the completed APK to a known root path (e.g., `CDDeck-debug.apk`) for immediate ADB install or phone transfer.

---

## 2. Material 3 Expressive UI & Jetpack Compose Patterns

### Component Versioning
- Material 3 Expressive components require:
  - Compose BOM: `2024.10.00`+
  - Material 3: `androidx.compose.material3:material3:1.4.0-alpha02` or higher.
  - Kotlin Compose Compiler Plugin: Kotlin 2.0.0+.

### Thick Wavy Scrubber / Progress Bar
- Standard `Slider` or `LinearProgressIndicator` can be replaced with custom Canvas drawing or M3 Wavy Slider:
  - Active wavy stroke: `7.dp`
  - Inactive straight stroke: `6.dp`
  - Calculate wave paths using sine functions: `y = sin((x / wavelength + phase) * 2 * PI) * amplitude`.
  - Drive phase animation via `rememberInfiniteTransition().animateFloat(0f, 1f, tween(1200, easing = LinearEasing))`.

### 3D Flippable Album Card (Cover Art <-> Synced Lyrics)
- Use `Modifier.graphicsLayer` with 3D projection:
  ```kotlin
  val flipRotationY by animateFloatAsState(
      targetValue = if (showLyrics) 180f else 0f,
      animationSpec = tween(durationMillis = 480),
      label = "cardFlip"
  )

  Box(
      modifier = modifier.graphicsLayer {
          rotationY = flipRotationY
          cameraDistance = 14f * density // Prevents perspective distortion
      }
  ) {
      if (flipRotationY <= 90f) {
          // FRONT: Cover Art
      } else {
          // BACK: Lyrics Face (MUST un-mirror)
          Box(modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
              ScrollableLyricsFace(...)
          }
      }
  }
  ```

### Ambient Dynamic Lighting from Cover Art
- Extract dominant colors from Coil image bitmaps using AndroidX Palette (`androidx.palette:palette-ktx`):
  ```kotlin
  Palette.from(bitmap).generate { palette ->
      val rgb = palette?.getDominantColor(0xFF18191E.toInt()) ?: 0xFF18191E.toInt()
      onDominantColorExtracted(Color(rgb))
  }
  ```
- Animate ambient background gradients smoothly using `animateColorAsState(targetColor, tween(600))`.

---

## 3. Low-Latency Network Sockets & Daemon Protocols (MPD)

### Dual-Socket Architecture
When interfacing with TCP daemons like MPD (port 6600):
1. **Command Socket**:
   - Mutex-guarded (`commandMutex.withLock`).
   - Short read timeout (`12_000ms`).
   - **Crucial Lesson**: MPD drops idle non-`idle` connections after 60 seconds of inactivity. **Always implement auto-reconnect retry**:
     ```kotlin
     for (attempt in 1..2) {
         try {
             if (commandSocket?.isConnected != true || commandSocket?.isClosed == true) {
                 openCommandSocketLocked(host, port)
             }
             // write & read ...
             return Result.success(...)
         } catch (e: Exception) {
             closeCommandSocket()
             if (attempt == 2) throw e
         }
     }
     ```
     This eliminates the common bug where user taps (e.g. playlist selection) are dropped after a pause.
2. **Idle Socket**:
   - Long-lived dedicated socket running blocking `idle player playlist options\n`.
   - Never send commands over the idle socket while waiting for events.

### CRITICAL: The `idle` Tag Mutation Feedback Loop
- **The Bug**: Calling `addtagid <id> Title "..."` in response to playlist snapshots caused MPD to bump its internal `playlist: N` version and emit `changed: playlist` over the idle socket. The app received `changed: playlist`, refreshed its snapshot, and called `addtagid` again. Result: **`playlist: 2922` within 5 minutes** and duplicate tag arrays of 4,000+ lines.
- **The Rule**: **Never write metadata tags back into MPD's queue over TCP**. Keep MPD tags read-only on the client side. Persist resolved album and track metadata locally in Android `SharedPreferences` (keyed by track count / disc signature).

---

## 4. Hardware Audio Clock, HDMI Handshake & Timing Sync

### The Root Cause of Clipped Starts & Rushing Timers
When playing physical optical media (`cdda://`) or switching digital audio outputs:
1. **Physical Seek Latency**: Moving the laser head across a CD takes **3.5 to 4.5 seconds**. During this time, MPD reports `state: play`, but `elapsed: 0.000`.
2. **ALSA Output Underrun**: If direct ALSA (`hw:X,Y`) is used without keep-alive, the PCM stream closes during the seek. The HDMI TV/AVR drops its HDMI audio clock lock.
3. **HDMI Re-Handshake**: When audio packets resume, HDMI takes **1 to 2 seconds** to re-acquire clock lock, muting the first notes of the track.
4. **Burst Buffer Pre-fill**: Once the drive finishes seeking, MPD bursts **~1.35 seconds** of audio into the output buffer (`elapsed` instantly leaps from `0.000` to `1.368` before sound exits the speakers).

### The Fix: PipeWire Keep-Alive + Status Anchoring
1. **PipeWire HDMI Keep-Alive**:
   Route MPD through PipeWire (`type "pipewire"` in `/etc/mpd.conf`) and configure WirePlumber (`51-hdmi-no-suspend.conf`):
   ```conf
   monitor.alsa.rules = [
     {
       matches = [ { node.name = "~alsa_output.*hdmi*" } ]
       actions = {
         update-props = {
           session.suspend-timeout-seconds = 0
           node.pause-on-idle = false
         }
       }
     }
   ]
   ```
   PipeWire continuously feeds silent zero-frames to HDMI during optical seeks, keeping the TV/AVR audio clock locked.
2. **Client-Side Seek Hold**:
   - Keep the app scrubber pinned at `0:00` while `isDriveSeeking = true`.
   - Poll `status` at **300ms** during seeks. Only release the UI counter once MPD reports `elapsed > 0.05s`.
   - Subtract the measured ALSA burst offset (`~1.35s`) so the UI timer and synced lyrics match the sound exiting the speakers.
   - Provide microsecond lyrics timing offset adjustment pills (`-0.5s` / `+0.5s`) in the UI to allow users to trim TV-specific post-processing latency.

---

## 5. Physical CD Redbook TOC & MusicBrainz DiscID

### Zero-Head-Seeking Hardware TOC Reader
MPD's `cdio_paranoia` (`cdda://`) plugin exposes tracks as `cdda:///1` to `cdda:///N` without sector offsets or track lengths. Standard CD scanning that seeks each track destroys hardware and takes 30+ seconds.

**The Solution (`arm64_mpd_cd_toc_helper.py`)**:
- Read the CD Table of Contents directly from Linux optical drive controller RAM via `ioctl` in **~2 milliseconds**:
  ```python
  import fcntl, struct

  CDROMREADTOCHDR = 0x5305
  CDROMREADTOCENTRY = 0x5306
  CDROM_LBA = 0x01
  CDROM_LEADOUT = 0xAA

  fd = os.open("/dev/sr0", os.O_RDONLY | os.O_NONBLOCK)
  hdr = fcntl.ioctl(fd, CDROMREADTOCHDR, struct.pack("BB", 0, 0))
  first_track, last_track = struct.unpack("BB", hdr)
  ```
- Format standard MusicBrainz TOC: `1 <last_track> <leadout_sector> <offset1> ... <offsetN>`.

### Computing MusicBrainz DiscID in Kotlin
```kotlin
fun computeMusicBrainzDiscId(
    firstTrack: Int,
    lastTrack: Int,
    leadoutSector: Int,
    offsets: List<Int>
): String {
    val sb = StringBuilder(804)
    sb.append(String.format(Locale.US, "%02X", firstTrack and 0xFF))
    sb.append(String.format(Locale.US, "%02X", lastTrack and 0xFF))
    sb.append(String.format(Locale.US, "%08X", leadoutSector))
    for (i in 0 until 99) {
        val offset = offsets.getOrNull(i) ?: 0
        sb.append(String.format(Locale.US, "%08X", offset))
    }
    val sha1 = MessageDigest.getInstance("SHA-1")
        .digest(sb.toString().toByteArray(Charsets.US_ASCII))
    return Base64.encodeToString(sha1, Base64.NO_WRAP)
        .replace('+', '.')
        .replace('/', '_')
        .replace('=', '-')
}
```

### MusicBrainz & Cover Art Archive APIs
- **DiscID Endpoint**: `https://musicbrainz.org/ws/2/discid/<discId>?inc=recordings+artist-credits&fmt=json`
- **Fuzzy TOC Fallback**: `https://musicbrainz.org/ws/2/discid/-?toc=1+<N>+<leadout>+<offsets...>&fmt=json`
- **Cover Art Archive**: `https://coverartarchive.org/release/<releaseMbid>/front-500`
- **Rate Limiting**: Strictly enforce a `1,000ms` mutex interval between MusicBrainz queries, and include a meaningful `User-Agent`.

---

## 6. Time-Synced Lyrics via LRCLIB

### API Specification
- **Endpoint**: `https://lrclib.net/api/get`
- **Query Parameters**: `artist_name`, `track_name`, `album_name`, `duration` (integer seconds).
- **Format**:
  - `syncedLyrics`: Standard LRC format `[mm:ss.xx] Lyric text`
  - `plainLyrics`: Fallback un-synced text

### Parsing LRC in Kotlin
```kotlin
val lineRegex = Regex("""^\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)$""")
val match = lineRegex.find(rawLine)
if (match != null) {
    val mins = match.groupValues[1].toInt()
    val secs = match.groupValues[2].toInt()
    val sub = match.groupValues[3]
    val millis = if (sub.length == 2) sub.toInt() * 10 else sub.toInt()
    val totalSec = mins * 60f + secs + (millis / 1000f)
    LyricLine(timestampSec = totalSec, text = match.groupValues[4].trim())
}
```

### Sync & Auto-Scroll
- Find active line: `lines.indexOfLast { (it.timestampSec ?: 0f) <= effectiveTimeSec }`.
- Auto-scroll with animation:
  ```kotlin
  LaunchedEffect(activeLineIndex) {
      if (activeLineIndex >= 0) {
          listState.animateScrollToItem((activeLineIndex - 1).coerceAtLeast(0))
      }
  }
  ```
- Support tap-to-seek on any lyric line by dispatching `onSeekTo(line.timestampSec)`.

---

## 7. Headless Linux & DietPi Service Integration

### Systemd Boot Ordering Drop-in
When headless daemons (like MPD) communicate with PipeWire over `/run/user/0/pipewire-0`, cold boot ordering race conditions must be prevented.

Create `/etc/systemd/system/mpd.service.d/pipewire.conf`:
```ini
[Service]
Environment=XDG_RUNTIME_DIR=/run/user/0
Environment=PIPEWIRE_RUNTIME_DIR=/run/user/0
# Wait up to 30s for the PipeWire socket to appear before launching MPD
ExecStartPre=/bin/sh -c 'for i in $(seq 1 30); do [ -S /run/user/0/pipewire-0 ] && exit 0; sleep 1; done; exit 0'
```

### Python Daemon Systemd Unit
Create `/etc/systemd/system/cddeck-toc.service`:
```ini
[Unit]
Description=CD/Deck MPD MusicBrainz TOC Helper Daemon
After=mpd.service
Wants=mpd.service

[Service]
Type=simple
ExecStart=/usr/bin/python3 /usr/local/bin/arm64_mpd_cd_toc_helper.py
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```
