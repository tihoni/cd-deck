package com.cddeck.app.data

import android.util.Base64
import com.cddeck.app.network.MpdSocketManager
import com.cddeck.app.network.MpdTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

data class CdTrackMetadata(
    val trackNumber: Int,
    val mpdPos: Int,
    val title: String,
    val artist: String,
    val durationSec: Float
) {
    val formattedNumber: String
        get() = String.format(Locale.US, "%02d", trackNumber)
}

data class ReleaseCandidate(
    val releaseMbid: String,
    val albumTitle: String,
    val artistName: String,
    val status: String?,
    val hasCoverArtFront: Boolean,
    val releaseDate: String?,
    val country: String?,
    val disambiguation: String?,
    val coverArtUrl: String,
    val tracks: List<CdTrackMetadata>,
    val avgDurationDeltaMs: Long
) {
    val isOfficial: Boolean
        get() = status.equals("Official", ignoreCase = true)

    val displayBadge: String
        get() = buildList {
            if (!releaseDate.isNullOrBlank()) add(releaseDate.take(4))
            if (!country.isNullOrBlank()) add(country)
            if (!status.isNullOrBlank()) add(status)
            if (!disambiguation.isNullOrBlank()) add(disambiguation)
        }.joinToString(" • ").ifBlank { "Standard Edition" }
}

data class DiscMetadataResult(
    val isFallback: Boolean,
    val selectedRelease: ReleaseCandidate?,
    val candidates: List<ReleaseCandidate>,
    val albumTitle: String,
    val artistName: String,
    val coverArtUrl: String?,
    val tracks: List<CdTrackMetadata>
)

data class LyricLine(
    val timestampSec: Float? = null,
    val text: String
)

data class TrackLyrics(
    val trackTitle: String,
    val artistName: String,
    val isSynced: Boolean,
    val isInstrumental: Boolean = false,
    val lines: List<LyricLine>
)

/**
 * Resolves physical audio CD track layouts and manual album searches
 * against the MusicBrainz WS/2 API and Cover Art Archive.
 */
