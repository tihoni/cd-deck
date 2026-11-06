package com.cddeck.app.network

import com.cddeck.app.data.CdTrackMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

enum class MpdPlayerState {
    PLAY, PAUSE, STOP;

    companion object {
        fun fromMpdString(value: String?): MpdPlayerState = when (value?.lowercase(Locale.ROOT)) {
            "play" -> PLAY
            "pause" -> PAUSE
            else -> STOP
        }
    }
}

enum class RepeatMode {
    OFF,
    ALL,
    ONE;

    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

data class MpdStatus(
    val state: MpdPlayerState = MpdPlayerState.STOP,
    val songPos: Int = 0,
    val songId: Int = -1,
    val elapsedSec: Float = 0f,
    val durationSec: Float = 0f,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isRandom: Boolean = false,
    val playlistVersion: Int = -1,
    val playlistLength: Int = 0,
    val bitrateKbps: Int = 0,
    val audioFormat: String = "44100:16:2"
)

data class MpdTrack(
    val pos: Int,
    val id: Int,
    val file: String,
    val durationSec: Float,
    val rawTitle: String? = null,
    val rawArtist: String? = null,
    val rawAlbum: String? = null,
    val rawGrouping: String? = null,
    val rawMbAlbumId: String? = null,
    val trackNumber: Int = pos + 1
) {
    val durationMs: Long
        get() = (durationSec * 1000f).toLong()
}

data class MpdSnapshot(
    val status: MpdStatus = MpdStatus(),
    val currentSong: MpdTrack? = null,
    val playlist: List<MpdTrack> = emptyList()
)

sealed interface MpdConnectionState {
    data object Disconnected : MpdConnectionState
    data class Connecting(val host: String, val port: Int) : MpdConnectionState
    data class Connected(val host: String, val port: Int, val serverVersion: String) : MpdConnectionState
    data class Error(val host: String, val port: Int, val message: String) : MpdConnectionState
}

/**
 * Pure Kotlin non-blocking TCP client for Music Player Daemon (MPD) on port 6600.
 *
 * Uses a dual-socket architecture:
 * 1. Command Socket (Mutex-guarded, auto-reconnecting) for low-latency transport & status commands.
 * 2. Idle Socket running `idle player playlist options` loop to react immediately
 *    to hardware CD insertions, track boundary transitions, and external state changes.
 */
class MpdSocketManager(
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        const val DEFAULT_PORT = 6600
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val COMMAND_READ_TIMEOUT_MS = 12_000
        private const val RECONNECT_DELAY_MS = 3_000L

        /**
         * Parses a hardware CD TOC string into standard MusicBrainz TOC integers:
         * `[firstTrack, lastTrack, leadoutSector, offset1, offset2, ..., offsetN]`
         *
         * Supports both:
         * 1. MusicBrainz / `cd-discid --musicbrainz` format:
         *    `1 <N> <leadoutSector> <offset1> ... <offsetN>` (or `<N> <offset1> ... <offsetN> <leadoutSector>`)
         * 2. Standard `cd-discid` (Freedb) format:
         *    `<8char_hex_id> <N> <offset1> ... <offsetN> <totalSeconds>`
         */
        fun parseHardwareTocSectors(rawToc: String?): List<Int>? {
            if (rawToc.isNullOrBlank()) return null
            val tokens = rawToc.trim().split(Regex("""[\s+]+""")).filter { it.isNotBlank() }
            if (tokens.size < 4) return null

            // Case 1: MusicBrainz format `1 <N> <leadout> <offset1> ... <offsetN>`
            val firstInt = tokens[0].toIntOrNull()
            val secondInt = tokens[1].toIntOrNull()
            if (firstInt == 1 && secondInt != null && secondInt > 0 && tokens.size == secondInt + 3) {
                val ints = tokens.mapNotNull { it.toIntOrNull() }
                if (ints.size == tokens.size && ints[2] > ints.last()) {
                    return ints
                }
            }

            // Case 2: `cd-discid --musicbrainz` without leading `1`: `<N> <offset1> ... <offsetN> <leadout>`
            if (firstInt != null && firstInt > 0 && tokens.size == firstInt + 2) {
                val ints = tokens.mapNotNull { it.toIntOrNull() }
                if (ints.size == tokens.size) {
                    val count = ints[0]
                    val leadout = ints.last()
                    val offsets = ints.subList(1, 1 + count)
                    if (leadout > offsets.last()) {
                        return buildList {
                            add(1)
                            add(count)
                            add(leadout)
                            addAll(offsets)
                        }
                    }
                }
            }

            // Case 3: Freedb `cd-discid` format: `<hex_id> <N> <offset1> ... <offsetN> <totalSeconds>`
            if (secondInt != null && secondInt > 0 && tokens.size == secondInt + 3) {
                val tailInts = tokens.drop(1).mapNotNull { it.toIntOrNull() }
                if (tailInts.size == secondInt + 2) {
                    val count = tailInts[0]
                    val offsets = tailInts.subList(1, 1 + count)
                    val lastVal = tailInts.last()
                    // In Freedb cd-discid, the final value is total disc seconds (leadout = seconds * 75 + 150)
                    val leadout = if (lastVal <= offsets.last()) {
                        (lastVal * 75) + (offsets.firstOrNull() ?: 150)
                    } else {
                        lastVal
                    }
                    if (leadout > offsets.last()) {
                        return buildList {
                            add(1)
                            add(count)
                            add(leadout)
                            addAll(offsets)
                        }
                    }
                }
            }

            return null
        }
    }

