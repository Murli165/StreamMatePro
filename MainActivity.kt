package com.streammate.pro

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiPrefs(
    val accentName: String = "Cyan",
    val fontSize: String = "Normal",
    val showNumbers: Boolean = true,
    val showLogos: Boolean = true,
    val clock24h: Boolean = false
)

fun resolveAccent(name: String): Color = when (name) {
    "Blue" -> Color(0xFF2979FF)
    "Purple" -> Color(0xFFAB47BC)
    "Green" -> Color(0xFF00E676)
    "Orange" -> Color(0xFFFF9100)
    else -> AccentCyan
}

class MainActivity : ComponentActivity() {
    private var isPlayerActive = false
    private val inPipState = mutableStateOf(false)

    private fun enterPipIfPossible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPlayerActive) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) { }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfPossible()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipState.value = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BgBlack,
                    surface = CardDark,
                    primary = AccentCyan,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary
                )
            ) {
                StreamMateTvApp(
                    isInPipMode = inPipState.value,
                    onPlayerActiveChanged = { isPlayerActive = it },
                    onRequestPip = { enterPipIfPossible() }
                )
            }
        }
    }
}

@Composable
fun StreamMateTvApp(
    isInPipMode: Boolean,
    onPlayerActiveChanged: (Boolean) -> Unit,
    onRequestPip: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("streammate_nuvio", Context.MODE_PRIVATE) }

    var activeMenu by remember { mutableStateOf("TV") }
    var showSettingsDrawer by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("All channels") }
    var searchQuery by remember { mutableStateOf("") }

    var sources by remember { mutableStateOf(loadSources(prefs)) }
    var settings by remember { mutableStateOf(loadSettings(prefs)) }
    var uiPrefs by remember {
        mutableStateOf(
            UiPrefs(
                accentName = prefs.getString("ui_accent", "Cyan") ?: "Cyan",
                fontSize = prefs.getString("ui_font", "Normal") ?: "Normal",
                showNumbers = prefs.getBoolean("ui_nums", true),
                showLogos = prefs.getBoolean("ui_logos", true),
                clock24h = prefs.getBoolean("ui_24h", false)
            )
        )
    }

    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var epgMap by remember { mutableStateOf>(emptyMap()) }
    var favoriteIds by remember { mutableStateOf(prefs.getStringSet("favs", emptySet()) ?: emptySet()) }
    var recentIds by remember { mutableStateOf(loadRecentIds(prefs)) }
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    var playingChannel by remember { mutableStateOf<Channel?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    val accentColor = remember(uiPrefs.accentName) { resolveAccent(uiPrefs.accentName) }
    val itemFontSp = when (uiPrefs.fontSize) {
        "Small" -> 11.sp
        "Large" -> 15.sp
        else -> 13.sp
    }

    LaunchedEffect(playingChannel) { onPlayerActiveChanged(playingChannel != null) }

    fun saveUiPrefs(u: UiPrefs) {
        uiPrefs = u
        prefs.edit()
            .putString("ui_accent", u.accentName)
            .putString("ui_font", u.fontSize)
            .putBoolean("ui_nums", u.showNumbers)
            .putBoolean("ui_logos", u.showLogos)
            .putBoolean("ui_24h", u.clock24h)
            .apply()
    }

    fun refreshAll(sourceList: List<PlaylistSource>, activeSettings: AppSettings = settings) {
        if (sourceList.isEmpty()) {
            channels = emptyList()
            statusMsg = "Open Settings -> Playlists to add source"
            return
        }
        scope.launch {
            isLoading = true
            statusMsg = "Loading (${activeSettings.uaPreset})..."
            val ua = resolveUserAgent(activeSettings)
            val allLoaded = mutableListOf<Channel>()
            for (src in sourceList) {
                when (src.type) {
                    "M3U" -> allLoaded.addAll(fetchAndParseM3U(src.url, ua))
                    "XTREAM" -> allLoaded.addAll(fetchXtreamApi(src.url, src.username, src.password, ua))
                    "STALKER" -> allLoaded.addAll(fetchStalkerApi(src.url, src.mac, src.sn, src.deviceId1, src.deviceId2, ua))
                }
            }
            channels = allLoaded.distinctBy { it.url }
            if (focusedChannel == null && channels.isNotEmpty()) focusedChannel = channels.first()
            if (activeSettings.epgUrl.isNotBlank()) {
                epgMap = fetchEpgMap(activeSettings.epgUrl, ua)
            }
            statusMsg = if (channels.isEmpty()) "0 channels found. Check URL/UA." else "${channels.size} items loaded"
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { if (sources.isNotEmpty()) refreshAll(sources) }

    fun toggleFav(ch: Channel) {
        val upd = favoriteIds.toMutableSet()
        if (upd.contains(ch.id)) upd.remove(ch.id) else upd.add(ch.id)
        favoriteIds = upd
        prefs.edit().putStringSet("favs", upd).apply()
    }

    fun playFullscreen(ch: Channel) {
        focusedChannel = ch
        playingChannel = ch
        val upd = (listOf(ch.id) + recentIds.filter { it != ch.id }).take(20)
        recentIds = upd
        prefs.edit().putString("recents", upd.joinToString(",")).apply()
    }

    fun getEpgFor(ch: Channel?): String {
        if (ch == null) return ""
        return epgMap[ch.tvgId]?.value ?: epgMap[ch.name]?.value ?: ""
        
    }

    if (playingChannel != null) {
        BackHandler { playingChannel = null }
        ProPlayerScreen(
            channel = playingChannel!!,
            allChannels = channels,
            epgTitle = getEpgFor(playingChannel),
            isFavorite = favoriteIds.contains(playingChannel!!.id),
            settings = settings,
            accentColor = accentColor,
            isInPipMode = isInPipMode,
            onSelectChannel = ::playFullscreen,
            onToggleFavorite = { toggleFav(playingChannel!!) },
            onToggleDecoder = {
                val next = if (settings.decoderMode == "HW") "SW" else "HW"
                val upd = settings.copy(decoderMode = next)
                settings = upd
                saveSettings(prefs, upd)
                Toast.makeText(context, "Decoder: $next", Toast.LENGTH_SHORT).show()
            },
            onEnterPip = onRequestPip,
            onBack = { playingChannel = null }
        )
        return
    }

    val mediaFiltered = remember(channels, activeMenu) {
        when (activeMenu) {
            "Movies" -> channels.filter { it.mediaType == "MOVIE" || it.group.contains("movie", true) }
            "Series" -> channels.filter { it.mediaType == "SERIES" || it.group.contains("series", true) }
            else -> channels.filter { it.mediaType == "LIVE" }
        }
    }

    val categories = remember(mediaFiltered) {
        listOf("Favorites", "All channels") + mediaFiltered.map { it.group }.distinct().sorted()
    }

    val displayedChannels = remember(channels, mediaFiltered, activeMenu, selectedCategory, searchQuery, favoriteIds) {
        when (activeMenu) {
            "Search" -> if (searchQuery.isBlank()) channels.take(50) else channels.filter { it.name.contains(searchQuery, true) || it.group.contains(searchQuery, true) }
            "My list" -> channels.filter { favoriteIds.contains(it.id) }
            else -> when (selectedCategory) {
                "Favorites" -> mediaFiltered.filter { favoriteIds.contains(it.id) }
                "All channels" -> mediaFiltered
                else -> mediaFiltered.filter { it.group == selectedCategory }
            }
        }
    }

    val clockPattern = if (uiPrefs.clock24h) "EEE, dd MMM, HH:mm" else "EEE, dd MMM, h:mm a"
    val nowTime = remember(uiPrefs.clock24h) { SimpleDateFormat(clockPattern, Locale.getDefault()).format(Date()) }
    val ua = remember(settings) { resolveUserAgent(settings) }

    Box(modifier = Modifier.fillMaxSize().background(BgBlack).statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 1. LEFT SIDEBAR (StreamMate Branding)
            Column(
                modifier = Modifier.width(136.dp).fillMaxHeight().background(SidebarBg).padding(vertical = 12.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("StreamMate", color = accentColor, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                    SidebarNavItem(Icons.Default.Search, "Search", activeMenu == "Search", accentColor) { activeMenu = "Search" }
                    SidebarNavItem(Icons.Default.Tv, "TV", activeMenu == "TV", accentColor) { activeMenu = "TV"; selectedCategory = "All channels" }
                    SidebarNavItem(Icons.Default.Movie, "Movies", activeMenu == "Movies", accentColor) { activeMenu = "Movies"; selectedCategory = "All channels" }
                    SidebarNavItem(Icons.Default.VideoLibrary, "Series", activeMenu == "Series", accentColor) { activeMenu = "Series"; selectedCategory = "All channels" }
                    SidebarNavItem(Icons.Default.FiberManualRecord, "Recordings", activeMenu == "Recordings", accentColor) { activeMenu = "Recordings" }
                    SidebarNavItem(Icons.Default.BookmarkBorder, "My list", activeMenu == "My list", accentColor) { activeMenu = "My list" }
                }
                SidebarNavItem(Icons.Default.Settings, "Settings", showSettingsDrawer, accentColor) { showSettingsDrawer = true }
            }

            // 2. MIDDLE CATEGORIES COLUMN
            if (activeMenu == "TV" || activeMenu == "Movies" || activeMenu == "Series") {
                LazyColumn(
                    modifier = Modifier.width(142.dp).fillMaxHeight().background(Color(0xFF0E1117)).padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(categories) { cat ->
                        val active = cat == selectedCategory
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) CardElevated else Color.Transparent)
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 10.dp, vertical = 9.dp)
                        ) {
                            Text(cat, color = if (active) Color.White else TextSecondary, fontSize = itemFontSp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // 3. RIGHT MAIN AREA (Live Mini-Preview + Numbered List)
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(10.dp)) {
                if (activeMenu == "Search") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search channels, movies, series...", color = TextSecondary, fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                } else if (activeMenu == "Recordings") {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("DVR Recordings are saved in:\nMovies/StreamMatePro/", color = TextSecondary, fontSize = 14.sp)
                    }
                    return@Column
                } else {
                    Row(modifier = Modifier.fillMaxWidth().height(96.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .width(166.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black)
                                .clickable { focusedChannel?.let { playFullscreen(it) } },
                            contentAlignment = Alignment.Center
                        ) {
                            if (focusedChannel != null && !showSettingsDrawer) {
                                MiniPreviewPlayer(focusedChannel!!.url, ua, Modifier.fillMaxSize())
                                Box(modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text("Tap Fullscreen", color = Color.White, fontSize = 9.sp)
                                }
                            } else {
                                Text("Preview", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(focusedChannel?.name ?: "Select a channel", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(getEpgFor(focusedChannel).ifBlank { statusMsg.ifBlank { "1st tap: Preview • 2nd tap: Fullscreen" } }, color = TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("$nowTime  •  ${focusedChannel?.group ?: selectedCategory}", color = accentColor, fontSize = 11.sp)
                        }
                    }
                }

                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accentColor) }
                } else if (displayedChannels.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No items. Open Settings -> Playlists to add M3U, Xtream or Stalker.", color = TextSecondary, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(displayedChannels) { idx, ch ->
                            val isFav = favoriteIds.contains(ch.id)
                            val isFocused = focusedChannel?.id == ch.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isFocused) CardElevated else CardDark)
                                    .clickable {
                                        if (isFocused) playFullscreen(ch) else focusedChannel = ch
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (uiPrefs.showNumbers) {
                                    Text("${idx + 1}", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(30.dp))
                                }
                                if (uiPrefs.showLogos) {
                                    if (ch.logo.isNotBlank()) AsyncImage(model = ch.logo, contentDescription = null, modifier = Modifier.size(26.dp), contentScale = ContentScale.Fit)
                                    else Icon(Icons.Default.LiveTv, null, tint = accentColor, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text(ch.name, color = TextPrimary, fontSize = itemFontSp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.2f))
                                Box(
                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1D222B)).padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(getEpgFor(ch).ifBlank { "No information" }, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { toggleFav(ch) }, modifier = Modifier.size(28.dp)) {
                                    Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, null, tint = if (isFav) Color(0xFFFFD54F) else TextSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. RIGHT-SIDE SETTINGS DRAWER
        if (showSettingsDrawer) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0x99000000)).clickable { showSettingsDrawer = false })
            SettingsDrawerPanel(
                modifier = Modifier.align(Alignment.CenterEnd),
                prefs = prefs,
                sources = sources,
                settings = settings,
                uiPrefs = uiPrefs,
                accentColor = accentColor,
                onUpdateSettings = { newS -> settings = newS; saveSettings(prefs, newS) },
                onUpdateUiPrefs = ::saveUiPrefs,
                onAddSource = { newSrc ->
                    val upd = sources + newSrc
                    sources = upd
                    saveSources(prefs, upd)
                    refreshAll(upd)
                },
                onDeleteSource = { del ->
                    val upd = sources.filter { it.id != del.id }
                    sources = upd
                    saveSources(prefs, upd)
                    refreshAll(upd)
                },
                onRefreshAll = { refreshAll(sources) },
                onRestored = {
                    sources = loadSources(prefs)
                    settings = loadSettings(prefs)
                    favoriteIds = prefs.getStringSet("favs", emptySet()) ?: emptySet()
                    refreshAll(sources, settings)
                },
                onClose = { showSettingsDrawer = false }
            )
        }
    }
}
@Composable
fun SidebarNavItem(icon: ImageVector, label: String, selected: Boolean, accentColor: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) CardElevated else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, label, tint = if (selected) accentColor else TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (selected) Color.White else TextSecondary, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun SettingsDrawerPanel(
    modifier: Modifier,
    prefs: android.content.SharedPreferences,
    sources: List<PlaylistSource>,
    settings: AppSettings,
    uiPrefs: UiPrefs,
    accentColor: Color,
    onUpdateSettings: (AppSettings) -> Unit,
    onUpdateUiPrefs: (UiPrefs) -> Unit,
    onAddSource: (PlaylistSource) -> Unit,
    onDeleteSource: (PlaylistSource) -> Unit,
    onRefreshAll: () -> Unit,
    onRestored: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var subMenu by remember { mutableStateOf("ROOT") }
    var addDialogType by remember { mutableStateOf<String?>(null) }
    var restoreInput by remember { mutableStateOf("") }
    var showRestoreBox by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxHeight().width(310.dp), color = Color(0xFF181C24), shadowElevation = 16.dp) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                if (subMenu != "ROOT") {
                    IconButton(onClick = { subMenu = "ROOT" }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (subMenu == "ROOT") "Settings" else subMenu, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Close", tint = TextSecondary) }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (subMenu) {
                    "ROOT" -> {
                        item { SettingsRowItem("General (User-Agent)") { subMenu = "General" } }
                        item { SettingsRowItem("Playlists (${sources.size})") { subMenu = "Playlists" } }
                        item { SettingsRowItem("EPG (TV Guide)") { subMenu = "EPG" } }
                        item { SettingsRowItem("Appearance (UI)") { subMenu = "Appearance" } }
                        item { SettingsRowItem("Playback (Buffer & Decoder)") { subMenu = "Playback" } }
                        item { SettingsRowItem("Backup & Restore") { subMenu = "Backup" } }
                        item { SettingsRowItem("Update all playlists") { onRefreshAll(); onClose() } }
                    }
                    "General" -> {
                        item { Text("Default User-Agent", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                        items(listOf("TiviMate", "OTT Navigator", "Custom")) { preset ->
                            SettingsRowItem(if (settings.uaPreset == preset) "✓ $preset" else preset) {
                                onUpdateSettings(settings.copy(uaPreset = preset))
                            }
                        }
                        if (settings.uaPreset == "Custom") {
                            item {
                                OutlinedTextField(
                                    value = settings.customUa,
                                    onValueChange = { onUpdateSettings(settings.copy(customUa = it)) },
                                    label = { Text("Custom User-Agent") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                            }
                        }
                    }
                    "EPG" -> {
                        item {
                            OutlinedTextField(
                                value = settings.epgUrl,
                                onValueChange = { onUpdateSettings(settings.copy(epgUrl = it)) },
                                label = { Text("XMLTV EPG URL") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { onRefreshAll(); onClose() },
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.Black),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Load EPG", fontWeight = FontWeight.Bold) }
                        }
                    }
                    "Appearance" -> {
                        item { Text("Accent Color", color = TextSecondary, fontSize = 12.sp) }
                        items(listOf("Cyan", "Blue", "Purple", "Green", "Orange")) { c ->
                            SettingsRowItem(if (uiPrefs.accentName == c) "✓ $c" else c) { onUpdateUiPrefs(uiPrefs.copy(accentName = c)) }
                        }
                        item { Spacer(Modifier.height(6.dp)); Text("Font Size", color = TextSecondary, fontSize = 12.sp) }
                        items(listOf("Small", "Normal", "Large")) { f ->
                            SettingsRowItem(if (uiPrefs.fontSize == f) "✓ $f" else f) { onUpdateUiPrefs(uiPrefs.copy(fontSize = f)) }
                        }
                        item {
                            Spacer(Modifier.height(6.dp))
                            SettingsRowItem("Channel Numbers: ${if (uiPrefs.showNumbers) "ON" else "OFF"}") { onUpdateUiPrefs(uiPrefs.copy(showNumbers = !uiPrefs.showNumbers)) }
                            SettingsRowItem("Channel Logos: ${if (uiPrefs.showLogos) "ON" else "OFF"}") { onUpdateUiPrefs(uiPrefs.copy(showLogos = !uiPrefs.showLogos)) }
                            SettingsRowItem("Clock Format: ${if (uiPrefs.clock24h) "24-Hour" else "12-Hour"}") { onUpdateUiPrefs(uiPrefs.copy(clock24h = !uiPrefs.clock24h)) }
                        }
                    }
                    "Playback" -> {
                        item { Text("Buffer Size", color = TextSecondary, fontSize = 12.sp) }
                        items(listOf("Low RAM (TV)", "Balanced", "High")) { mode ->
                            SettingsRowItem(if (settings.bufferMode == mode) "✓ $mode" else mode) { onUpdateSettings(settings.copy(bufferMode = mode)) }
                        }
                        item { Spacer(Modifier.height(8.dp)); Text("Video Decoder", color = TextSecondary, fontSize = 12.sp) }
                        items(listOf("HW", "SW")) { dec ->
                            SettingsRowItem(if (settings.decoderMode == dec) "✓ $dec Decoder" else "$dec Decoder") { onUpdateSettings(settings.copy(decoderMode = dec)) }
                        }
                    }
                    "Backup" -> {
                        item {
                            Button(
                                onClick = {
                                    val json = exportBackupJson(prefs)
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("StreamMate Backup", json))
                                    Toast.makeText(context, "Backup copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Copy Backup Data") }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { showRestoreBox = !showRestoreBox }, modifier = Modifier.fillMaxWidth()) { Text("Restore Backup") }
                            if (showRestoreBox) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = restoreInput, onValueChange = { restoreInput = it }, label = { Text("Paste Backup JSON") }, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(6.dp))
                                Button(onClick = {
                                    if (importBackupJson(prefs, restoreInput)) {
                                        Toast.makeText(context, "Restored!", Toast.LENGTH_SHORT).show()
                                        showRestoreBox = false
                                        onRestored()
                                    } else {
                                        Toast.makeText(context, "Invalid backup data", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("Apply Restore") }
                            }
                        }
                    }
                    "Playlists" -> {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                                Button(onClick = { addDialogType = "M3U" }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("+M3U", fontSize = 11.sp) }
                                Button(onClick = { addDialogType = "XTREAM" }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("+Xtream", fontSize = 11.sp) }
                                Button(onClick = { addDialogType = "STALKER" }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("+Stalker", fontSize = 11.sp) }
                            }
                        }
                        items(sources) { src ->
                            Surface(shape = RoundedCornerShape(8.dp), color = CardDark, border = BorderStroke(1.dp, BorderSubtle), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(src.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("${src.type} • ${src.url}", color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { onDeleteSource(src) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Delete, "Del", tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (addDialogType != null) {
        AddPlaylistDialog(addDialogType!!, accentColor, { addDialogType = null }) { src ->
            onAddSource(src)
            addDialogType = null
        }
    }
}

@Composable
fun SettingsRowItem(title: String, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp)) {
        Text(title, color = TextPrimary, fontSize = 14.sp)
    }
}

@Composable
fun AddPlaylistDialog(type: String, accentColor: Color, onDismiss: () -> Unit, onSave: (PlaylistSource) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("00:1A:79:") }
    var sn by remember { mutableStateOf("") }
    var dev1 by remember { mutableStateOf("") }
    var dev2 by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardElevated,
        title = { Text("Add $type Playlist", color = TextPrimary) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Playlist Name") }, singleLine = true) }
                item { OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(if (type == "M3U") "M3U URL" else "Server / Portal URL") }, singleLine = true) }
                if (type == "XTREAM") {
                    item { OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true) }
                    item { OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true) }
                }
                if (type == "STALKER") {
                    item { OutlinedTextField(value = mac, onValueChange = { mac = it }, label = { Text("MAC Address") }, singleLine = true) }
                    item { OutlinedTextField(value = sn, onValueChange = { sn = it }, label = { Text("Serial Number (SN)") }, singleLine = true) }
                    item { OutlinedTextField(value = dev1, onValueChange = { dev1 = it }, label = { Text("Device ID 1") }, singleLine = true) }
                    item { OutlinedTextField(value = dev2, onValueChange = { dev2 = it }, label = { Text("Device ID 2") }, singleLine = true) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (url.isNotBlank()) {
                        onSave(
                            PlaylistSource(
                                id = System.currentTimeMillis().toString(),
                                name = name.ifBlank { "$type Playlist" },
                                type = type,
                                url = url.trim(),
                                username = username.trim(),
                                password = password.trim(),
                                mac = mac.trim(),
                                sn = sn.trim(),
                                deviceId1 = dev1.trim(),
                                deviceId2 = dev2.trim()
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.Black)
            ) { Text("Save", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } }
    )
}