class CdMetadataRepository(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val USER_AGENT = "CDDeckAndroid/1.0 ( https://github.com/cddeck )"
        private const val MB_BASE_URL = "https://musicbrainz.org/ws/2"
        private const val CAA_BASE_URL = "https://coverartarchive.org/release"
        private const val DURATION_TOLERANCE_MS = 3_500L
        private const val MAX_CANDIDATES_TO_HYDRATE = 4
        private const val MIN_MB_REQUEST_INTERVAL_MS = 1_100L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val rateLimitMutex = Mutex()
    private var lastRequestTimestampMs: Long = 0L

    // Simple in-memory cache keyed by disc layout signature
    private val discCache = mutableMapOf<String, DiscMetadataResult>()
    private val lyricsCache = mutableMapOf<String, TrackLyrics>()

    fun clearCache() {
        discCache.clear()
    }

    /**
     * Fetches synced or plain lyrics for a track from LRCLIB (`https://lrclib.net/api/search`).
     */
    suspend fun fetchLyrics(
        artistName: String,
        trackTitle: String,
        albumTitle: String? = null,
        durationSec: Float = 0f
    ): TrackLyrics? = withContext(Dispatchers.IO) {
        val cleanTrack = trackTitle.trim()
        val cleanArtist = artistName.trim()
        if (cleanTrack.isBlank() ||
            cleanTrack.startsWith("Track ", ignoreCase = true) ||
            cleanTrack.equals("NO DISC", ignoreCase = true)
        ) {
            return@withContext null
        }

        val cacheKey = "${cleanArtist.lowercase(Locale.ROOT)}|${cleanTrack.lowercase(Locale.ROOT)}"
        lyricsCache[cacheKey]?.let { return@withContext it }

        val encodedTrack = URLEncoder.encode(cleanTrack, Charsets.UTF_8.name())
        val encodedArtist = URLEncoder.encode(
            if (cleanArtist.equals("Unknown Artist", ignoreCase = true) ||
                cleanArtist.startsWith("Tap to", ignoreCase = true)
            ) "" else cleanArtist,
            Charsets.UTF_8.name()
        )

        val searchUrl = if (encodedArtist.isNotBlank()) {
            "https://lrclib.net/api/search?track_name=$encodedTrack&artist_name=$encodedArtist"
        } else {
            "https://lrclib.net/api/search?q=$encodedTrack"
        }

        val results = runCatching {
            val request = Request.Builder()
                .url(searchUrl.toHttpUrl())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                json.decodeFromString<List<LrcLibItem>>(body)
            }
        }.getOrNull().orEmpty()

        // If track_name + artist_name returned empty, try general query `q=artist track`
        val candidates = if (results.isEmpty() && encodedArtist.isNotBlank()) {
            val fallbackQ = URLEncoder.encode("$cleanArtist $cleanTrack", Charsets.UTF_8.name())
            runCatching {
                val req = Request.Builder()
                    .url("https://lrclib.net/api/search?q=$fallbackQ".toHttpUrl())
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    json.decodeFromString<List<LrcLibItem>>(body)
                }
            }.getOrNull().orEmpty()
        } else {
            results
        }

        if (candidates.isEmpty()) return@withContext null

        // Rank best lyric candidate: prefer syncedLyrics, then closest duration, then plainLyrics
        val best = candidates.sortedWith(
            compareByDescending<LrcLibItem> { !it.syncedLyrics.isNullOrBlank() }
                .thenByDescending { !it.plainLyrics.isNullOrBlank() || it.instrumental }
                .thenBy { item ->
                    if (durationSec > 0f && item.duration != null && item.duration > 0.0) {
                        abs(durationSec - item.duration.toFloat())
                    } else {
                        999f
                    }
                }
        ).firstOrNull() ?: return@withContext null

        val parsedLyrics = when {
            best.instrumental && best.plainLyrics.isNullOrBlank() && best.syncedLyrics.isNullOrBlank() -> {
                TrackLyrics(
                    trackTitle = cleanTrack,
                    artistName = cleanArtist,
                    isSynced = false,
                    isInstrumental = true,
                    lines = listOf(LyricLine(null, "♪ Instrumental Track ♪"))
                )
            }
            !best.syncedLyrics.isNullOrBlank() -> {
                val syncedLines = parseSyncedLrc(best.syncedLyrics)
                if (syncedLines.isNotEmpty()) {
                    TrackLyrics(
                        trackTitle = cleanTrack,
                        artistName = cleanArtist,
                        isSynced = true,
                        lines = syncedLines
                    )
                } else {
                    null
                }
            }
            !best.plainLyrics.isNullOrBlank() -> {
                val plainLines = best.plainLyrics
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { LyricLine(timestampSec = null, text = it) }
                if (plainLines.isNotEmpty()) {
                    TrackLyrics(
                        trackTitle = cleanTrack,
                        artistName = cleanArtist,
                        isSynced = false,
                        lines = plainLines
                    )
                } else {
                    null
                }
            }
            else -> null
        }

        if (parsedLyrics != null) {
            lyricsCache[cacheKey] = parsedLyrics
        }
        parsedLyrics
    }

    private fun parseSyncedLrc(rawLrc: String): List<LyricLine> {
        val regex = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?](.*)$""")
        val lines = mutableListOf<LyricLine>()
        for (rawLine in rawLrc.lines()) {
            val match = regex.find(rawLine.trim()) ?: continue
            val mins = match.groupValues[1].toIntOrNull() ?: 0
            val secs = match.groupValues[2].toIntOrNull() ?: 0
            val fracStr = match.groupValues[3]
            val fracSec = when (fracStr.length) {
                1 -> (fracStr.toFloatOrNull() ?: 0f) / 10f
                2 -> (fracStr.toFloatOrNull() ?: 0f) / 100f
                3 -> (fracStr.toFloatOrNull() ?: 0f) / 1000f
                else -> 0f
            }
            val lyricText = match.groupValues[4].trim()
            if (lyricText.isNotEmpty()) {
                lines.add(
                    LyricLine(
                        timestampSec = (mins * 60f) + secs + fracSec,
                        text = lyricText
                    )
                )
            }
        }
        return lines
    }

    fun computeDiscSignature(mpdTracks: List<MpdTrack>): String {
        if (mpdTracks.isEmpty()) return "EMPTY_DISC"
        val rawToc = mpdTracks.firstOrNull { !it.rawGrouping.isNullOrBlank() }?.rawGrouping.orEmpty()
        val knownDurations = mpdTracks.count { it.durationSec > 0f }
        return "${mpdTracks.size}:$knownDurations:$rawToc:" +
            mpdTracks.joinToString("|") { "${it.pos}:${(it.durationSec * 10).toInt()}" }
    }

    /**
     * Computes the official 28-character MusicBrainz DiscID from CD TOC sector offsets:
     * SHA-1 over 804 uppercase ASCII hex chars (`%02X` firstTrack, `%02X` lastTrack,
     * `%08X` leadoutSector, and 99 `%08X` track offsets), Base64-encoded with `.`, `_`, `-`.
     */
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
        val sha1 = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(sha1, Base64.NO_WRAP)
            .replace('+', '.')
            .replace('/', '_')
            .replace('=', '-')
    }

    /**
     * Automatically resolves metadata for the current MPD playlist using:
     * 0. Pre-existing MPD tags (`MUSICBRAINZ_ALBUMID` or `Album`/`Artist`/`Title` set via `addtagid`).
     * 1. Hardware CD TOC in `Grouping` tag (`cd-discid` / `ioctl` helper) -> Exact MusicBrainz DiscID + TOC lookup.
     * 2. MusicBrainz Fuzzy CD TOC lookup (`/ws/2/discid/-?toc=...`) when all track durations are known.
     * 3. MusicBrainz Recording duration + track count search (`/ws/2/recording`).
     * 4. Graceful fallback to `Track 01`, `Track 02` if unrecognized.
     */
    suspend fun resolveDiscMetadata(
        mpdTracks: List<MpdTrack>,
        forceRefresh: Boolean = false
    ): DiscMetadataResult = withContext(Dispatchers.IO) {
        if (mpdTracks.isEmpty()) {
            return@withContext createFallbackResult(emptyList())
        }

        val signature = computeDiscSignature(mpdTracks)
        if (!forceRefresh) {
            discCache[signature]?.let { cached ->
                if (!cached.isFallback) return@withContext cached
            }

            // Stage 0: Check if MPD's playlist tracks already carry resolved tags in RAM
            val taggedMbid = mpdTracks.firstOrNull { !it.rawMbAlbumId.isNullOrBlank() }?.rawMbAlbumId
            if (!taggedMbid.isNullOrBlank()) {
                val candidate = runCatching {
                    fetchReleaseCandidate(taggedMbid, mpdTracks, enforceStrictDurationMatch = false)
                }.getOrNull()
                if (candidate != null) {
                    val result = DiscMetadataResult(
                        isFallback = false,
                        selectedRelease = candidate,
                        candidates = listOf(candidate),
                        albumTitle = candidate.albumTitle,
                        artistName = candidate.artistName,
                        coverArtUrl = candidate.coverArtUrl,
                        tracks = candidate.tracks
                    )
                    discCache[signature] = result
                    return@withContext result
                }
            }

            val taggedAlbum = mpdTracks.firstOrNull { !it.rawAlbum.isNullOrBlank() }?.rawAlbum
            val taggedArtist = mpdTracks.firstOrNull { !it.rawArtist.isNullOrBlank() }?.rawArtist
            val hasTaggedTitles = mpdTracks.count {
                !it.rawTitle.isNullOrBlank() && !it.rawTitle.startsWith("Track ", ignoreCase = true)
            } >= (mpdTracks.size / 2).coerceAtLeast(1)

            if (!taggedAlbum.isNullOrBlank() && !taggedArtist.isNullOrBlank() && hasTaggedTitles) {
                val restoredTracks = mpdTracks.mapIndexed { idx, t ->
                    CdTrackMetadata(
                        trackNumber = idx + 1,
                        mpdPos = t.pos,
                        title = t.rawTitle?.takeIf { it.isNotBlank() }
                            ?: String.format(Locale.US, "Track %02d", idx + 1),
                        artist = t.rawArtist ?: taggedArtist,
                        durationSec = t.durationSec
                    )
                }
                val result = DiscMetadataResult(
                    isFallback = false,
                    selectedRelease = null,
                    candidates = emptyList(),
                    albumTitle = taggedAlbum,
                    artistName = taggedArtist,
                    coverArtUrl = taggedMbid?.let { "$CAA_BASE_URL/$it/front-500" },
                    tracks = restoredTracks
                )
                discCache[signature] = result
                return@withContext result
            }
        }

        val resolved = runCatching {
            queryMusicBrainzForDisc(mpdTracks)
        }.getOrNull()

        val finalResult = if (resolved != null && resolved.candidates.isNotEmpty()) {
            resolved
        } else {
            createFallbackResult(mpdTracks)
        }

        if (!finalResult.isFallback) {
            discCache[signature] = finalResult
        }
        finalResult
    }

    /**
     * Manual Album Search fallback when a physical CD is not automatically recognized.
     * Queries MusicBrainz `/ws/2/release` by album/artist name, preferring releases
     * that match the current CD's track count, and populates track titles + Cover Art Archive art.
     */
    suspend fun searchDiscByAlbumName(
        albumQuery: String,
        mpdTracks: List<MpdTrack>
    ): DiscMetadataResult = withContext(Dispatchers.IO) {
        val cleanQuery = albumQuery.trim()
        if (cleanQuery.isBlank()) {
            return@withContext createFallbackResult(mpdTracks)
        }

        val trackCount = mpdTracks.size
        // First search releases matching both the user's query and the CD's track count
        val luceneWithCount = if (trackCount > 0) {
            "($cleanQuery) AND tracks:$trackCount"
        } else {
            cleanQuery
        }

        val encodedPrimary = URLEncoder.encode(luceneWithCount, Charsets.UTF_8.name())
        var searchResp = executeGetRateLimited<MbReleaseSearchResponse>(
            "$MB_BASE_URL/release?query=$encodedPrimary&limit=10&fmt=json"
        )

        // If no exact track-count release matched, fall back to searching by album name alone
        if (searchResp?.releases.isNullOrEmpty() && trackCount > 0) {
            val encodedBroad = URLEncoder.encode(cleanQuery, Charsets.UTF_8.name())
            searchResp = executeGetRateLimited(
                "$MB_BASE_URL/release?query=$encodedBroad&limit=10&fmt=json"
            )
        }

        val stubs = searchResp?.releases.orEmpty()
        if (stubs.isEmpty()) {
            return@withContext createFallbackResult(mpdTracks)
        }

        // Pre-sort stubs so Official releases and earliest dates are hydrated first
        val prioritizedStubs = stubs.sortedWith(
            compareByDescending<MbReleaseStub> { it.status.equals("Official", ignoreCase = true) }
                .thenBy { normalizeDateForSort(it.date) }
        )

        val hydrated = mutableListOf<ReleaseCandidate>()
        for (stub in prioritizedStubs.take(MAX_CANDIDATES_TO_HYDRATE)) {
            val detail = fetchReleaseCandidate(
                releaseMbid = stub.id,
                mpdTracks = mpdTracks,
                enforceStrictDurationMatch = false
            )
            if (detail != null) {
                hydrated.add(detail)
            }
        }

        if (hydrated.isEmpty()) {
            return@withContext createFallbackResult(mpdTracks)
        }

        val sortedCandidates = rankCandidates(hydrated)
        val top = sortedCandidates.first()

        val result = DiscMetadataResult(
            isFallback = false,
            selectedRelease = top,
            candidates = sortedCandidates,
            albumTitle = top.albumTitle,
            artistName = top.artistName,
            coverArtUrl = top.coverArtUrl,
            tracks = top.tracks
        )

        if (mpdTracks.isNotEmpty()) {
            discCache[computeDiscSignature(mpdTracks)] = result
        }
        result
    }

    /**
     * Switches the active release candidate when the user selects an edition in the UI.
     */
    fun selectCandidate(
        currentResult: DiscMetadataResult,
        releaseMbid: String
    ): DiscMetadataResult {
        val chosen = currentResult.candidates.firstOrNull { it.releaseMbid == releaseMbid }
            ?: return currentResult

        return currentResult.copy(
            isFallback = false,
            selectedRelease = chosen,
            albumTitle = chosen.albumTitle,
            artistName = chosen.artistName,
            coverArtUrl = chosen.coverArtUrl,
            tracks = chosen.tracks
        )
    }

    private suspend fun queryMusicBrainzForDisc(mpdTracks: List<MpdTrack>): DiscMetadataResult? {
        val trackCount = mpdTracks.size

        // Stage 1: If Track 1 carries a hardware CD TOC in `Grouping` (from cd-discid / ioctl helper),
        // query MusicBrainz by exact cryptographic DiscID + fuzzy TOC fallback!
        val rawToc = mpdTracks.firstOrNull { !it.rawGrouping.isNullOrBlank() }?.rawGrouping
        val tocSectors = MpdSocketManager.parseHardwareTocSectors(rawToc)
        if (tocSectors != null && tocSectors.size >= 4) {
            val hwResult = queryByHardwareToc(tocSectors, mpdTracks)
            if (hwResult != null && hwResult.candidates.isNotEmpty()) {
                return hwResult
            }
        }

        val tracksWithDuration = mpdTracks.filter { it.durationMs > 0L }
        if (tracksWithDuration.isEmpty()) return null

        // Stage 2: If all tracks have known durations, query MusicBrainz's dedicated CD TOC endpoint
        if (tracksWithDuration.size == trackCount) {
            val tocResult = queryByCdToc(mpdTracks)
            if (tocResult != null && tocResult.candidates.isNotEmpty()) {
                return tocResult
            }
        }

        // Stage 3: Query `/ws/2/recording` using up to 2 tracks with known durations
        // Prefer longer/distinctive tracks which have fewer false positives in Lucene
        val anchorTracks = tracksWithDuration
            .sortedByDescending { it.durationMs }
            .take(2)

        val mbidHitCount = linkedMapOf<String, Int>()
        val mbidStubMap = mutableMapOf<String, MbReleaseStub>()

        for (anchor in anchorTracks) {
            val durMs = anchor.durationMs
            val minDur = (durMs - DURATION_TOLERANCE_MS).coerceAtLeast(1_000L)
            val maxDur = durMs + DURATION_TOLERANCE_MS
            val trackNum = anchor.pos + 1

            val luceneQuery = "dur:[$minDur TO $maxDur] AND tnum:$trackNum AND tracks:$trackCount"
            val encodedQuery = URLEncoder.encode(luceneQuery, Charsets.UTF_8.name())
            val url = "$MB_BASE_URL/recording?query=$encodedQuery&limit=40&fmt=json"

            val searchResponse = executeGetRateLimited<MbRecordingSearchResponse>(url)
            searchResponse?.recordings.orEmpty().forEach { rec ->
                rec.releases.orEmpty().forEach { rel ->
                    val matchingMedium = rel.media.orEmpty().any { medium ->
                        medium.trackCount == trackCount
                    }
                    if (matchingMedium && rel.id.isNotBlank()) {
                        mbidHitCount[rel.id] = (mbidHitCount[rel.id] ?: 0) + 1
                        mbidStubMap[rel.id] = rel
                    }
                }
            }
        }

        if (mbidHitCount.isEmpty()) return null

        // Prioritize releases that matched multiple anchor tracks, then Official status, then earliest date
        val rankedMbids = mbidHitCount.keys.sortedWith(
            compareByDescending<String> { mbidHitCount[it] ?: 0 }
                .thenByDescending { mbidStubMap[it]?.status.equals("Official", ignoreCase = true) }
                .thenBy { normalizeDateForSort(mbidStubMap[it]?.date) }
        )

        val hydratedCandidates = mutableListOf<ReleaseCandidate>()
        for (mbid in rankedMbids.take(MAX_CANDIDATES_TO_HYDRATE)) {
            val candidate = fetchReleaseCandidate(
                releaseMbid = mbid,
                mpdTracks = mpdTracks,
                enforceStrictDurationMatch = true
            )
            if (candidate != null) {
                hydratedCandidates.add(candidate)
            }
        }

        if (hydratedCandidates.isEmpty()) return null

        val sortedCandidates = rankCandidates(hydratedCandidates)
        val topCandidate = sortedCandidates.first()
        val bestCoverArt = if (topCandidate.hasCoverArtFront) {
            topCandidate.coverArtUrl
        } else {
            sortedCandidates.firstOrNull { it.hasCoverArtFront }?.coverArtUrl ?: topCandidate.coverArtUrl
        }

        return DiscMetadataResult(
            isFallback = false,
            selectedRelease = topCandidate,
            candidates = sortedCandidates,
            albumTitle = topCandidate.albumTitle,
            artistName = topCandidate.artistName,
            coverArtUrl = bestCoverArt,
            tracks = topCandidate.tracks
        )
    }

    /**
     * Queries MusicBrainz using exact hardware CD TOC sector offsets (`[1, N, leadout, offset1, ...]`).
     * Computes the exact 28-character MusicBrainz DiscID and also passes `?toc=...` for fuzzy fallback.
     */
    private suspend fun queryByHardwareToc(
        tocSectors: List<Int>,
        mpdTracks: List<MpdTrack>
    ): DiscMetadataResult? {
        val firstTrack = tocSectors[0]
        val lastTrack = tocSectors[1]
        val leadoutSector = tocSectors[2]
        val offsets = tocSectors.drop(3)
        if (offsets.size != lastTrack) return null

        val discId = computeMusicBrainzDiscId(firstTrack, lastTrack, leadoutSector, offsets)
        val tocParam = tocSectors.joinToString("+")

        val url = "$MB_BASE_URL/discid/$discId?toc=$tocParam&inc=recordings+artist-credits&fmt=json"
        val tocResp = executeGetRateLimited<MbDiscTocResponse>(url) ?: return null
        val releases = tocResp.releases.orEmpty()
        if (releases.isEmpty()) return null

        val candidates = releases.mapNotNull { release ->
            buildCandidateFromLookup(
                release = release,
                mpdTracks = mpdTracks,
                enforceStrictDurationMatch = false
            )
        }
        if (candidates.isEmpty()) return null

        val sorted = rankCandidates(candidates)
        val top = sorted.first()
        val bestCoverArt = if (top.hasCoverArtFront) {
            top.coverArtUrl
        } else {
            sorted.firstOrNull { it.hasCoverArtFront }?.coverArtUrl ?: top.coverArtUrl
        }

        return DiscMetadataResult(
            isFallback = false,
            selectedRelease = top,
            candidates = sorted,
            albumTitle = top.albumTitle,
            artistName = top.artistName,
            coverArtUrl = bestCoverArt,
            tracks = top.tracks
        )
    }

    /**
     * Queries MusicBrainz `/ws/2/discid/-?toc=...` using Redbook 75-frames/sec sector offsets.
     */
    private suspend fun queryByCdToc(mpdTracks: List<MpdTrack>): DiscMetadataResult? {
        var currentSector = 150
        val trackOffsets = IntArray(mpdTracks.size)
        for (i in mpdTracks.indices) {
            trackOffsets[i] = currentSector
            val frames = (mpdTracks[i].durationSec * 75f).roundToInt().coerceAtLeast(75)
            currentSector += frames
        }
        val leadoutSector = currentSector
        val tocSectors = buildList {
            add(1)
            add(mpdTracks.size)
            add(leadoutSector)
            for (offset in trackOffsets) add(offset)
        }
        return queryByHardwareToc(tocSectors, mpdTracks)
    }

    private suspend fun fetchReleaseCandidate(
        releaseMbid: String,
        mpdTracks: List<MpdTrack>,
        enforceStrictDurationMatch: Boolean
    ): ReleaseCandidate? {
        val url = "$MB_BASE_URL/release/$releaseMbid?inc=recordings+artist-credits+media&fmt=json"
        val release = executeGetRateLimited<MbReleaseLookupResponse>(url) ?: return null
        return buildCandidateFromLookup(release, mpdTracks, enforceStrictDurationMatch)
    }

    private fun buildCandidateFromLookup(
        release: MbReleaseLookupResponse,
        mpdTracks: List<MpdTrack>,
        enforceStrictDurationMatch: Boolean
    ): ReleaseCandidate? {
        val mediaList = release.media.orEmpty()
        val matchingMedium = mediaList.firstOrNull { it.tracks.orEmpty().size == mpdTracks.size }
            ?: if (!enforceStrictDurationMatch) mediaList.firstOrNull { !it.tracks.isNullOrEmpty() } else null
        if (matchingMedium == null) return null

        val mbTracks = matchingMedium.tracks.orEmpty()
        var totalDeltaMs = 0L
        var comparedCount = 0

        val limit = minOf(mpdTracks.size, mbTracks.size)
        for (i in 0 until limit) {
            val mpdMs = mpdTracks[i].durationMs
            val mbMs = mbTracks[i].length ?: mbTracks[i].recording?.length ?: 0L
            if (mpdMs > 0L && mbMs > 0L) {
                val delta = abs(mpdMs - mbMs)
                if (enforceStrictDurationMatch && delta > 10_000L) {
                    return null
                }
                totalDeltaMs += delta
                comparedCount++
            }
        }

        val avgDeltaMs = if (comparedCount > 0) totalDeltaMs / comparedCount else 0L
        val releaseArtist = release.artistCredit
            .orEmpty()
            .joinToString("") { "${it.name}${it.joinphrase.orEmpty()}" }
            .ifBlank { "Unknown Artist" }

        val targetTracks = if (mpdTracks.isNotEmpty()) {
            mpdTracks.mapIndexed { index, mpdTrack ->
                val mbTrack = mbTracks.getOrNull(index)
                val resolvedTitle = mbTrack?.title
                    ?.takeIf { it.isNotBlank() }
                    ?: mbTrack?.recording?.title?.takeIf { it.isNotBlank() }
                    ?: String.format(Locale.US, "Track %02d", index + 1)

                val trackArtist = (mbTrack?.artistCredit ?: mbTrack?.recording?.artistCredit)
                    .orEmpty()
                    .joinToString("") { "${it.name}${it.joinphrase.orEmpty()}" }
                    .ifBlank { releaseArtist }

                val mbDurationSec = ((mbTrack?.length ?: mbTrack?.recording?.length ?: 0L) / 1000f)
                val finalDurationSec = if (mpdTrack.durationSec > 0f) mpdTrack.durationSec else mbDurationSec

                CdTrackMetadata(
                    trackNumber = index + 1,
                    mpdPos = mpdTrack.pos,
                    title = resolvedTitle,
                    artist = trackArtist,
                    durationSec = finalDurationSec
                )
            }
        } else {
            mbTracks.mapIndexed { index, mbTrack ->
                val resolvedTitle = mbTrack.title
                    ?.takeIf { it.isNotBlank() }
                    ?: mbTrack.recording?.title?.takeIf { it.isNotBlank() }
                    ?: String.format(Locale.US, "Track %02d", index + 1)
                val trackArtist = (mbTrack.artistCredit ?: mbTrack.recording?.artistCredit)
                    .orEmpty()
                    .joinToString("") { "${it.name}${it.joinphrase.orEmpty()}" }
                    .ifBlank { releaseArtist }
                val mbDurationSec = ((mbTrack.length ?: mbTrack.recording?.length ?: 0L) / 1000f)
                CdTrackMetadata(
                    trackNumber = index + 1,
                    mpdPos = index,
                    title = resolvedTitle,
                    artist = trackArtist,
                    durationSec = mbDurationSec
                )
            }
        }

        val hasFrontArt = release.coverArtArchive?.front == true
        val caaFrontUrl = "$CAA_BASE_URL/${release.id}/front-500"

        return ReleaseCandidate(
            releaseMbid = release.id,
            albumTitle = release.title.ifBlank { "Compact Disc" },
            artistName = releaseArtist,
            status = release.status,
            hasCoverArtFront = hasFrontArt,
            releaseDate = release.date,
            country = release.country,
            disambiguation = release.disambiguation,
            coverArtUrl = caaFrontUrl,
            tracks = targetTracks,
            avgDurationDeltaMs = avgDeltaMs
        )
    }

    /**
     * Multiple Results Disambiguation ranking:
     * 1) Duration accuracy bucket (`avgDurationDeltaMs / 2500L` so exact TOC matches beat fuzzy matches)
     * 2) `release.status == "Official"`
     * 3) `release.coverArtArchive.front == true`
     * 4) earliest `date`
     * 5) exact `avgDurationDeltaMs`
     */
    private fun rankCandidates(candidates: List<ReleaseCandidate>): List<ReleaseCandidate> =
        candidates.sortedWith(
            compareBy<ReleaseCandidate> { it.avgDurationDeltaMs / 2_500L }
                .thenByDescending { it.isOfficial }
                .thenByDescending { it.hasCoverArtFront }
                .thenBy { normalizeDateForSort(it.releaseDate) }
                .thenBy { it.avgDurationDeltaMs }
        )

    /**
     * Retro CD Deck fallback when offline or unrecognized Redbook audio CD.
     */
    fun createFallbackResult(mpdTracks: List<MpdTrack>): DiscMetadataResult {
        val fallbackAlbum = mpdTracks.firstOrNull { !it.rawAlbum.isNullOrBlank() }?.rawAlbum
            ?: if (mpdTracks.isEmpty()) "No Disc Inserted" else "Unknown Audio CD"
        val fallbackArtist = mpdTracks.firstOrNull { !it.rawArtist.isNullOrBlank() }?.rawArtist
            ?: if (mpdTracks.isEmpty()) "Insert a CD & tap Refresh" else "Tap to enter album name"

        val fallbackTracks = mpdTracks.mapIndexed { index, track ->
            val trackNo = index + 1
            val defaultName = String.format(Locale.US, "Track %02d", trackNo)
            val cleanTitle = track.rawTitle
                ?.takeIf { it.isNotBlank() && !it.startsWith("track", ignoreCase = true) }
                ?: defaultName

            CdTrackMetadata(
                trackNumber = trackNo,
                mpdPos = track.pos,
                title = cleanTitle,
                artist = track.rawArtist ?: fallbackArtist,
                durationSec = track.durationSec
            )
        }

        return DiscMetadataResult(
            isFallback = true,
            selectedRelease = null,
            candidates = emptyList(),
            albumTitle = fallbackAlbum,
            artistName = fallbackArtist,
            coverArtUrl = null,
            tracks = fallbackTracks
        )
    }

    private fun normalizeDateForSort(date: String?): String {
        if (date.isNullOrBlank()) return "9999-99-99"
        return when (date.length) {
            4 -> "$date-99-99"
            7 -> "$date-99"
            else -> date
        }
    }

    /**
     * Executes a GET request against MusicBrainz respecting the 1 req/sec rate limit
     * and retrying automatically once if MusicBrainz returns HTTP 503 / 429.
     */
    private suspend inline fun <reified T> executeGetRateLimited(urlString: String): T? {
        repeat(2) { attempt ->
            rateLimitMutex.withLock {
                val now = System.currentTimeMillis()
                val elapsed = now - lastRequestTimestampMs
                if (elapsed < MIN_MB_REQUEST_INTERVAL_MS) {
                    delay(MIN_MB_REQUEST_INTERVAL_MS - elapsed)
                }
                lastRequestTimestampMs = System.currentTimeMillis()
            }

            val request = Request.Builder()
                .url(urlString.toHttpUrl())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            var shouldRetry = false
            val parsed: T? = runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 503 || response.code == 429) {
                        shouldRetry = true
                        return@use null
                    }
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    json.decodeFromString<T>(body)
                }
            }.getOrNull()

            if (parsed != null) return parsed
            if (!shouldRetry || attempt == 1) return null
            delay(1_400L)
        }
        return null
    }
}

// -----------------------------------------------------------------------------
// MusicBrainz WS/2 JSON DTOs
// -----------------------------------------------------------------------------

@Serializable
data class MbRecordingSearchResponse(
    val recordings: List<MbRecordingItem>? = null
)

@Serializable
data class MbRecordingItem(
    val id: String = "",
    val title: String = "",
    val length: Long? = null,
    val releases: List<MbReleaseStub>? = null
)

@Serializable
data class MbReleaseSearchResponse(
    val releases: List<MbReleaseStub>? = null
)

@Serializable
data class MbDiscTocResponse(
    val releases: List<MbReleaseLookupResponse>? = null
)

@Serializable
data class MbReleaseStub(
    val id: String = "",
    val title: String = "",
    val status: String? = null,
    val date: String? = null,
    val country: String? = null,
    val media: List<MbMediumStub>? = null
)

@Serializable
data class MbMediumStub(
    @SerialName("track-count")
    val trackCount: Int = 0
)

@Serializable
data class MbReleaseLookupResponse(
    val id: String = "",
    val title: String = "",
    val status: String? = null,
    val date: String? = null,
    val country: String? = null,
    val disambiguation: String? = null,
    @SerialName("artist-credit")
    val artistCredit: List<MbArtistCredit>? = null,
    @SerialName("cover-art-archive")
    val coverArtArchive: MbCoverArtArchive? = null,
    val media: List<MbMediumDetail>? = null
)

@Serializable
data class MbArtistCredit(
    val name: String = "",
    val joinphrase: String? = null
)

@Serializable
data class MbCoverArtArchive(
    val artwork: Boolean = false,
    val front: Boolean = false,
    val back: Boolean = false
)

@Serializable
data class MbMediumDetail(
    val position: Int = 1,
    val format: String? = null,
    val tracks: List<MbTrackDetail>? = null
)

@Serializable
data class MbTrackDetail(
    val id: String = "",
    val number: String? = null,
    val title: String? = null,
    val length: Long? = null,
    @SerialName("artist-credit")
    val artistCredit: List<MbArtistCredit>? = null,
    val recording: MbRecordingStub? = null
)

@Serializable
data class MbRecordingStub(
    val id: String = "",
    val title: String? = null,
    val length: Long? = null,
    @SerialName("artist-credit")
    val artistCredit: List<MbArtistCredit>? = null
)

@Serializable
data class LrcLibItem(
    val id: Long = 0L,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null
)