    private val commandMutex = Mutex()
    private var commandSocket: Socket? = null
    private var commandReader: BufferedReader? = null
    private var commandWriter: BufferedWriter? = null

    private var idleJob: Job? = null
    private var currentHost: String = "192.168.86.254"
    private var currentPort: Int = DEFAULT_PORT

    private val _connectionState = MutableStateFlow<MpdConnectionState>(MpdConnectionState.Disconnected)
    val connectionState: StateFlow<MpdConnectionState> = _connectionState.asStateFlow()

    private val _snapshot = MutableStateFlow(MpdSnapshot())
    val snapshot: StateFlow<MpdSnapshot> = _snapshot.asStateFlow()

    private val _idleEvents = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)
    val idleEvents: SharedFlow<Set<String>> = _idleEvents.asSharedFlow()

    // Cache of learned cdda:// track durations (pos -> durationSec) for the current disc
    private val cddaDurationCache = mutableMapOf<Int, Float>()
    private var cachedPlaylistVersion: Int = -1
    private var cachedPlaylistLength: Int = -1

    /**
     * Connects to the specified MPD host & port and starts the `idle player playlist` listener loop.
     */
    fun connect(host: String, port: Int = DEFAULT_PORT) {
        currentHost = host.trim()
        currentPort = port
        idleJob?.cancel()
        idleJob = externalScope.launch {
            runConnectionLoop(currentHost, currentPort)
        }
    }

    fun disconnect() {
        idleJob?.cancel()
        idleJob = null
        externalScope.launch {
            commandMutex.withLock {
                closeCommandSocket()
            }
            _connectionState.value = MpdConnectionState.Disconnected
        }
    }

    // -------------------------------------------------------------------------
    // Transport & Playback Commands
    // -------------------------------------------------------------------------

    suspend fun play(songPos: Int? = null): Result<Unit> =
        if (songPos != null) sendSimpleCommand("play $songPos") else sendSimpleCommand("play")

    suspend fun pause(paused: Boolean? = null): Result<Unit> = when (paused) {
        true -> sendSimpleCommand("pause 1")
        false -> sendSimpleCommand("pause 0")
        null -> sendSimpleCommand("pause")
    }

    suspend fun stop(): Result<Unit> = sendSimpleCommand("stop")

    suspend fun next(): Result<Unit> = sendSimpleCommand("next")

    suspend fun previous(): Result<Unit> = sendSimpleCommand("previous")

    suspend fun seekCur(seconds: Float): Result<Unit> {
        val clamped = seconds.coerceAtLeast(0f)
        val formatted = String.format(Locale.US, "%.2f", clamped)
        return sendSimpleCommand("seekcur $formatted")
    }

    /**
     * Tri-state Repeat implementation per MPD spec:
     * - Repeat 1:   `repeat 1` + `single 1`
     * - Repeat All: `repeat 1` + `single 0`
     * - Repeat Off: `repeat 0` + `single 0`
     */
    suspend fun setRepeatMode(mode: RepeatMode): Result<Unit> {
        val commands = when (mode) {
            RepeatMode.ONE -> listOf("repeat 1", "single 1")
            RepeatMode.ALL -> listOf("repeat 1", "single 0")
            RepeatMode.OFF -> listOf("repeat 0", "single 0")
        }
        return sendCommandBatch(commands).also {
            if (it.isSuccess) refreshSnapshot()
        }
    }

    suspend fun setRandom(enabled: Boolean): Result<Unit> {
        val flag = if (enabled) 1 else 0
        return sendSimpleCommand("random $flag").also {
            if (it.isSuccess) refreshSnapshot()
        }
    }

    // -------------------------------------------------------------------------
    // Query & In-Memory Tag Commands (Zero Optical Drive Seeking)
    // -------------------------------------------------------------------------

    suspend fun fetchStatus(): Result<MpdStatus> =
        executeRawCommand("status").map { lines -> parseStatus(lines) }

    suspend fun fetchCurrentSong(): Result<MpdTrack?> =
        executeRawCommand("currentsong").map { lines -> parseTracks(lines).firstOrNull() }

    suspend fun fetchPlaylistInfo(): Result<List<MpdTrack>> =
        executeRawCommand("playlistinfo").map { lines -> parseTracks(lines) }

    /**
     * Ultra-lightweight status poll used during active playback & optical drive seeks
     * to keep the UI timer and synced lyrics locked to MPD's real playback position
     * without querying `playlistinfo`.
     */
    suspend fun pollPlaybackStatus(): Result<MpdStatus> = runCatching {
        val newStatus = fetchStatus().getOrThrow()
        if (newStatus.durationSec > 0f && newStatus.songPos >= 0) {
            cddaDurationCache[newStatus.songPos] = newStatus.durationSec
        }
        val current = _snapshot.value
        if (current.status.playlistLength != newStatus.playlistLength) {
            refreshSnapshot(forcePlaylistReload = true)
        } else {
            _snapshot.value = current.copy(status = newStatus)
        }
        newStatus
    }

    /**
     * "Forget-and-Refresh" without causing any optical drive seeking:
     * 1. Clears in-memory `Title`, `Artist`, `Album`, and `MUSICBRAINZ_ALBUMID` tags on MPD.
     * 2. Signals any local `cddeck` TOC helper on the arm64 host (`sendmessage cddeck refresh_toc`).
     * 3. Reloads `status`, `currentsong`, and `playlistinfo` cleanly.
     */
    suspend fun forgetAndRefreshDisc(): Result<MpdSnapshot> = runCatching {
        cddaDurationCache.clear()
        cachedPlaylistVersion = -1
        cachedPlaylistLength = -1

        val existingTracks = fetchPlaylistInfo().getOrDefault(emptyList())
        if (existingTracks.isNotEmpty()) {
            val clearCmds = mutableListOf<String>()
            for (t in existingTracks) {
                clearCmds.add("cleartagid ${t.id} Title")
                clearCmds.add("cleartagid ${t.id} Artist")
                clearCmds.add("cleartagid ${t.id} Album")
                clearCmds.add("cleartagid ${t.id} MUSICBRAINZ_ALBUMID")
            }
            sendCommandBatch(clearCmds)
        } else {
            // Only if the MPD queue is completely empty, load cdda://
            executeRawCommand("add cdda://")
        }

        // Notify optional arm64 TOC helper daemon (if subscribed to channel `cddeck`)
        executeRawCommand("sendmessage cddeck refresh_toc")
        delay(180L)

        refreshSnapshot(forcePlaylistReload = true).getOrThrow()
    }

    /**
     * Pulls a complete state snapshot (`status`, `currentsong`, and `playlistinfo` if changed).
     */
    suspend fun refreshSnapshot(forcePlaylistReload: Boolean = false): Result<MpdSnapshot> = runCatching {
        val newStatus = fetchStatus().getOrThrow()
        val currentSong = fetchCurrentSong().getOrNull()

        if (cachedPlaylistLength != -1 && newStatus.playlistLength != cachedPlaylistLength) {
            cddaDurationCache.clear()
        }
        cachedPlaylistVersion = newStatus.playlistVersion
        cachedPlaylistLength = newStatus.playlistLength

        if (newStatus.durationSec > 0f && newStatus.songPos >= 0) {
            cddaDurationCache[newStatus.songPos] = newStatus.durationSec
        }

        val previousSnapshot = _snapshot.value
        val shouldReloadPlaylist = forcePlaylistReload ||
            previousSnapshot.playlist.isEmpty() ||
            previousSnapshot.status.playlistLength != newStatus.playlistLength

        val basePlaylist = if (newStatus.playlistLength == 0) {
            emptyList()
        } else if (shouldReloadPlaylist) {
            fetchPlaylistInfo().getOrNull()?.takeIf { it.isNotEmpty() } ?: previousSnapshot.playlist
        } else {
            previousSnapshot.playlist
        }

        // If Track 1 (or any track) carries a hardware TOC in `Grouping` (e.g. from cd-discid / ioctl helper),
        // compute exact durations for all tracks on the disc with zero optical drive seeking!
        val rawToc = basePlaylist.firstOrNull { !it.rawGrouping.isNullOrBlank() }?.rawGrouping
        val tocSectors = parseHardwareTocSectors(rawToc)
        if (tocSectors != null && tocSectors.size >= 4) {
            val trackCount = tocSectors[1]
            val leadoutSector = tocSectors[2]
            val offsets = tocSectors.drop(3)
            if (offsets.size == trackCount) {
                for (i in 0 until trackCount) {
                    val startSector = offsets[i]
                    val endSector = if (i + 1 < trackCount) offsets[i + 1] else leadoutSector
                    val durSec = ((endSector - startSector).coerceAtLeast(75)) / 75.0f
                    cddaDurationCache[i] = durSec
                }
            }
        }

        // Enrich tracks with any durations learned from hardware TOC or `status`
        val playlist = basePlaylist.map { track ->
            val knownDur = if (track.durationSec > 0f) {
                track.durationSec
            } else {
                cddaDurationCache[track.pos] ?: 0f
            }
            if (knownDur > 0f && track.durationSec != knownDur) {
                track.copy(durationSec = knownDur)
            } else {
                track
            }
        }

        MpdSnapshot(
            status = newStatus,
            currentSong = currentSong?.let { song ->
                val d = if (song.durationSec > 0f) song.durationSec else (cddaDurationCache[song.pos] ?: newStatus.durationSec)
                song.copy(durationSec = d)
            },
            playlist = playlist
        ).also { updated ->
            _snapshot.value = updated
        }
    }

    // -------------------------------------------------------------------------
    // Internal Socket Lifecycle & Idle Loop
    // -------------------------------------------------------------------------

    private suspend fun runConnectionLoop(host: String, port: Int) = withContext(Dispatchers.IO) {
        while (isActive) {
            var idleSocket: Socket? = null
            try {
                _connectionState.value = MpdConnectionState.Connecting(host, port)

                // 1. Ensure command socket is open
                val serverVersion = commandMutex.withLock {
                    openCommandSocketLocked(host, port)
                }

                // 2. Open dedicated idle socket for `idle player options`
                idleSocket = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                }
                val idleReader = BufferedReader(InputStreamReader(idleSocket.getInputStream(), Charsets.UTF_8))
                val idleWriter = BufferedWriter(OutputStreamWriter(idleSocket.getOutputStream(), Charsets.UTF_8))

                val greeting = idleReader.readLine()
                    ?: throw IOException("MPD server closed idle connection before greeting")
                if (!greeting.startsWith("OK MPD")) {
                    throw IOException("Unexpected MPD greeting: $greeting")
                }

                _connectionState.value = MpdConnectionState.Connected(host, port, serverVersion)

                // Never wipe the existing TOC; only ask the helper (if running) to re-stamp.
                // The idle loop below listens for `playlist` so the stamped TOC is picked up.
                executeRawCommand("sendmessage cddeck refresh_toc")

                // Initial full snapshot on connect (zero optical drive seeking)
                refreshSnapshot(forcePlaylistReload = true)

                // 3. Non-blocking `idle player options` loop
                while (isActive && !idleSocket.isClosed) {
                    idleWriter.write("idle player playlist options\n")
                    idleWriter.flush()

                    val changedSystems = mutableSetOf<String>()
                    while (true) {
                        val line = idleReader.readLine()
                            ?: throw IOException("EOF while waiting for MPD idle event")
                        when {
                            line == "OK" -> break
                            line.startsWith("ACK ") -> throw IOException("MPD idle error: $line")
                            line.startsWith("changed:") -> {
                                val subsystem = line.substringAfter("changed:").trim()
                                changedSystems.add(subsystem)
                            }
                        }
                    }

                    if (changedSystems.isNotEmpty()) {
                        _idleEvents.tryEmit(changedSystems)
                        refreshSnapshot(forcePlaylistReload = "playlist" in changedSystems)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _connectionState.value = MpdConnectionState.Error(
                    host = host,
                    port = port,
                    message = e.message ?: "Unable to reach MPD at $host:$port"
                )
            } finally {
                runCatching { idleSocket?.close() }
                commandMutex.withLock { closeCommandSocket() }
            }

            delay(RECONNECT_DELAY_MS)
        }
    }

    private fun openCommandSocketLocked(host: String, port: Int): String {
        closeCommandSocket()
        val socket = Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            soTimeout = COMMAND_READ_TIMEOUT_MS
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

        val banner = reader.readLine() ?: throw IOException("Empty greeting from MPD")
        if (!banner.startsWith("OK MPD")) {
            socket.close()
            throw IOException("Invalid MPD greeting: $banner")
        }

        commandSocket = socket
        commandReader = reader
        commandWriter = writer
        return banner.removePrefix("OK MPD").trim()
    }

    private fun closeCommandSocket() {
        runCatching { commandWriter?.close() }
        runCatching { commandReader?.close() }
        runCatching { commandSocket?.close() }
        commandWriter = null
        commandReader = null
        commandSocket = null
    }

    private suspend fun sendSimpleCommand(command: String): Result<Unit> =
        executeRawCommand(command).map { }

    private suspend fun sendCommandBatch(commands: List<String>): Result<Unit> {
        val batch = buildList {
            add("command_list_begin")
            addAll(commands)
            add("command_list_end")
        }.joinToString("\n")
        return executeRawCommand(batch).map { }
    }

    /**
     * Executes an MPD command over `commandSocket`.
     * Because MPD closes idle non-`idle` connections after 60 seconds, a reused socket may throw
     * EOF/IOException on the first write or read after a quiet period. We automatically reconnect
     * and retry once so user taps (e.g. selecting a track in the playlist) never get dropped.
     */
    private suspend fun executeRawCommand(command: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            commandMutex.withLock {
                var lastError: Throwable? = null
                for (attempt in 1..2) {
                    try {
                        if (commandSocket?.isConnected != true || commandSocket?.isClosed == true) {
                            openCommandSocketLocked(currentHost, currentPort)
                        }
                        val writer = commandWriter ?: throw IOException("Command writer unavailable")
                        val reader = commandReader ?: throw IOException("Command reader unavailable")

                        writer.write("$command\n")
                        writer.flush()

                        val responseLines = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine()
                                ?: throw IOException("Connection closed by MPD during '$command'")
                            when {
                                line == "OK" -> break
                                line.startsWith("ACK ") -> {
                                    // MPD protocol error (not a broken socket) — do not retry
                                    return@withLock Result.failure(IOException(line))
                                }
                                else -> responseLines.add(line)
                            }
                        }
                        return@withLock Result.success(responseLines)
                    } catch (e: Exception) {
                        lastError = e
                        closeCommandSocket()
                    }
                }
                Result.failure(lastError ?: IOException("Failed to execute '$command'"))
            }
        }

    private fun escapeMpdArg(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    // -------------------------------------------------------------------------
    // MPD Protocol Parsers
    // -------------------------------------------------------------------------

    private fun parseStatus(lines: List<String>): MpdStatus {
        val map = lines.associate { line ->
            val key = line.substringBefore(":").trim().lowercase(Locale.ROOT)
            val value = line.substringAfter(":", "").trim()
            key to value
        }

        val state = MpdPlayerState.fromMpdString(map["state"])
        val songPos = map["song"]?.toIntOrNull() ?: 0
        val songId = map["songid"]?.toIntOrNull() ?: -1

        val timeParts = map["time"]?.split(":")
        val elapsedSec = map["elapsed"]?.toFloatOrNull()
            ?: timeParts?.getOrNull(0)?.toFloatOrNull()
            ?: 0f
        val durationSec = map["duration"]?.toFloatOrNull()
            ?: timeParts?.getOrNull(1)?.toFloatOrNull()
            ?: 0f

        val repeatFlag = map["repeat"] == "1"
        val singleFlag = map["single"] == "1"
        val repeatMode = when {
            repeatFlag && singleFlag -> RepeatMode.ONE
            repeatFlag && !singleFlag -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }

        return MpdStatus(
            state = state,
            songPos = songPos,
            songId = songId,
            elapsedSec = elapsedSec,
            durationSec = durationSec,
            repeatMode = repeatMode,
            isRandom = map["random"] == "1",
            playlistVersion = map["playlist"]?.toIntOrNull() ?: -1,
            playlistLength = map["playlistlength"]?.toIntOrNull() ?: 0,
            bitrateKbps = map["bitrate"]?.toIntOrNull() ?: 1411,
            audioFormat = map["audio"] ?: "44100:16:2"
        )
    }

    private fun parseTracks(lines: List<String>): List<MpdTrack> {
        val tracks = mutableListOf<MpdTrack>()
        var currentFields = mutableMapOf<String, String>()

        fun flushTrack() {
            val file = currentFields["file"] ?: return
            val pos = currentFields["pos"]?.toIntOrNull() ?: tracks.size
            val id = currentFields["id"]?.toIntOrNull() ?: pos
            val duration = currentFields["duration"]?.toFloatOrNull()
                ?: currentFields["time"]?.toFloatOrNull()
                ?: 0f
            val parsedTrackNo = currentFields["track"]
                ?.substringBefore("/")
                ?.toIntOrNull()
                ?: (pos + 1)

            tracks.add(
                MpdTrack(
                    pos = pos,
                    id = id,
                    file = file,
                    durationSec = duration,
                    rawTitle = currentFields["title"],
                    rawArtist = currentFields["artist"] ?: currentFields["albumartist"],
                    rawAlbum = currentFields["album"],
                    rawGrouping = currentFields["grouping"] ?: currentFields["disc"],
                    rawMbAlbumId = currentFields["musicbrainz_albumid"],
                    trackNumber = parsedTrackNo
                )
            )
        }

        for (line in lines) {
            val key = line.substringBefore(":").trim().lowercase(Locale.ROOT)
            val value = line.substringAfter(":", "").trim()
            if (key == "file") {
                if (currentFields.isNotEmpty()) {
                    flushTrack()
                }
                currentFields = mutableMapOf("file" to value)
            } else {
                currentFields[key] = value
            }
        }
        if (currentFields.isNotEmpty()) {
            flushTrack()
        }

        return tracks.sortedBy { it.pos }
    }
}
