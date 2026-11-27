@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.streammate.pro

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

data class TrackOption(val group: TrackGroup, val index: Int, val label: String)

@Composable
fun ProPlayerScreen(
    channel: Channel,
    allChannels: List<Channel>,
    epgTitle: String,
    isFavorite: Boolean,
    settings: AppSettings,
    accentColor: Color,
    isInPipMode: Boolean,
    onSelectChannel: (Channel) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleDecoder: () -> Unit,
    onEnterPip: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val scope = rememberCoroutineScope()
    val userAgent = remember(settings) { resolveUserAgent(settings) }

    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var showChannelDrawer by remember { mutableStateOf(false) }
    var showTrackDialog by remember { mutableStateOf(false) }
    var resizeModeIndex by remember { mutableIntStateOf(0) }
    var reconnectMsg by remember { mutableStateOf("") }
    var gestureHudText by remember { mutableStateOf("") }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    val recorder = remember { DvrRecorder() }
    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }

    val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Full",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "16:9"
    )

    val exoPlayer = remember(channel.url, userAgent, settings.bufferMode, settings.decoderMode) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val (minBuf, maxBuf, playBuf, rebuf) = when (settings.bufferMode) {
            "High" -> listOf(15000, 50000, 2500, 5000)
            "Balanced" -> listOf(8000, 25000, 1500, 3000)
            else -> listOf(4000, 12000, 1000, 2000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxBuf, playBuf, rebuf)
            .build()

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            if (settings.decoderMode == "SW") {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            }
        }

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .build().apply {
                setMediaItem(MediaItem.fromUri(channel.url))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        var retryCount = 0
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    retryCount = 0
                    reconnectMsg = ""
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (retryCount < 5) {
                    retryCount++
                    reconnectMsg = "Reconnecting ($retryCount/5)..."
                    scope.launch {
                        delay(2000)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }
                } else {
                    reconnectMsg = "Stream offline or blocked"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            recorder.stop()
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(showControls, isLocked, showChannelDrawer) {
        if (showControls && !isLocked && !showChannelDrawer) {
            delay(5000)
            showControls = false
        }
    }
    LaunchedEffect(gestureHudText) {
        if (gestureHudText.isNotEmpty()) {
            delay(1200)
            gestureHudText = ""
        }
    }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordSeconds = 0
            while (isRecording) {
                delay(1000)
                recordSeconds++
            }
        }
    }

    fun zapChannel(offset: Int) {
        if (allChannels.isEmpty()) return
        val idx = allChannels.indexOfFirst { it.id == channel.id }
        if (idx != -1) {
            val nextIdx = (idx + offset + allChannels.size) % allChannels.size
            onSelectChannel(allChannels[nextIdx])
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && !showChannelDrawer && !showTrackDialog) {
                    when (ev.key) {
                        Key.DirectionUp -> { zapChannel(-1); true }
                        Key.DirectionDown -> { zapChannel(1); true }
                        Key.DirectionCenter, Key.Enter -> { showControls = !showControls; true }
                        else -> false
                    }
                } else false
            }
            .onSizeChanged { boxSize = it }
            .pointerInput(isLocked, showChannelDrawer) {
                detectTapGestures {
                    if (!isInPipMode) {
                        if (showChannelDrawer) {
                            showChannelDrawer = false
                        } else {
                            showControls = !showControls
                        }
                    }
                }
            }
            .pointerInput(isLocked, boxSize) {
                var brightness = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                var volFloat = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol

                detectVerticalDragGestures { change, dragAmount ->
                    if (isLocked || isInPipMode || showChannelDrawer || boxSize.height == 0) return@detectVerticalDragGestures
                    val delta = -dragAmount / (boxSize.height * 0.7f)
                    if (change.position.x < boxSize.width / 2f) {
                        brightness = (brightness + delta).coerceIn(0.05f, 1f)
                        activity?.window?.let { win ->
                            val lp = win.attributes
                            lp.screenBrightness = brightness
                            win.attributes = lp
                        }
                        gestureHudText = "Brightness: ${(brightness * 100).toInt()}%"
                    } else {
                        volFloat = (volFloat + delta).coerceIn(0f, 1f)
                        val newVol = (volFloat * maxVol).toInt().coerceIn(0, maxVol)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        gestureHudText = "Volume: ${(volFloat * 100).toInt()}%"
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                view.player = exoPlayer
                view.resizeMode = resizeModes[resizeModeIndex].first
            },
            modifier = Modifier.fillMaxSize()
        )
              if (isInPipMode) return@Box

        if (reconnectMsg.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xCC000000)
            ) {
                Text(reconnectMsg, color = accentColor, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }

        if (gestureHudText.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xCC131318)
            ) {
                Text(gestureHudText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }

        if (isLocked && showControls) {
            IconButton(
                onClick = { isLocked = false; showControls = true },
                modifier = Modifier.align(Alignment.CenterStart).padding(20.dp).clip(CircleShape).background(Color(0x99000000))
            ) {
                Icon(Icons.Default.LockOpen, "Unlock", tint = accentColor)
            }
        }

        // Touch-Friendly Overlay (Tap anywhere above to hide immediately!)
        if (showControls && !isLocked && !showChannelDrawer) {
            // Minimal Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color(0xBB000000), Color.Transparent)))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                Text(channel.group, color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${settings.decoderMode} • ${settings.uaPreset}", color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            // Center Play/Pause + 10s Seek
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0)) },
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0x77000000))
                ) { Icon(Icons.Default.Replay10, "Back 10s", tint = Color.White) }

                IconButton(
                    onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    modifier = Modifier.size(58.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.9f))
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Color.Black, modifier = Modifier.size(32.dp))
                }

                IconButton(
                    onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 10000) },
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0x77000000))
                ) { Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White) }
            }

            // TiviMate-Style Bottom Info + Scrollable Action Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60B0D12))))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    if (channel.logo.isNotBlank()) {
                        AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(epgTitle.ifBlank { "Live • ${channel.group}" }, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerActionPill(Icons.Default.List, "Channels", Color.White) { showChannelDrawer = true }

                    val mins = recordSeconds / 60
                    val secs = recordSeconds % 60
                    PlayerActionPill(
                        icon = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        label = if (isRecording) String.format("%02d:%02d", mins, secs) else "Record",
                        tint = if (isRecording) Color(0xFFFF3B30) else Color.White
                    ) {
                        if (isRecording) {
                            recorder.stop()
                            isRecording = false
                            Toast.makeText(context, "Saved in Movies/StreamMatePro", Toast.LENGTH_SHORT).show()
                        } else {
                            isRecording = true
                            recorder.start(context, scope, channel.url, channel.name, userAgent) { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    PlayerActionPill(Icons.Default.PictureInPictureAlt, "PiP", Color.White, onEnterPip)
                    PlayerActionPill(Icons.Default.AspectRatio, resizeModes[resizeModeIndex].second, Color.White) {
                        resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                    }
                    PlayerActionPill(Icons.Default.Subtitles, "Audio/Sub", Color.White) { showTrackDialog = true }
                    PlayerActionPill(Icons.Default.Build, settings.decoderMode, accentColor, onToggleDecoder)
                    PlayerActionPill(
                        if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        "Favorite",
                        if (isFavorite) Color(0xFFFFD54F) else Color.White,
                        onToggleFavorite
                    )
                    PlayerActionPill(Icons.Default.Lock, "Lock", Color.White) { isLocked = true }
                }
            }
        }

        // In-Player Channel Drawer
        if (showChannelDrawer) {
            Surface(
                modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().width(260.dp),
                color = Color(0xEE10131A)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Channels", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { showChannelDrawer = false }) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(allChannels) { ch ->
                            val active = ch.id == channel.id
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (active) accentColor.copy(alpha = 0.25f) else CardDark,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    showChannelDrawer = false
                                    onSelectChannel(ch)
                                }
                            ) {
                                Text(ch.name, color = if (active) accentColor else TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
                            }
                        }
                    }
                }
            }
        }

        // Audio & Subtitle Dialog
        if (showTrackDialog) {
            val audioTracks = mutableListOf<TrackOption>()
            val textTracks = mutableListOf<TrackOption>()
            exoPlayer.currentTracks.groups.forEach { grp ->
                for (i in 0 until grp.length) {
                    val fmt = grp.getTrackFormat(i)
                    val label = fmt.label ?: fmt.language?.uppercase() ?: "Track ${i + 1}"
                    if (grp.type == C.TRACK_TYPE_AUDIO) audioTracks.add(TrackOption(grp.mediaTrackGroup, i, label))
                    if (grp.type == C.TRACK_TYPE_TEXT) textTracks.add(TrackOption(grp.mediaTrackGroup, i, label))
                }
            }
            AlertDialog(
                onDismissRequest = { showTrackDialog = false },
                containerColor = CardElevated,
                title = { Text("Audio & Subtitles", color = TextPrimary) },
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Text("Audio", color = accentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        if (audioTracks.isEmpty()) item { Text("Default Audio", color = TextSecondary, fontSize = 12.sp) }
                        items(audioTracks) { opt ->
                            Text(opt.label, color = TextPrimary, modifier = Modifier.fillMaxWidth().clickable {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(opt.group, opt.index)).build()
                                showTrackDialog = false
                            }.padding(8.dp))
                        }
                        item { Spacer(Modifier.height(6.dp)); Text("Subtitles", color = accentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        item {
                            Text("Off", color = TextSecondary, modifier = Modifier.fillMaxWidth().clickable {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                                showTrackDialog = false
                            }.padding(8.dp))
                        }
                        items(textTracks) { opt ->
                            Text(opt.label, color = TextPrimary, modifier = Modifier.fillMaxWidth().clickable {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(TrackSelectionOverride(opt.group, opt.index)).build()
                                showTrackDialog = false
                            }.padding(8.dp))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showTrackDialog = false }) { Text("Close", color = accentColor) } }
            )
        }
    }
}

@Composable
fun PlayerActionPill(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

// Live Mini-Preview Player for Top Preview Box
@Composable
fun MiniPreviewPlayer(streamUrl: String, userAgent: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val previewPlayer = remember(streamUrl, userAgent) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2000, 6000, 1000, 1500)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .build().apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(previewPlayer) {
        onDispose { previewPlayer.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = previewPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { it.player = previewPlayer },
        modifier = modifier
    )
}
