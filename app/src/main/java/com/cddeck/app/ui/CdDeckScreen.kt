@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)

package com.cddeck.app.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Eject
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cddeck.app.data.CdTrackMetadata
import com.cddeck.app.data.ReleaseCandidate
import com.cddeck.app.data.TrackLyrics
import com.cddeck.app.network.MpdConnectionState
import com.cddeck.app.network.RepeatMode
import kotlin.math.PI
import kotlin.math.sin

// -----------------------------------------------------------------------------
// Material 3 Expressive Color Palette (Matched to Reference Design)
// -----------------------------------------------------------------------------

private val DeckBackground = Color(0xFF18191E)
private val DeckSurface = Color(0xFF21232A)
private val CircleButtonSurface = Color(0xFF2B2D35)
private val IntroTonalContainer = Color(0xFF333E58)
private val ExpressivePeriwinkle = Color(0xFFBAC3FF)
private val ExpressiveWaveTint = Color(0xFF9EA9E8)
private val OnPeriwinkleDark = Color(0xFF111625)
private val PrimaryWhiteText = Color(0xFFF2F3F8)
private val SecondaryMutedText = Color(0xFF8E929F)
private val TrackInactiveLine = Color(0xFF3A3C45)
private val StatusLedGreen = Color(0xFF4ADE80)
private val StatusLedAmber = Color(0xFFFFB74D)
private val StatusLedRed = Color(0xFFEF5350)

private val CdDeckExpressiveColorScheme = darkColorScheme(
    primary = ExpressivePeriwinkle,
    onPrimary = OnPeriwinkleDark,
    primaryContainer = IntroTonalContainer,
    onPrimaryContainer = PrimaryWhiteText,
    secondary = ExpressiveWaveTint,
    onSecondary = OnPeriwinkleDark,
    secondaryContainer = CircleButtonSurface,
    onSecondaryContainer = PrimaryWhiteText,
    surface = DeckSurface,
    onSurface = PrimaryWhiteText,
    surfaceVariant = CircleButtonSurface,
    onSurfaceVariant = SecondaryMutedText,
    background = DeckBackground,
    onBackground = PrimaryWhiteText
)

@Composable
fun CdDeckExpressiveTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = CdDeckExpressiveColorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

