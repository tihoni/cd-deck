package com.cddeck.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cddeck.app.data.CdMetadataRepository
import com.cddeck.app.data.CdTrackMetadata
import com.cddeck.app.data.DiscMetadataResult
import com.cddeck.app.data.ReleaseCandidate
import com.cddeck.app.data.TrackLyrics
import com.cddeck.app.network.MpdConnectionState
import com.cddeck.app.network.MpdPlayerState
import com.cddeck.app.network.MpdSocketManager
import com.cddeck.app.network.MpdTrack
import com.cddeck.app.network.RepeatMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class CdDeckUiState(
    val mpdHost: String = "192.168.86.254",
    val mpdPort: Int = MpdSocketManager.DEFAULT_PORT,
    val connectionState: MpdConnectionState = MpdConnectionState.Disconnected,
    val playerState: MpdPlayerState = MpdPlayerState.STOP,
    val currentTrackIndex: Int = 0,
    val elapsedSec: Float = 0f,
    val durationSec: Float = 0f,
    val isDriveSeeking: Boolean = false,
    val lyricsOffsetSec: Float = 0f,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isShuffleEnabled: Boolean = false,
    val isIntroScanActive: Boolean = false,
    val introScanCountdownSec: Int = 10,
    val isRefreshingDisc: Boolean = false,
    val isMetadataLoading: Boolean = false,
    val isFallbackMetadata: Boolean = true,
    val albumTitle: String = "COMPACT DISC DIGITAL AUDIO",
    val artistName: String = "OPTICAL DECK READY",
    val coverArtUrl: String? = null,
    val tracks: List<CdTrackMetadata> = emptyList(),
    val releaseCandidates: List<ReleaseCandidate> = emptyList(),
    val selectedReleaseMbid: String? = null,
    val currentLyrics: TrackLyrics? = null,
    val isLyricsLoading: Boolean = false,
    val bitrateKbps: Int = 1411,
    val audioFormat: String = "44.1 kHz / 16-BIT PCM"
) {
    val isPlaying: Boolean
        get() = playerState == MpdPlayerState.PLAY

    val currentTrack: CdTrackMetadata?
        get() = tracks.getOrNull(currentTrackIndex)

    val currentTrackTitle: String
        get() = currentTrack?.title
            ?: if (tracks.isEmpty()) "NO DISC" else String.format(Locale.US, "TRACK %02d", currentTrackIndex + 1)

    val formattedTrackCounter: String
        get() {
            if (tracks.isEmpty()) return "00 / 00"
            return String.format(Locale.US, "%02d / %02d", currentTrackIndex + 1, tracks.size)
        }

    val formattedElapsed: String
        get() = formatSecondsCompact(elapsedSec)

    val formattedDuration: String
        get() = formatSecondsCompact(durationSec)

    val formattedRemaining: String
        get() {
            val remaining = (durationSec - elapsedSec).coerceAtLeast(0f)
            return formatSeconds(remaining, prefix = "-")
        }

    companion object {
        fun formatSeconds(seconds: Float, prefix: String = ""): String {
            val totalSeconds = seconds.toInt().coerceAtLeast(0)
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            return String.format(Locale.US, "%s%02d:%02d", prefix, mins, secs)
        }

        fun formatSecondsCompact(seconds: Float): String {
            val totalSeconds = seconds.toInt().coerceAtLeast(0)
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            return String.format(Locale.US, "%d:%02d", mins, secs)
        }
    }
}

class CdDeckViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val mpdClient: MpdSocketManager = MpdSocketManager()
    private val metadataRepository: CdMetadataRepository = CdMetadataRepository()
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "cd_deck_prefs"
        private const val KEY_MPD_HOST = "mpd_host"
        private const val KEY_MPD_PORT = "mpd_port"
        private const val KEY_CACHED_DISC_JSON = "cached_disc_json"
        private const val KEY_LYRICS_OFFSET_SEC = "lyrics_offset_sec"
        const val DEFAULT_MPD_HOST = "192.168.86.254"

        private const val INTRO_START_OFFSET_SEC = 60f
        private const val INTRO_WINDOW_SECONDS = 10
        private const val PROGRESS_TICK_MS = 250L
        private const val DEFAULT_ALSA_BUFFER_OFFSET_SEC = 1.35f
    }

    private val savedHost: String =
        prefs.getString(KEY_MPD_HOST, DEFAULT_MPD_HOST)?.takeIf { it.isNotBlank() } ?: DEFAULT_MPD_HOST
    private val savedPort: Int =
        prefs.getInt(KEY_MPD_PORT, MpdSocketManager.DEFAULT_PORT)
    private val savedLyricsOffsetSec: Float =
        prefs.getFloat(KEY_LYRICS_OFFSET_SEC, 0f)

    private val _uiState = MutableStateFlow(
        CdDeckUiState(
            mpdHost = savedHost,
            mpdPort = savedPort,
            lyricsOffsetSec = savedLyricsOffsetSec
        )
    )
    val uiState: StateFlow<CdDeckUiState> = _uiState.asStateFlow()

    private var introScanJob: Job? = null
    private var metadataLookupJob: Job? = null
    private var lyricsLookupJob: Job? = null
    private var lastResolvedDiscSignature: String = ""
    private var lastLyricsKey: String = ""
    private var lastMetadataResult: DiscMetadataResult? = null
    private var isManualMetadataLocked: Boolean = false

    // Optical drive seek & ALSA burst-buffer synchronization state
    @Volatile
    private var pendingSeekTrackPos: Int? = null
    @Volatile
    private var playbackBurstOffsetSec: Float = DEFAULT_ALSA_BUFFER_OFFSET_SEC

    init {
        observeMpdConnection()
        observeMpdSnapshot()
        startSmoothElapsedTicker()
        startPlaybackStatusPoller()

        // Seed with a 90s demo Redbook CD layout prior to live MPD socket response
        // so the UI immediately renders a rich deck state on startup.
        seedInitialFallbackDeck()

        // Initiate non-blocking connection to saved MPD host & port (default 192.168.86.254:6600)
        connectToMpd(savedHost, savedPort)
    }

    fun connectToMpd(host: String, port: Int = MpdSocketManager.DEFAULT_PORT) {
        val cleanHost = host.trim().ifBlank { DEFAULT_MPD_HOST }
        prefs.edit()
            .putString(KEY_MPD_HOST, cleanHost)
            .putInt(KEY_MPD_PORT, port)
            .apply()
        lastResolvedDiscSignature = ""
        isManualMetadataLocked = false
        _uiState.update { it.copy(mpdHost = cleanHost, mpdPort = port) }
        mpdClient.connect(cleanHost, port)
    }

    fun onAdjustLyricsOffset(deltaSec: Float) {
        val updated = (_uiState.value.lyricsOffsetSec + deltaSec).coerceIn(-15f, 15f)
        prefs.edit().putFloat(KEY_LYRICS_OFFSET_SEC, updated).apply()
        _uiState.update { it.copy(lyricsOffsetSec = updated) }
    }

    /**
     * "Forget-and-Refresh": drops the currently identified album/tracks (both locally
     * and in MPD's in-memory tag cache), reloads the playlist from MPD without causing
     * any optical drive hardware seeking, and performs a fresh MusicBrainz lookup.
     */
    fun onRefreshDisc() {
        cancelIntroScan()
        metadataLookupJob?.cancel()
        lyricsLookupJob?.cancel()
        lastResolvedDiscSignature = ""
        lastLyricsKey = ""
        lastMetadataResult = null
        isManualMetadataLocked = false
        clearCachedDiscPrefs()
        metadataRepository.clearCache()

        viewModelScope.launch {
            // Immediately drop existing album in the UI so user sees the reset right away
            val currentCount = _uiState.value.tracks.size
            val placeholderMpdTracks = mpdClient.snapshot.value.playlist.map {
                it.copy(rawTitle = null, rawArtist = null, rawAlbum = null, rawMbAlbumId = null)
            }.ifEmpty {
                List(currentCount) { idx ->
                    MpdTrack(pos = idx, id = idx + 1, file = "cdda:///${idx + 1}", durationSec = 0f)
                }
            }
            val resetFallback = metadataRepository.createFallbackResult(placeholderMpdTracks)
            applyMetadataResult(resetFallback, persistToPrefs = false)

            _uiState.update {
                it.copy(
                    isRefreshingDisc = true,
                    isMetadataLoading = true,
                    currentLyrics = null,
                    isLyricsLoading = false
                )
            }

            val refreshedSnapshot = mpdClient.forgetAndRefreshDisc().getOrNull()
            val playlist = refreshedSnapshot?.playlist ?: mpdClient.snapshot.value.playlist

            if (playlist.isEmpty()) {
                val emptyFallback = metadataRepository.createFallbackResult(emptyList())
                lastMetadataResult = emptyFallback
                applyMetadataResult(emptyFallback, persistToPrefs = false)
                _uiState.update {
                    it.copy(
                        isRefreshingDisc = false,
                        isMetadataLoading = false,
                        playerState = MpdPlayerState.STOP,
                        currentTrackIndex = 0,
                        elapsedSec = 0f,
                        durationSec = 0f
                    )
                }
            } else {
                onPlaylistUpdated(playlist, forceRefresh = true)
                _uiState.update { it.copy(isRefreshingDisc = false) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Primary Transport Controls
    // -------------------------------------------------------------------------

    fun onPlayPauseToggle() {
        cancelIntroScan()
        val currentState = _uiState.value.playerState
        viewModelScope.launch {
            when (currentState) {
                MpdPlayerState.PLAY -> {
                    pendingSeekTrackPos = null
                    _uiState.update { it.copy(playerState = MpdPlayerState.PAUSE, isDriveSeeking = false) }
                    mpdClient.pause(true)
                }
                MpdPlayerState.PAUSE -> {
                    _uiState.update { it.copy(playerState = MpdPlayerState.PLAY, isDriveSeeking = false) }
                    mpdClient.pause(false)
                }
                MpdPlayerState.STOP -> {
                    val targetPos = _uiState.value.currentTrackIndex
                    pendingSeekTrackPos = targetPos
                    _uiState.update {
                        it.copy(
                            playerState = MpdPlayerState.PLAY,
                            elapsedSec = 0f,
                            isDriveSeeking = true
                        )
                    }
                    mpdClient.play(targetPos)
                }
            }
            mpdClient.pollPlaybackStatus()
        }
    }

    fun onStop() {
        cancelIntroScan()
        pendingSeekTrackPos = null
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    playerState = MpdPlayerState.STOP,
                    elapsedSec = 0f,
                    isDriveSeeking = false
                )
            }
            mpdClient.stop()
            mpdClient.pollPlaybackStatus()
        }
    }

    fun onNextTrack() {
        cancelIntroScan()
        viewModelScope.launch {
            val tracks = _uiState.value.tracks
            if (tracks.isNotEmpty()) {
                val nextIndex = (_uiState.value.currentTrackIndex + 1) % tracks.size
                pendingSeekTrackPos = nextIndex
                _uiState.update {
                    it.copy(
                        currentTrackIndex = nextIndex,
                        elapsedSec = 0f,
                        durationSec = tracks[nextIndex].durationSec,
                        playerState = MpdPlayerState.PLAY,
                        isDriveSeeking = true
                    )
                }
                refreshLyricsForCurrentTrack()
            }
            mpdClient.next()
            mpdClient.pollPlaybackStatus()
        }
    }

    fun onPreviousTrack() {
        cancelIntroScan()
        viewModelScope.launch {
            if (_uiState.value.elapsedSec > 3f) {
                val currentPos = _uiState.value.currentTrackIndex
                pendingSeekTrackPos = currentPos
                _uiState.update {
                    it.copy(
                        elapsedSec = 0f,
                        isDriveSeeking = true
                    )
                }
                mpdClient.seekCur(0f)
            } else {
                val tracks = _uiState.value.tracks
                if (tracks.isNotEmpty()) {
                    val prevIndex = (_uiState.value.currentTrackIndex - 1 + tracks.size) % tracks.size
                    pendingSeekTrackPos = prevIndex
                    _uiState.update {
                        it.copy(
                            currentTrackIndex = prevIndex,
                            elapsedSec = 0f,
                            durationSec = tracks[prevIndex].durationSec,
                            playerState = MpdPlayerState.PLAY,
                            isDriveSeeking = true
                        )
                    }
                    refreshLyricsForCurrentTrack()
                }
                mpdClient.previous()
            }
            mpdClient.pollPlaybackStatus()
        }
    }

    fun onSelectTrack(trackPos: Int) {
        cancelIntroScan()
        viewModelScope.launch {
            val targetTrack = _uiState.value.tracks.getOrNull(trackPos)
            pendingSeekTrackPos = trackPos
            _uiState.update {
                it.copy(
                    currentTrackIndex = trackPos,
                    elapsedSec = 0f,
                    durationSec = targetTrack?.durationSec ?: it.durationSec,
                    playerState = MpdPlayerState.PLAY,
                    isDriveSeeking = true
                )
            }
            refreshLyricsForCurrentTrack()
            mpdClient.play(trackPos)
            mpdClient.pollPlaybackStatus()
        }
    }

    fun onSeekTo(seconds: Float) {
        cancelIntroScan()
        val clamped = seconds.coerceIn(0f, _uiState.value.durationSec.coerceAtLeast(1f))
        _uiState.update { it.copy(elapsedSec = clamped, isDriveSeeking = true) }
        viewModelScope.launch {
            mpdClient.seekCur(clamped)
            _uiState.update { it.copy(isDriveSeeking = false) }
            mpdClient.pollPlaybackStatus()
        }
    }

    // -------------------------------------------------------------------------
    // 90s CD Deck Modes: Tri-State Repeat, Shuffle & Intro Scan
    // -------------------------------------------------------------------------

    /**
     * Cycles Repeat through [ OFF -> ALL -> ONE ] and dispatches the dual
     * MPD `repeat` + `single` command batch.
     */
    fun onCycleRepeatMode() {
        val nextMode = _uiState.value.repeatMode.next()
        _uiState.update { it.copy(repeatMode = nextMode) }
        viewModelScope.launch {
            mpdClient.setRepeatMode(nextMode)
        }
    }

    fun onToggleShuffle() {
        val nextShuffle = !_uiState.value.isShuffleEnabled
        _uiState.update { it.copy(isShuffleEnabled = nextShuffle) }
        viewModelScope.launch {
            mpdClient.setRandom(nextShuffle)
        }
    }

    /**
     * Toggles 90s CD Player "INTRO" Scan Mode:
     * Plays 10 seconds of each track starting from the 1-minute mark
     * (or 0:00 if the track is shorter than 1 minute), then advances automatically.
     */
    fun onToggleIntroScan() {
        if (_uiState.value.isIntroScanActive) {
            cancelIntroScan()
        } else {
            startIntroScan()
        }
    }

    private fun startIntroScan() {
        introScanJob?.cancel()
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) return

        introScanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isIntroScanActive = true,
                    introScanCountdownSec = INTRO_WINDOW_SECONDS,
                    playerState = MpdPlayerState.PLAY
                )
            }

            var scanIndex = _uiState.value.currentTrackIndex.coerceIn(0, tracks.lastIndex)

            while (isActive && scanIndex < _uiState.value.tracks.size) {
                val track = _uiState.value.tracks[scanIndex]
                val startOffset = if (track.durationSec > INTRO_START_OFFSET_SEC) {
                    INTRO_START_OFFSET_SEC
                } else {
                    0f
                }

                // Update UI & send MPD play + seekcur commands
                _uiState.update {
                    it.copy(
                        currentTrackIndex = scanIndex,
                        durationSec = track.durationSec,
                        elapsedSec = startOffset,
                        playerState = MpdPlayerState.PLAY,
                        introScanCountdownSec = INTRO_WINDOW_SECONDS
                    )
                }

                mpdClient.play(track.mpdPos)
                if (startOffset > 0f) {
                    delay(120L) // Allow MPD decoder to initialize track stream before seeking
                    mpdClient.seekCur(startOffset)
                }

                // Count down 10-second audition window
                for (remaining in INTRO_WINDOW_SECONDS downTo 1) {
                    _uiState.update { it.copy(introScanCountdownSec = remaining) }
                    delay(1_000L)
                }

                // Advance to next track
                scanIndex++
                if (scanIndex < _uiState.value.tracks.size) {
                    mpdClient.next()
                }
            }

            // Completed full disc intro scan
            _uiState.update {
                it.copy(isIntroScanActive = false, introScanCountdownSec = INTRO_WINDOW_SECONDS)
            }
        }
    }

    private fun cancelIntroScan() {
        if (introScanJob != null || _uiState.value.isIntroScanActive) {
            introScanJob?.cancel()
            introScanJob = null
            _uiState.update {
                it.copy(isIntroScanActive = false, introScanCountdownSec = INTRO_WINDOW_SECONDS)
            }
        }
    }

    // -------------------------------------------------------------------------
    // MusicBrainz Multiple Results Disambiguation & Manual Album Lookup
    // -------------------------------------------------------------------------

    fun onSelectReleaseCandidate(releaseMbid: String) {
        val currentResult = lastMetadataResult ?: return
        val updatedResult = metadataRepository.selectCandidate(currentResult, releaseMbid)
        lastMetadataResult = updatedResult
        applyMetadataResult(updatedResult)
    }

    /**
     * Allows the user to manually enter an album/artist name if the physical CD
     * is not automatically recognized in MusicBrainz's TOC database.
     */
    fun onSearchAlbumManually(albumQuery: String) {
        val trimmed = albumQuery.trim()
        if (trimmed.isEmpty()) return

        metadataLookupJob?.cancel()
        metadataLookupJob = viewModelScope.launch {
            _uiState.update { it.copy(isMetadataLoading = true) }
            val currentMpdTracks = mpdClient.snapshot.value.playlist.ifEmpty {
                _uiState.value.tracks.map { t ->
                    MpdTrack(
                        pos = t.mpdPos,
                        id = t.trackNumber,
                        file = "cdda:///${t.trackNumber}",
                        durationSec = t.durationSec
                    )
                }
            }
            val result = metadataRepository.searchDiscByAlbumName(trimmed, currentMpdTracks)
            if (!result.isFallback) {
                isManualMetadataLocked = true
            }
            lastMetadataResult = result
            applyMetadataResult(result)
            _uiState.update { it.copy(isMetadataLoading = false) }
        }
    }

    // -------------------------------------------------------------------------
    // MPD State Observers & Metadata Coordination
    // -------------------------------------------------------------------------

    private fun observeMpdConnection() {
        viewModelScope.launch {
            mpdClient.connectionState.collect { connState ->
                _uiState.update { it.copy(connectionState = connState) }
            }
        }
    }

    private fun observeMpdSnapshot() {
        viewModelScope.launch {
            mpdClient.snapshot.collect { snapshot ->
                val status = snapshot.status
                val playlist = snapshot.playlist

                val pendingPos = pendingSeekTrackPos
                val rawActivePos = status.songPos.coerceAtLeast(0)
                val activePos = if (pendingPos != null && _uiState.value.isDriveSeeking) {
                    pendingPos
                } else {
                    rawActivePos
                }

                // Determine whether the physical DVD drive is still seeking to the requested track
                val stillSeeking = if (status.state != MpdPlayerState.PLAY) {
                    pendingSeekTrackPos = null
                    false
                } else if (pendingPos != null) {
                    if (rawActivePos == pendingPos && status.elapsedSec > 0.05f) {
                        // Drive just finished seeking and burst-filled the initial ALSA buffer!
                        if (status.elapsedSec in 0.5f..2.6f) {
                            playbackBurstOffsetSec = status.elapsedSec
                        }
                        pendingSeekTrackPos = null
                        false
                    } else {
                        true
                    }
                } else {
                    status.elapsedSec <= 0.01f && _uiState.value.isDriveSeeking
                }

                val activeDuration = if (status.durationSec > 0f && rawActivePos == activePos) {
                    status.durationSec
                } else {
                    playlist.getOrNull(activePos)?.durationSec
                        ?: _uiState.value.tracks.getOrNull(activePos)?.durationSec
                        ?: _uiState.value.durationSec
                }

                val compensatedElapsed = when {
                    status.state == MpdPlayerState.STOP -> 0f
                    stillSeeking -> 0f
                    status.state == MpdPlayerState.PLAY -> {
                        (status.elapsedSec - playbackBurstOffsetSec).coerceAtLeast(0f)
                    }
                    else -> status.elapsedSec.coerceAtLeast(0f)
                }

                _uiState.update { current ->
                    current.copy(
                        playerState = if (stillSeeking) MpdPlayerState.PLAY else status.state,
                        currentTrackIndex = activePos.coerceIn(0, (current.tracks.size - 1).coerceAtLeast(0)),
                        elapsedSec = compensatedElapsed,
                        durationSec = activeDuration,
                        isDriveSeeking = stillSeeking,
                        repeatMode = status.repeatMode,
                        isShuffleEnabled = status.isRandom,
                        bitrateKbps = if (status.bitrateKbps > 0) status.bitrateKbps else 1411,
                        audioFormat = formatAudioSpec(status.audioFormat)
                    )
                }

                refreshLyricsForCurrentTrack()

                if (playlist.isNotEmpty()) {
                    onPlaylistUpdated(playlist, forceRefresh = false)
                } else if (status.playlistLength == 0 && _uiState.value.connectionState is MpdConnectionState.Connected) {
                    // Physical CD was ejected and MPD queue is genuinely empty
                    if (lastResolvedDiscSignature != "EMPTY_DISC") {
                        lastResolvedDiscSignature = "EMPTY_DISC"
                        isManualMetadataLocked = false
                        val emptyResult = metadataRepository.createFallbackResult(emptyList())
                        lastMetadataResult = emptyResult
                        applyMetadataResult(emptyResult, persistToPrefs = false)
                        _uiState.update {
                            it.copy(
                                playerState = MpdPlayerState.STOP,
                                currentTrackIndex = 0,
                                elapsedSec = 0f,
                                durationSec = 0f,
                                isDriveSeeking = false,
                                isMetadataLoading = false
                            )
                        }
                    }
                }
            }
        }
    }

    private fun onPlaylistUpdated(mpdTracks: List<MpdTrack>, forceRefresh: Boolean = false) {
        val signature = metadataRepository.computeDiscSignature(mpdTracks)
        if (!forceRefresh && signature == lastResolvedDiscSignature) return

        val currentState = _uiState.value
        val sameTrackCount = currentState.tracks.size == mpdTracks.size

        // 1. If we already have resolved metadata in memory for this disc, keep it and merge durations
        if (!forceRefresh && (isManualMetadataLocked || (sameTrackCount && !currentState.isFallbackMetadata))) {
            lastResolvedDiscSignature = signature
            _uiState.update { state ->
                val mergedTracks = state.tracks.mapIndexed { idx, existingTrack ->
                    val mpdDur = mpdTracks.getOrNull(idx)?.durationSec ?: 0f
                    if (existingTrack.durationSec <= 0f && mpdDur > 0f) {
                        existingTrack.copy(durationSec = mpdDur)
                    } else {
                        existingTrack
                    }
                }
                state.copy(tracks = mergedTracks)
            }
            refreshLyricsForCurrentTrack()
            return
        }

        // 2. Check SharedPreferences cache for this disc's track count so app restarts preserve album & tracks
        if (!forceRefresh) {
            val cached = loadDiscMetadataFromPrefs(mpdTracks)
            if (cached != null) {
                lastResolvedDiscSignature = signature
                lastMetadataResult = cached
                applyMetadataResult(cached, persistToPrefs = false)
                return
            }
        }

        lastResolvedDiscSignature = signature

        // Immediately populate retro Japanese CD deck fallback so UI & tracklist respond at 0ms latency
        val immediateFallback = metadataRepository.createFallbackResult(mpdTracks)
        applyMetadataResult(immediateFallback, persistToPrefs = false)

        // Launch non-blocking MusicBrainz + Cover Art Archive resolution
        metadataLookupJob?.cancel()
        metadataLookupJob = viewModelScope.launch {
            _uiState.update { it.copy(isMetadataLoading = true) }
            val result = metadataRepository.resolveDiscMetadata(
                mpdTracks = mpdTracks,
                forceRefresh = forceRefresh
            )
            // Guard against overwriting if the user manually selected an album while lookup was in-flight
            if (!isManualMetadataLocked) {
                lastMetadataResult = result
                applyMetadataResult(result, persistToPrefs = !result.isFallback)
            }
            _uiState.update { it.copy(isMetadataLoading = false, isRefreshingDisc = false) }
        }
    }

    private fun applyMetadataResult(result: DiscMetadataResult, persistToPrefs: Boolean = true) {
        _uiState.update { current ->
            val clampedIdx = current.currentTrackIndex.coerceIn(0, (result.tracks.size - 1).coerceAtLeast(0))
            val trackDuration = result.tracks.getOrNull(clampedIdx)?.durationSec ?: current.durationSec
            current.copy(
                isFallbackMetadata = result.isFallback,
                albumTitle = result.albumTitle,
                artistName = result.artistName,
                coverArtUrl = result.coverArtUrl,
                tracks = result.tracks,
                releaseCandidates = result.candidates,
                selectedReleaseMbid = result.selectedRelease?.releaseMbid,
                currentTrackIndex = clampedIdx,
                durationSec = if (current.durationSec > 0f) current.durationSec else trackDuration
            )
        }
        if (persistToPrefs && !result.isFallback && result.tracks.isNotEmpty()) {
            saveDiscMetadataToPrefs(result)
        }
        refreshLyricsForCurrentTrack(force = true)
    }

    private fun saveDiscMetadataToPrefs(result: DiscMetadataResult) {
        runCatching {
            val root = JSONObject().apply {
                put("trackCount", result.tracks.size)
                put("albumTitle", result.albumTitle)
                put("artistName", result.artistName)
                put("coverArtUrl", result.coverArtUrl ?: "")
                put("releaseMbid", result.selectedRelease?.releaseMbid ?: "")
                put("isManualLock", isManualMetadataLocked)
                val arr = JSONArray()
                for (t in result.tracks) {
                    arr.put(
                        JSONObject().apply {
                            put("trackNumber", t.trackNumber)
                            put("mpdPos", t.mpdPos)
                            put("title", t.title)
                            put("artist", t.artist)
                            put("durationSec", t.durationSec.toDouble())
                        }
                    )
                }
                put("tracks", arr)
            }
            prefs.edit().putString(KEY_CACHED_DISC_JSON, root.toString()).apply()
        }
    }

    private fun loadDiscMetadataFromPrefs(mpdTracks: List<MpdTrack>): DiscMetadataResult? {
        if (mpdTracks.isEmpty()) return null
        val raw = prefs.getString(KEY_CACHED_DISC_JSON, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            if (obj.optInt("trackCount", -1) != mpdTracks.size) return null
            val albumTitle = obj.optString("albumTitle", "").takeIf { it.isNotBlank() } ?: return null
            val artistName = obj.optString("artistName", "").ifBlank { "Unknown Artist" }
            val coverArtUrl = obj.optString("coverArtUrl", "").takeIf { it.isNotBlank() }
            isManualMetadataLocked = obj.optBoolean("isManualLock", false)
            val arr = obj.getJSONArray("tracks")
            val tracks = buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val savedDur = item.optDouble("durationSec", 0.0).toFloat()
                    val mpdDur = mpdTracks.getOrNull(i)?.durationSec ?: 0f
                    add(
                        CdTrackMetadata(
                            trackNumber = item.optInt("trackNumber", i + 1),
                            mpdPos = item.optInt("mpdPos", i),
                            title = item.optString("title", String.format(Locale.US, "Track %02d", i + 1)),
                            artist = item.optString("artist", artistName),
                            durationSec = if (savedDur > 0f) savedDur else mpdDur
                        )
                    )
                }
            }
            DiscMetadataResult(
                isFallback = false,
                albumTitle = albumTitle,
                artistName = artistName,
                coverArtUrl = coverArtUrl,
                tracks = tracks,
                candidates = emptyList(),
                selectedRelease = null
            )
        }.getOrNull()
    }

    private fun clearCachedDiscPrefs() {
        prefs.edit().remove(KEY_CACHED_DISC_JSON).apply()
    }

    private fun refreshLyricsForCurrentTrack(force: Boolean = false) {
        val state = _uiState.value
        val track = state.currentTrack
        if (state.isFallbackMetadata || track == null || track.title.startsWith("TRACK ", ignoreCase = true)) {
            lyricsLookupJob?.cancel()
            lastLyricsKey = ""
            _uiState.update { it.copy(currentLyrics = null, isLyricsLoading = false) }
            return
        }

        val primaryArtist = track.artist.ifBlank { state.artistName }
        val lyricsKey = "${primaryArtist.lowercase()}|${track.title.lowercase()}|${track.trackNumber}"
        if (!force && lyricsKey == lastLyricsKey) return
        lastLyricsKey = lyricsKey

        lyricsLookupJob?.cancel()
        lyricsLookupJob = viewModelScope.launch {
            _uiState.update { it.copy(isLyricsLoading = true) }
            val targetDur = track.durationSec.takeIf { it > 0f } ?: state.durationSec
            val resolvedLyrics = metadataRepository.fetchLyrics(
                artistName = primaryArtist,
                trackTitle = track.title,
                albumTitle = state.albumTitle,
                durationSec = targetDur
            ) ?: if (!primaryArtist.equals(state.artistName, ignoreCase = true)) {
                metadataRepository.fetchLyrics(
                    artistName = state.artistName,
                    trackTitle = track.title,
                    albumTitle = state.albumTitle,
                    durationSec = targetDur
                )
            } else {
                null
            }
            _uiState.update {
                it.copy(
                    currentLyrics = resolvedLyrics,
                    isLyricsLoading = false
                )
            }
        }
    }

    /**
     * Smoothly advances the progress bar at 4fps ONLY when the optical drive has finished
     * seeking (`!isDriveSeeking`) and MPD is actively playing audio.
     */
    private fun startSmoothElapsedTicker() {
        viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_TICK_MS)
                _uiState.update { state ->
                    if (state.playerState == MpdPlayerState.PLAY && !state.isDriveSeeking && state.durationSec > 0f) {
                        val nextElapsed = (state.elapsedSec + (PROGRESS_TICK_MS / 1000f))
                        if (nextElapsed <= state.durationSec) {
                            state.copy(elapsedSec = nextElapsed)
                        } else {
                            state
                        }
                    } else {
                        state
                    }
                }
            }
        }
    }

    /**
     * Continuously anchors `elapsedSec` to MPD's real hardware playback position:
     * - Polls every 300ms while waiting for the DVD drive to seek to a newly selected track
     *   so the UI starts counting at the exact moment audio starts playing.
     * - Polls every 1000ms during steady-state playback so the progress bar and synced
     *   lyrics never drift ahead of the audio.
     */
    private fun startPlaybackStatusPoller() {
        viewModelScope.launch {
            while (isActive) {
                val state = _uiState.value
                val isConnected = state.connectionState is MpdConnectionState.Connected
                val pollDelayMs = when {
                    !isConnected -> 1_500L
                    state.isDriveSeeking -> 300L
                    state.playerState == MpdPlayerState.PLAY -> 1_000L
                    else -> 2_500L
                }
                delay(pollDelayMs)
                if (_uiState.value.connectionState is MpdConnectionState.Connected &&
                    (_uiState.value.playerState == MpdPlayerState.PLAY || _uiState.value.isDriveSeeking)
                ) {
                    mpdClient.pollPlaybackStatus()
                }
            }
        }
    }

    private fun seedInitialFallbackDeck() {
        val sampleRedbookTracks = listOf(
            MpdTrack(pos = 0, id = 1, file = "cdda:///1", durationSec = 278.4f),
            MpdTrack(pos = 1, id = 2, file = "cdda:///2", durationSec = 321.0f),
            MpdTrack(pos = 2, id = 3, file = "cdda:///3", durationSec = 246.8f),
            MpdTrack(pos = 3, id = 4, file = "cdda:///4", durationSec = 364.2f),
            MpdTrack(pos = 4, id = 5, file = "cdda:///5", durationSec = 295.5f),
            MpdTrack(pos = 5, id = 6, file = "cdda:///6", durationSec = 198.0f),
            MpdTrack(pos = 6, id = 7, file = "cdda:///7", durationSec = 412.6f),
            MpdTrack(pos = 7, id = 8, file = "cdda:///8", durationSec = 263.1f),
            MpdTrack(pos = 8, id = 9, file = "cdda:///9", durationSec = 315.9f),
            MpdTrack(pos = 9, id = 10, file = "cdda:///10", durationSec = 388.0f)
        )
        val fallback = metadataRepository.createFallbackResult(sampleRedbookTracks)
        applyMetadataResult(fallback, persistToPrefs = false)
    }

    private fun formatAudioSpec(rawAudio: String): String {
        val parts = rawAudio.split(":")
        val rateHz = parts.getOrNull(0)?.toIntOrNull() ?: 44100
        val bits = parts.getOrNull(1) ?: "16"
        val rateKhz = String.format(Locale.US, "%.1f", rateHz / 1000f)
        return "$rateKhz kHz / $bits-BIT PCM"
    }

    override fun onCleared() {
        super.onCleared()
        introScanJob?.cancel()
        mpdClient.disconnect()
    }
}