@Composable
fun CdDeckScreen(
    viewModel: CdDeckViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var dominantTint by remember { mutableStateOf(DeckBackground) }
    var showTracklistSheet by remember { mutableStateOf(false) }
    var showAlbumVersionSheet by remember { mutableStateOf(false) }
    var showManualSearchDialog by remember { mutableStateOf(false) }
    var showHostDialog by remember { mutableStateOf(false) }

    CdDeckExpressiveTheme {
        val animatedAmbientTint by animateColorAsState(
            targetValue = if (uiState.coverArtUrl != null) dominantTint else DeckBackground,
            animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
            label = "ambientTint"
        )

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            DeckBackground,
                            animatedAmbientTint.copy(alpha = 0.16f),
                            DeckBackground
                        )
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Top Header Bar: [ Green LED | MPD: IP | Gear ] ... [ Refresh ]
                TopMpdHeaderBar(
                    host = uiState.mpdHost,
                    connectionState = uiState.connectionState,
                    isRefreshing = uiState.isRefreshingDisc || uiState.isMetadataLoading,
                    onOpenSettings = { showHostDialog = true },
                    onRefresh = viewModel::onRefreshDisc
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Large Rounded Square Album Artwork Card (Flippable to Scrollable Lyrics)
                AlbumArtworkCard(
                    coverArtUrl = uiState.coverArtUrl,
                    albumTitle = uiState.albumTitle,
                    artistName = uiState.artistName,
                    trackTitle = uiState.currentTrackTitle,
                    lyrics = uiState.currentLyrics,
                    isLyricsLoading = uiState.isLyricsLoading,
                    elapsedSec = uiState.elapsedSec,
                    lyricsOffsetSec = uiState.lyricsOffsetSec,
                    isPlaying = uiState.isPlaying,
                    isLoading = uiState.isMetadataLoading || uiState.isRefreshingDisc || uiState.isDriveSeeking,
                    onSeekTo = viewModel::onSeekTo,
                    onAdjustLyricsOffset = viewModel::onAdjustLyricsOffset,
                    onDominantColorExtracted = { dominantTint = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 3. Left-Aligned Song Title & Album Subtitle (+ Manual Album Search & Version Picker)
                TrackMetadataSection(
                    trackTitle = uiState.currentTrackTitle,
                    albumSubtitle = if (uiState.isFallbackMetadata) {
                        uiState.albumTitle
                    } else {
                        "${uiState.albumTitle} • ${uiState.artistName}"
                    },
                    isFallbackMetadata = uiState.isFallbackMetadata,
                    releaseCandidates = uiState.releaseCandidates,
                    selectedReleaseMbid = uiState.selectedReleaseMbid,
                    onOpenManualSearch = { showManualSearchDialog = true },
                    onOpenVersionSheet = { showAlbumVersionSheet = true }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 4. M3 Expressive Wavy Scrubber + Elapsed & Duration Timestamps
                WavyScrubberSection(
                    elapsedSec = uiState.elapsedSec,
                    durationSec = uiState.durationSec,
                    formattedElapsed = uiState.formattedElapsed,
                    formattedDuration = uiState.formattedDuration,
                    isPlaying = uiState.isPlaying,
                    onSeekTo = viewModel::onSeekTo
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 5. Primary Transport Row: [ ( |<< ) ]  [ ( || Pause / > Play ) ]  [ ( >>| ) ]
                PrimaryTransportRow(
                    isPlaying = uiState.isPlaying,
                    onPrevious = viewModel::onPreviousTrack,
                    onPlayPause = viewModel::onPlayPauseToggle,
                    onNext = viewModel::onNextTrack
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 6. Bottom Mode & Tracklist Row: [ INTRO (10s) ]  [ ( ^ ) ]  [ REPEAT (1) ]
                BottomModeBar(
                    isIntroActive = uiState.isIntroScanActive,
                    introCountdownSec = uiState.introScanCountdownSec,
                    repeatMode = uiState.repeatMode,
                    onToggleIntro = viewModel::onToggleIntroScan,
                    onOpenTracklist = { showTracklistSheet = true },
                    onCycleRepeat = viewModel::onCycleRepeatMode
                )
            }

            // Slide-Up Tracklist Modal Bottom Sheet
            if (showTracklistSheet) {
                TracklistBottomSheet(
                    albumTitle = uiState.albumTitle,
                    artistName = uiState.artistName,
                    tracks = uiState.tracks,
                    currentTrackIndex = uiState.currentTrackIndex,
                    isPlaying = uiState.isPlaying,
                    isShuffleEnabled = uiState.isShuffleEnabled,
                    isRefreshing = uiState.isRefreshingDisc || uiState.isMetadataLoading,
                    onToggleShuffle = viewModel::onToggleShuffle,
                    onRefreshDisc = viewModel::onRefreshDisc,
                    onSelectTrack = { pos ->
                        viewModel.onSelectTrack(pos)
                        showTracklistSheet = false
                    },
                    onDismiss = { showTracklistSheet = false }
                )
            }

            // MusicBrainz Multiple Release Disambiguation Sheet
            if (showAlbumVersionSheet && uiState.releaseCandidates.isNotEmpty()) {
                ReleaseDisambiguationBottomSheet(
                    candidates = uiState.releaseCandidates,
                    selectedMbid = uiState.selectedReleaseMbid,
                    onSelectCandidate = { mbid ->
                        viewModel.onSelectReleaseCandidate(mbid)
                        showAlbumVersionSheet = false
                    },
                    onDismiss = { showAlbumVersionSheet = false }
                )
            }

            // MPD IP & Port Settings Dialog
            if (showHostDialog) {
                MpdEndpointDialog(
                    currentHost = uiState.mpdHost,
                    currentPort = uiState.mpdPort,
                    onConfirm = { host, port ->
                        viewModel.connectToMpd(host, port)
                        showHostDialog = false
                    },
                    onDismiss = { showHostDialog = false }
                )
            }

            // Manual MusicBrainz Album Search Dialog
            if (showManualSearchDialog) {
                ManualAlbumSearchDialog(
                    initialQuery = if (uiState.isFallbackMetadata) "" else uiState.albumTitle,
                    onSearch = { query ->
                        viewModel.onSearchAlbumManually(query)
                        showManualSearchDialog = false
                    },
                    onDismiss = { showManualSearchDialog = false }
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 1. Top Header Bar: [ Green LED • MPD: 192.168.86.254 • Gear ] ... [ Eject • Refresh ]
// -----------------------------------------------------------------------------

@Composable
private fun TopMpdHeaderBar(
    host: String,
    connectionState: MpdConnectionState,
    isRefreshing: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    val ledColor = when (connectionState) {
        is MpdConnectionState.Connected -> StatusLedGreen
        is MpdConnectionState.Connecting -> StatusLedAmber
        is MpdConnectionState.Error -> StatusLedRed
        MpdConnectionState.Disconnected -> SecondaryMutedText
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left cluster: Glowing Status LED + "MPD: <IP>" + Settings Gear Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Glowing LED Indicator
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(ledColor.copy(alpha = 0.28f))
                )
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(ledColor)
                )
            }

            Text(
                text = "MPD: $host",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.4.sp
                ),
                color = Color(0xFFD0D3DC),
                modifier = Modifier.clickable { onOpenSettings() }
            )

            // Circular M3 Expressive Settings Icon Button
            FilledTonalIconToggleButton(
                checked = false,
                onCheckedChange = { onOpenSettings() },
                shapes = IconButtonDefaults.shapes(
                    shape = IconButtonDefaults.smallRoundShape,
                    pressedShape = IconButtonDefaults.smallPressedShape,
                    checkedShape = IconButtonDefaults.smallSquareShape
                ),
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    containerColor = CircleButtonSurface,
                    contentColor = Color(0xFFC8CBD4)
                ),
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "MPD Server Settings",
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        // Right: Forget-and-Refresh Button (resets cached album & re-queries tracks without hardware seeking)
        val infiniteTransition = rememberInfiniteTransition(label = "refreshRotation")
        val rotationDeg by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing)
            ),
            label = "refreshSpin"
        )

        FilledTonalIconToggleButton(
            checked = isRefreshing,
            onCheckedChange = { onRefresh() },
            shapes = IconButtonDefaults.shapes(
                shape = IconButtonDefaults.smallRoundShape,
                pressedShape = IconButtonDefaults.smallPressedShape,
                checkedShape = IconButtonDefaults.smallSquareShape
            ),
            colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                containerColor = CircleButtonSurface,
                contentColor = Color(0xFFC8CBD4),
                checkedContainerColor = IntroTonalContainer,
                checkedContentColor = ExpressivePeriwinkle
            ),
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "Forget Album & Refresh Tracks",
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (isRefreshing) rotationDeg else 0f)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// 2. Center Stage Square Album Art Card
// -----------------------------------------------------------------------------

@Composable
private fun AlbumArtworkCard(
    coverArtUrl: String?,
    albumTitle: String,
    artistName: String,
    trackTitle: String,
    lyrics: TrackLyrics?,
    isLyricsLoading: Boolean,
    elapsedSec: Float,
    lyricsOffsetSec: Float,
    isPlaying: Boolean,
    isLoading: Boolean,
    onSeekTo: (Float) -> Unit,
    onAdjustLyricsOffset: (Float) -> Unit,
    onDominantColorExtracted: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "discRotation")
    val discAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8_000, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "discSpinAngle"
    )

    var imageLoadSucceeded by remember(coverArtUrl) { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }

    val flipRotationY by animateFloatAsState(
        targetValue = if (showLyrics) 180f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "lyricsCardFlip"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                rotationY = flipRotationY
                cameraDistance = 14f * density
            }
            .shadow(
                elevation = 20.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color.Black,
                spotColor = Color.Black
            )
            .clip(RoundedCornerShape(28.dp))
            .background(DeckSurface),
        contentAlignment = Alignment.Center
    ) {
        if (flipRotationY <= 90f) {
            // FRONT FACE: Album Cover Art
            if (coverArtUrl == null || !imageLoadSucceeded) {
                FallbackDiscArtwork(
                    albumTitle = albumTitle,
                    artistName = artistName,
                    rotationDegrees = if (isPlaying) discAngle else 18f
                )
            }

            if (coverArtUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(coverArtUrl)
                        .crossfade(true)
                        .allowHardware(false)
                        .listener(
                            onSuccess = { _, result ->
                                imageLoadSucceeded = true
                                val bitmap: Bitmap? = (result.drawable as? BitmapDrawable)?.bitmap
                                if (bitmap != null) {
                                    Palette.from(bitmap).generate { palette ->
                                        val rgb = palette?.getDominantColor(0xFF18191E.toInt())
                                            ?: 0xFF18191E.toInt()
                                        onDominantColorExtracted(Color(rgb))
                                    }
                                }
                            },
                            onError = { _, _ ->
                                imageLoadSucceeded = false
                            }
                        )
                        .build(),
                    contentDescription = albumTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // M3 Expressive ContainedLoadingIndicator while scanning CD TOC / querying MusicBrainz
            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                ContainedLoadingIndicator(
                    containerColor = DeckBackground.copy(alpha = 0.82f),
                    indicatorColor = ExpressivePeriwinkle
                )
            }

            // Bottom-Right Corner Flip-to-Lyrics Button
            FilledTonalIconToggleButton(
                checked = lyrics != null,
                onCheckedChange = { showLyrics = true },
                shapes = IconButtonDefaults.shapes(
                    shape = IconButtonDefaults.smallRoundShape,
                    pressedShape = IconButtonDefaults.smallPressedShape,
                    checkedShape = IconButtonDefaults.smallRoundShape
                ),
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    containerColor = DeckBackground.copy(alpha = 0.78f),
                    contentColor = SecondaryMutedText,
                    checkedContainerColor = DeckBackground.copy(alpha = 0.84f),
                    checkedContentColor = ExpressivePeriwinkle
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lyrics,
                    contentDescription = "Flip to Lyrics",
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            // BACK FACE: Scrollable Lyrics Card (un-mirrored via rotationY = 180f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f }
            ) {
                ScrollableLyricsFace(
                    trackTitle = trackTitle,
                    artistName = artistName,
                    lyrics = lyrics,
                    isLyricsLoading = isLyricsLoading,
                    elapsedSec = elapsedSec,
                    lyricsOffsetSec = lyricsOffsetSec,
                    onSeekTo = onSeekTo,
                    onAdjustLyricsOffset = onAdjustLyricsOffset,
                    onFlipBack = { showLyrics = false }
                )
            }
        }
    }
}

@Composable
private fun ScrollableLyricsFace(
    trackTitle: String,
    artistName: String,
    lyrics: TrackLyrics?,
    isLyricsLoading: Boolean,
    elapsedSec: Float,
    lyricsOffsetSec: Float,
    onSeekTo: (Float) -> Unit,
    onAdjustLyricsOffset: (Float) -> Unit,
    onFlipBack: () -> Unit
) {
    val listState = rememberLazyListState()

    val effectiveLyricsTimeSec = (elapsedSec + lyricsOffsetSec).coerceAtLeast(0f)

    val activeLineIndex = remember(lyrics, effectiveLyricsTimeSec) {
        val lines = lyrics?.lines ?: return@remember -1
        if (!lyrics.isSynced) return@remember -1
        lines.indexOfLast { line ->
            val t = line.timestampSec
            t != null && t <= effectiveLyricsTimeSec
        }
    }

    LaunchedEffect(activeLineIndex) {
        if (activeLineIndex >= 0) {
            listState.animateScrollToItem(
                index = (activeLineIndex - 1).coerceAtLeast(0)
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1F222C),
                        Color(0xFF171920)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            // Lyrics Card Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = trackTitle,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = PrimaryWhiteText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artistName,
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondaryMutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (lyrics?.isSynced == true) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = CircleButtonSurface,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { onAdjustLyricsOffset(-0.5f) }
                        ) {
                            Text(
                                text = "-0.5s",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = PrimaryWhiteText,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            color = IntroTonalContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(50)
                        ) {
                            val offsetLabel = if (kotlin.math.abs(lyricsOffsetSec) < 0.05f) {
                                "SYNC"
                            } else {
                                String.format(java.util.Locale.US, "%+.1fs", lyricsOffsetSec)
                            }
                            Text(
                                text = offsetLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = ExpressivePeriwinkle,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            color = CircleButtonSurface,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { onAdjustLyricsOffset(0.5f) }
                        ) {
                            Text(
                                text = "+0.5s",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = PrimaryWhiteText,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                    }
                } else {
                    Surface(
                        color = IntroTonalContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "LYRICS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = ExpressivePeriwinkle,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                isLyricsLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ContainedLoadingIndicator(
                                containerColor = CircleButtonSurface,
                                indicatorColor = ExpressivePeriwinkle
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Loading lyrics...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SecondaryMutedText
                            )
                        }
                    }
                }
                lyrics == null || lyrics.lines.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No lyrics found for this track.\nIdentify the album first or try another song.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SecondaryMutedText,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 56.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(lyrics.lines) { index, line ->
                            val isActive = lyrics.isSynced && index == activeLineIndex
                            val lineColor = when {
                                isActive -> ExpressivePeriwinkle
                                lyrics.isSynced && index < activeLineIndex -> SecondaryMutedText.copy(alpha = 0.55f)
                                lyrics.isSynced -> PrimaryWhiteText.copy(alpha = 0.78f)
                                else -> PrimaryWhiteText.copy(alpha = 0.90f)
                            }

                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = if (isActive) 20.sp else 17.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    lineHeight = 25.sp
                                ),
                                color = lineColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = line.timestampSec != null) {
                                        line.timestampSec?.let { onSeekTo(it) }
                                    }
                                    .padding(vertical = 2.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Bottom-Right Corner Flip-Back-to-Cover-Art Button
        FilledTonalIconToggleButton(
            checked = true,
            onCheckedChange = { onFlipBack() },
            shapes = IconButtonDefaults.shapes(
                shape = IconButtonDefaults.smallRoundShape,
                pressedShape = IconButtonDefaults.smallPressedShape,
                checkedShape = IconButtonDefaults.smallRoundShape
            ),
            colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                containerColor = CircleButtonSurface,
                contentColor = PrimaryWhiteText,
                checkedContainerColor = ExpressivePeriwinkle,
                checkedContentColor = OnPeriwinkleDark
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
                .size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Album,
                contentDescription = "Flip Back to Album Art",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun FallbackDiscArtwork(
    albumTitle: String,
    artistName: String,
    rotationDegrees: Float
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF2B2E39),
                        Color(0xFF1C1E26),
                        Color(0xFF13151B)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize(0.78f)
                .aspectRatio(1f)
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.minDimension / 2f

            drawCircle(
                color = Color(0xFF1B1D24),
                radius = outerRadius,
                center = center
            )

            rotate(degrees = rotationDegrees, pivot = center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        0.0f to Color(0xFF1B1D24),
                        0.15f to ExpressivePeriwinkle.copy(alpha = 0.22f),
                        0.28f to Color(0xFF1B1D24),
                        0.65f to ExpressiveWaveTint.copy(alpha = 0.18f),
                        0.78f to Color(0xFF1B1D24),
                        1.0f to Color(0xFF1B1D24),
                        center = center
                    ),
                    radius = outerRadius * 0.96f,
                    center = center
                )
            }

            val grooveCount = 12
            for (i in 1..grooveCount) {
                val r = outerRadius * (0.34f + (i / grooveCount.toFloat()) * 0.58f)
                drawCircle(
                    color = Color.White.copy(alpha = if (i % 3 == 0) 0.08f else 0.03f),
                    radius = r,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            drawCircle(
                color = Color(0xFF2C303C),
                radius = outerRadius * 0.28f,
                center = center
            )
            drawCircle(
                color = ExpressivePeriwinkle.copy(alpha = 0.35f),
                radius = outerRadius * 0.28f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawCircle(
                color = DeckBackground,
                radius = outerRadius * 0.10f,
                center = center
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp, start = 20.dp, end = 20.dp)
        ) {
            Text(
                text = albumTitle.uppercase(),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp
                ),
                color = PrimaryWhiteText.copy(alpha = 0.90f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = artistName,
                style = MaterialTheme.typography.bodySmall.copy(
                    letterSpacing = 0.8.sp
                ),
                color = SecondaryMutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// -----------------------------------------------------------------------------
// 3. Left-Aligned Song Title & Album Subtitle Block
// -----------------------------------------------------------------------------

@Composable
private fun TrackMetadataSection(
    trackTitle: String,
    albumSubtitle: String,
    isFallbackMetadata: Boolean,
    releaseCandidates: List<ReleaseCandidate>,
    selectedReleaseMbid: String?,
    onOpenManualSearch: () -> Unit,
    onOpenVersionSheet: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onOpenManualSearch() }
        ) {
            Text(
                text = trackTitle,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp
                ),
                color = PrimaryWhiteText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isFallbackMetadata) "$albumSubtitle (Tap to set album)" else albumSubtitle,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = SecondaryMutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (releaseCandidates.size > 1) {
                val activeCandidate = releaseCandidates.firstOrNull {
                    it.releaseMbid == selectedReleaseMbid
                } ?: releaseCandidates.first()

                TonalToggleButton(
                    checked = true,
                    onCheckedChange = { onOpenVersionSheet() },
                    shapes = ToggleButtonDefaults.shapes(),
                    colors = ToggleButtonDefaults.tonalToggleButtonColors(
                        checkedContainerColor = CircleButtonSurface,
                        checkedContentColor = ExpressivePeriwinkle
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = "Switch Album Version",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = activeCandidate.releaseDate?.take(4) ?: "VER (${releaseCandidates.size})",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            // M3 Expressive Manual Album Lookup Button
            FilledTonalIconToggleButton(
                checked = false,
                onCheckedChange = { onOpenManualSearch() },
                shapes = IconButtonDefaults.shapes(
                    shape = IconButtonDefaults.smallRoundShape,
                    pressedShape = IconButtonDefaults.smallPressedShape,
                    checkedShape = IconButtonDefaults.smallSquareShape
                ),
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    containerColor = CircleButtonSurface,
                    contentColor = ExpressivePeriwinkle
                ),
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Search Album Name on MusicBrainz",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 4. M3 Expressive Wavy Scrubber (`~~~|---`) + Timestamps (`1:39` ... `3:16`)
// -----------------------------------------------------------------------------

@Composable
private fun WavyScrubberSection(
    elapsedSec: Float,
    durationSec: Float,
    formattedElapsed: String,
    formattedDuration: String,
    isPlaying: Boolean,
    onSeekTo: (Float) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val totalSec = durationSec.coerceAtLeast(1f)
    val liveFraction = (elapsedSec / totalSec).coerceIn(0f, 1f)
    val displayFraction = if (isDragging) dragFraction else liveFraction

    val infiniteTransition = rememberInfiniteTransition(label = "wavePhase")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "wavePhaseShift"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(totalSec) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeekTo(fraction * totalSec)
                    }
                }
                .pointerInput(totalSec) {
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            isDragging = true
                            dragFraction = (startOffset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            isDragging = false
                            onSeekTo(dragFraction * totalSec)
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val widthPx = size.width
                val centerY = size.height / 2f
                val activeEndX = (widthPx * displayFraction).coerceIn(0f, widthPx)

                val waveAmplitude = 6.5.dp.toPx()
                val waveLength = 36.dp.toPx()
                val activeStroke = 7.dp.toPx()
                val inactiveStroke = 6.dp.toPx()
                val thumbWidth = 6.5.dp.toPx()
                val thumbHeight = 24.dp.toPx()

                // 1. Inactive straight horizontal track on the right
                if (activeEndX < widthPx) {
                    drawLine(
                        color = TrackInactiveLine,
                        start = Offset(activeEndX + thumbWidth, centerY),
                        end = Offset(widthPx, centerY),
                        strokeWidth = inactiveStroke,
                        cap = StrokeCap.Round
                    )
                }

                // 2. Active sinusoidal wavy track on the left
                if (activeEndX > 2f) {
                    val path = Path()
                    val stepPx = 2f
                    val phase = if (isPlaying && !isDragging) wavePhase else 0f
                    var x = 0f
                    var firstPoint = true
                    while (x <= activeEndX) {
                        val angle = ((x / waveLength) * (2f * PI.toFloat())) - phase
                        val y = centerY + sin(angle) * waveAmplitude
                        if (firstPoint) {
                            path.moveTo(x, y)
                            firstPoint = false
                        } else {
                            path.lineTo(x, y)
                        }
                        x += stepPx
                    }
                    drawPath(
                        path = path,
                        color = ExpressiveWaveTint,
                        style = Stroke(width = activeStroke, cap = StrokeCap.Round)
                    )
                }

                // 3. Vertical rounded pill thumb indicator `|`
                val thumbLeft = (activeEndX - thumbWidth / 2f).coerceIn(0f, (widthPx - thumbWidth).coerceAtLeast(0f))
                drawRoundRect(
                    color = ExpressivePeriwinkle,
                    topLeft = Offset(thumbLeft, centerY - thumbHeight / 2f),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(thumbWidth / 2f, thumbWidth / 2f)
                )
            }
        }

        // Elapsed (`1:39`) and Total Duration (`3:16`) row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val shownElapsed = if (isDragging) {
                CdDeckUiState.formatSecondsCompact(dragFraction * totalSec)
            } else {
                formattedElapsed
            }
            Text(
                text = shownElapsed,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                color = SecondaryMutedText
            )
            Text(
                text = formattedDuration,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                color = SecondaryMutedText
            )
        }
    }
}

// -----------------------------------------------------------------------------
// 5. Primary Transport Row: [ ( |<< ) ]  [ ( || Pause ) ]  [ ( >>| ) ]
// -----------------------------------------------------------------------------

@Composable
private fun PrimaryTransportRow(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    ButtonGroup(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Left Circular [ |<< ] Button
        TonalToggleButton(
            checked = false,
            onCheckedChange = { onPrevious() },
            shapes = ToggleButtonDefaults.shapes(
                shape = CircleShape,
                pressedShape = ToggleButtonDefaults.pressedShape,
                checkedShape = CircleShape
            ),
            colors = ToggleButtonDefaults.tonalToggleButtonColors(
                containerColor = CircleButtonSurface,
                contentColor = PrimaryWhiteText
            ),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(68.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = "Previous Track",
                modifier = Modifier.size(30.dp)
            )
        }

        // Center Wide Pill [ || Pause / > Play ] Button
        ToggleButton(
            checked = isPlaying,
            onCheckedChange = { onPlayPause() },
            shapes = ToggleButtonDefaults.shapes(
                shape = RoundedCornerShape(50),
                pressedShape = ToggleButtonDefaults.pressedShape,
                checkedShape = RoundedCornerShape(50)
            ),
            colors = ToggleButtonDefaults.toggleButtonColors(
                containerColor = ExpressivePeriwinkle,
                contentColor = OnPeriwinkleDark,
                checkedContainerColor = ExpressivePeriwinkle,
                checkedContentColor = OnPeriwinkleDark
            ),
            modifier = Modifier
                .weight(1f)
                .height(68.dp)
        ) {
            if (isPlaying) {
                OutlinedDoublePauseIcon(
                    color = OnPeriwinkleDark,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Pause",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Play",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }

        // Right Circular [ >>| ] Button
        TonalToggleButton(
            checked = false,
            onCheckedChange = { onNext() },
            shapes = ToggleButtonDefaults.shapes(
                shape = CircleShape,
                pressedShape = ToggleButtonDefaults.pressedShape,
                checkedShape = CircleShape
            ),
            colors = ToggleButtonDefaults.tonalToggleButtonColors(
                containerColor = CircleButtonSurface,
                contentColor = PrimaryWhiteText
            ),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(68.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "Next Track",
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

/**
 * Draws the twin outlined vertical pause bars (`||`) matching the reference screenshot.
 */
@Composable
private fun OutlinedDoublePauseIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val barWidth = size.width * 0.28f
        val barHeight = size.height * 0.86f
        val topY = (size.height - barHeight) / 2f
        val leftBarX = size.width * 0.14f
        val rightBarX = size.width - leftBarX - barWidth
        val strokePx = 2.dp.toPx()
        val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())

        drawRoundRect(
            color = color,
            topLeft = Offset(leftBarX, topY),
            size = Size(barWidth, barHeight),
            cornerRadius = radius,
            style = Stroke(width = strokePx)
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(rightBarX, topY),
            size = Size(barWidth, barHeight),
            cornerRadius = radius,
            style = Stroke(width = strokePx)
        )
    }
}

// -----------------------------------------------------------------------------
// 6. Bottom Mode & Drawer Row: [ INTRO (10s) ]  [ ( ^ ) ]  [ REPEAT (1) ]
// -----------------------------------------------------------------------------

@Composable
private fun BottomModeBar(
    isIntroActive: Boolean,
    introCountdownSec: Int,
    repeatMode: RepeatMode,
    onToggleIntro: () -> Unit,
    onOpenTracklist: () -> Unit,
    onCycleRepeat: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: [ INTRO ] pill with top-right "10s" M3 Badge
        BadgedBox(
            badge = {
                Badge(
                    containerColor = ExpressivePeriwinkle,
                    contentColor = OnPeriwinkleDark,
                    modifier = Modifier.offset(x = (-6).dp, y = 4.dp)
                ) {
                    val badgeLabel = if (isIntroActive) "${introCountdownSec}s" else "10s"
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
            }
        ) {
            TonalToggleButton(
                checked = isIntroActive,
                onCheckedChange = { onToggleIntro() },
                shapes = ToggleButtonDefaults.shapes(
                    shape = RoundedCornerShape(50),
                    pressedShape = ToggleButtonDefaults.pressedShape,
                    checkedShape = RoundedCornerShape(50)
                ),
                colors = ToggleButtonDefaults.tonalToggleButtonColors(
                    containerColor = IntroTonalContainer,
                    contentColor = PrimaryWhiteText,
                    checkedContainerColor = ExpressivePeriwinkle,
                    checkedContentColor = OnPeriwinkleDark
                ),
                modifier = Modifier
                    .width(106.dp)
                    .height(48.dp)
            ) {
                Text(
                    text = "INTRO",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.8.sp
                    )
                )
            }
        }

        // Center: Circular [ ^ ] Tracklist Sheet Trigger
        FilledTonalIconToggleButton(
            checked = false,
            onCheckedChange = { onOpenTracklist() },
            shapes = IconButtonDefaults.shapes(
                shape = CircleShape,
                pressedShape = IconButtonDefaults.smallPressedShape,
                checkedShape = CircleShape
            ),
            colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                containerColor = CircleButtonSurface,
                contentColor = PrimaryWhiteText
            ),
            modifier = Modifier.size(46.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = "Open Tracklist",
                modifier = Modifier.size(24.dp)
            )
        }

        // Right: [ REPEAT ] pill with top-right "1" / "ALL" M3 Badge
        val isRepeatActive = repeatMode != RepeatMode.OFF
        BadgedBox(
            badge = {
                if (isRepeatActive) {
                    Badge(
                        containerColor = Color(0xFFD9E0FF),
                        contentColor = OnPeriwinkleDark,
                        modifier = Modifier.offset(x = (-6).dp, y = 4.dp)
                    ) {
                        val repeatBadgeText = when (repeatMode) {
                            RepeatMode.ONE -> "1"
                            RepeatMode.ALL -> "ALL"
                            RepeatMode.OFF -> ""
                        }
                        Text(
                            text = repeatBadgeText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        ) {
            TonalToggleButton(
                checked = isRepeatActive,
                onCheckedChange = { onCycleRepeat() },
                shapes = ToggleButtonDefaults.shapes(
                    shape = RoundedCornerShape(50),
                    pressedShape = ToggleButtonDefaults.pressedShape,
                    checkedShape = RoundedCornerShape(50)
                ),
                colors = ToggleButtonDefaults.tonalToggleButtonColors(
                    containerColor = IntroTonalContainer,
                    contentColor = PrimaryWhiteText,
                    checkedContainerColor = ExpressivePeriwinkle,
                    checkedContentColor = OnPeriwinkleDark
                ),
                modifier = Modifier
                    .width(106.dp)
                    .height(48.dp)
            ) {
                Text(
                    text = "REPEAT",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.8.sp
                    )
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 7. Slide-Up Tracklist Modal Bottom Sheet
// -----------------------------------------------------------------------------

@Composable
private fun TracklistBottomSheet(
    albumTitle: String,
    artistName: String,
    tracks: List<CdTrackMetadata>,
    currentTrackIndex: Int,
    isPlaying: Boolean,
    isShuffleEnabled: Boolean,
    isRefreshing: Boolean,
    onToggleShuffle: () -> Unit,
    onRefreshDisc: () -> Unit,
    onSelectTrack: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeckSurface,
        contentColor = PrimaryWhiteText
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = albumTitle,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = PrimaryWhiteText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondaryMutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconToggleButton(
                        checked = isShuffleEnabled,
                        onCheckedChange = { onToggleShuffle() },
                        shapes = IconButtonDefaults.shapes(
                            shape = IconButtonDefaults.smallRoundShape,
                            pressedShape = IconButtonDefaults.smallPressedShape,
                            checkedShape = IconButtonDefaults.smallSquareShape
                        ),
                        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                            containerColor = CircleButtonSurface,
                            contentColor = SecondaryMutedText,
                            checkedContainerColor = ExpressivePeriwinkle,
                            checkedContentColor = OnPeriwinkleDark
                        ),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Toggle Shuffle",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    FilledTonalIconToggleButton(
                        checked = isRefreshing,
                        onCheckedChange = { onRefreshDisc() },
                        shapes = IconButtonDefaults.shapes(
                            shape = IconButtonDefaults.smallRoundShape,
                            pressedShape = IconButtonDefaults.smallPressedShape,
                            checkedShape = IconButtonDefaults.smallSquareShape
                        ),
                        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                            containerColor = CircleButtonSurface,
                            contentColor = ExpressivePeriwinkle
                        ),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Rescan CD",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No disc detected — insert a CD and tap Refresh",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SecondaryMutedText
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(tracks, key = { it.trackNumber }) { track ->
                        val isCurrent = (track.mpdPos == currentTrackIndex)
                        val rowContainer = if (isCurrent) {
                            IntroTonalContainer
                        } else {
                            CircleButtonSurface.copy(alpha = 0.65f)
                        }

                        Surface(
                            onClick = { onSelectTrack(track.mpdPos) },
                            modifier = Modifier.fillMaxWidth(),
                            color = rowContainer,
                            shape = RoundedCornerShape(16.dp),
                            border = if (isCurrent) {
                                BorderStroke(1.dp, ExpressivePeriwinkle.copy(alpha = 0.55f))
                            } else {
                                null
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = track.formattedNumber,
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = if (isCurrent) ExpressivePeriwinkle else SecondaryMutedText
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                                        ),
                                        color = PrimaryWhiteText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isCurrent && isPlaying) {
                                        Icon(
                                            imageVector = Icons.Rounded.GraphicEq,
                                            contentDescription = "Now Playing",
                                            tint = ExpressivePeriwinkle,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = CdDeckUiState.formatSecondsCompact(track.durationSec),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = if (isCurrent) ExpressivePeriwinkle else SecondaryMutedText
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 8. MusicBrainz Multiple Release Disambiguation Sheet
// -----------------------------------------------------------------------------

@Composable
private fun ReleaseDisambiguationBottomSheet(
    candidates: List<ReleaseCandidate>,
    selectedMbid: String?,
    onSelectCandidate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeckSurface,
        contentColor = PrimaryWhiteText
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Select Album Version",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = PrimaryWhiteText
            )
            Text(
                text = "Ranked by Official status, Cover Art availability, and earliest release date.",
                style = MaterialTheme.typography.bodySmall,
                color = SecondaryMutedText
            )

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(candidates, key = { it.releaseMbid }) { candidate ->
                    val isSelected = candidate.releaseMbid == selectedMbid
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelectCandidate(candidate.releaseMbid) },
                        color = if (isSelected) IntroTonalContainer else CircleButtonSurface,
                        shape = RoundedCornerShape(16.dp),
                        border = if (isSelected) {
                            BorderStroke(1.dp, ExpressivePeriwinkle)
                        } else {
                            null
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = candidate.albumTitle,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = PrimaryWhiteText
                                )
                                Text(
                                    text = "${candidate.artistName} • ${candidate.displayBadge}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = SecondaryMutedText
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Selected Release",
                                    tint = ExpressivePeriwinkle
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 9. MPD Host & Port Configuration Dialog
// -----------------------------------------------------------------------------

@Composable
private fun MpdEndpointDialog(
    currentHost: String,
    currentPort: Int,
    onConfirm: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var hostInput by remember(currentHost) { mutableStateOf(currentHost) }
    var portInput by remember(currentPort) { mutableStateOf(currentPort.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeckSurface,
        titleContentColor = PrimaryWhiteText,
        textContentColor = SecondaryMutedText,
        title = {
            Text(
                text = "MPD Server Endpoint",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    label = { Text("MPD Host / IP") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter(Char::isDigit) },
                    label = { Text("TCP Port (Default 6600)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val port = portInput.toIntOrNull() ?: 6600
                    onConfirm(hostInput.ifBlank { CdDeckViewModel.DEFAULT_MPD_HOST }, port)
                }
            ) {
                Text("SAVE & CONNECT", color = ExpressivePeriwinkle)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = SecondaryMutedText)
            }
        }
    )
}

@Composable
private fun ManualAlbumSearchDialog(
    initialQuery: String,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var queryInput by remember(initialQuery) { mutableStateOf(initialQuery) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeckSurface,
        titleContentColor = PrimaryWhiteText,
        textContentColor = SecondaryMutedText,
        title = {
            Text(
                text = "Identify CD on MusicBrainz",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter the Album Title (or Artist + Album) to load track names and cover art:",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryMutedText
                )
                OutlinedTextField(
                    value = queryInput,
                    onValueChange = { queryInput = it },
                    label = { Text("Album or Artist + Album") },
                    placeholder = { Text("e.g. Daft Punk Discovery") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (queryInput.isNotBlank()) {
                        onSearch(queryInput)
                    }
                }
            ) {
                Text("SEARCH", color = ExpressivePeriwinkle)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = SecondaryMutedText)
            }
        }
    )
}
