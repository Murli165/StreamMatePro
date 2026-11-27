package com.streammate.pro

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Xml
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

// --- Classic Dark TV Theme ---
val BgBlack = Color(0xFF0C0E12)
val SidebarBg = Color(0xFF12151B)
val CardDark = Color(0xFF171B22)
val CardElevated = Color(0xFF232A36)
val BorderSubtle = Color(0xFF2C3440)
val AccentCyan = Color(0xFF1EA7FD)
val TextPrimary = Color(0xFFF2F4F8)
val TextSecondary = Color(0xFF8F99A8)

data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "Live TV",
    val mediaType: String = "LIVE", // "LIVE", "MOVIE", "SERIES"
    val tvgId: String = ""
)

data class PlaylistSource(
    val id: String,
    val name: String,
    val type: String, // "M3U", "XTREAM", "STALKER"
    val url: String,
    val username: String = "",
    val password: String = "",
    val mac: String = "",
    val sn: String = "",
    val deviceId1: String = "",
    val deviceId2: String = ""
)

data class AppSettings(
    val uaPreset: String = "TiviMate",
    val customUa: String = "TiviMate/4.7.0 (Android)",
    val bufferMode: String = "Low RAM (TV)",
    val decoderMode: String = "HW", // "HW" or "SW"
    val epgUrl: String = ""
)

fun resolveUserAgent(settings: AppSettings): String = when (settings.uaPreset) {
    "OTT Navigator" -> "OTT Navigator/1.7.1 (Linux; Android)"
    "Custom" -> settings.customUa.ifBlank { "TiviMate/4.7.0 (Android)" }
    else -> "TiviMate/4.7.0 (Android)"
}

suspend fun fetchAndParseM3U(urlStr: String, userAgent: String): List<Channel> = withContext(Dispatchers.IO) {
    val list = mutableListOf<Channel>()
    try {
        var currentUrl = urlStr.trim()
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                currentUrl = URL(URL(currentUrl), loc).toString()
                redirects++
                conn.disconnect()
            } else break
        }
        val lines = conn.inputStream.bufferedReader().readLines()
        var currentName = ""
        var currentLogo = ""
        var currentGroup = "Live TV"
        var currentTvgId = ""
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF:")) {
                currentName = line.substringAfterLast(",", "Channel").trim()
                currentLogo = Regex("""tvg-logo="([^"]*)"""").find(line)?.groupValues?.get(1) ?: ""
                currentTvgId = Regex("""tvg-id="([^"]*)"""").find(line)?.groupValues?.get(1) ?: ""
                currentGroup = Regex("""group-title="([^"]*)"""").find(line)?.groupValues?.get(1)?.ifBlank { "Live TV" } ?: "Live TV"
            } else if (line.isNotEmpty() && !line.startsWith("#") &&
                (line.startsWith("http") || line.startsWith("rtmp") || line.startsWith("rtsp"))
            ) {
                val lower = line.lowercase()
                val mType = when {
                    lower.contains("/movie/") || lower.endsWith(".mp4") || lower.endsWith(".mkv") -> "MOVIE"
                    lower.contains("/series/") -> "SERIES"
                    else -> "LIVE"
                }
                list.add(
                    Channel(
                        id = line.hashCode().toString(),
                        name = currentName.ifBlank { "Channel ${list.size + 1}" },
                        url = line,
                        logo = currentLogo,
                        group = currentGroup,
                        mediaType = mType,
                        tvgId = currentTvgId
                    )
                )
                currentName = ""
            }
        }
    } catch (_: Exception) { }
    list
}

// Lightweight XMLTV EPG Parser (Channel ID -> Current Program Title)
suspend fun fetchEpgMap(epgUrl: String, userAgent: String): Map<String, String> = withContext(Dispatchers.IO) {
    val map = mutableMapOf<String, String>()
    if (epgUrl.isBlank()) return@withContext map
    try {
        val conn = URL(epgUrl.trim()).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", userAgent)
        val parser = Xml.newPullParser()
        parser.setInput(conn.inputStream, "UTF-8")
        var event = parser.eventType
        var currentChannelId = ""
        while (event != XmlPullParser.END_DOCUMENT && map.size < 1500) {
            if (event == XmlPullParser.START_TAG) {
                if (parser.name == "programme") {
                    currentChannelId = parser.getAttributeValue(null, "channel") ?: ""
                } else if (parser.name == "title" && currentChannelId.isNotBlank() && !map.containsKey(currentChannelId)) {
                    val title = parser.nextText().trim()
                    if (title.isNotBlank()) map[currentChannelId] = title
                }
            }
            event = parser.next()
        }
        conn.disconnect()
    } catch (_: Exception) { }
    map
}

class DvrRecorder {
    private var job: Job? = null

    fun start(
        context: Context,
        scope: CoroutineScope,
        streamUrl: String,
        channelName: String,
        userAgent: String,
        onStatus: (String) -> Unit
    ) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            var out: OutputStream? = null
            var conn: HttpURLConnection? = null
            try {
                val safeName = channelName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(25)
                val fileName = "SM_${safeName}_${System.currentTimeMillis()}.ts"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StreamMatePro")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) out = context.contentResolver.openOutputStream(uri)
                } else {
                    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "StreamMatePro")
                    dir.mkdirs()
                    out = FileOutputStream(File(dir, fileName))
                }
                if (out == null) {
                    withContext(Dispatchers.Main) { onStatus("Storage error") }
                    return@launch
                }
                var currentUrl = streamUrl
                var redirects = 0
                while (true) {
                    conn = URL(currentUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 20000
                    conn.setRequestProperty("User-Agent", userAgent)
                    val code = conn.responseCode
                    if (code in 300..399 && redirects < 5) {
                        val loc = conn.getHeaderField("Location") ?: break
                        currentUrl = URL(URL(currentUrl), loc).toString()
                        redirects++
                        conn.disconnect()
                    } else break
                }
                val input = conn!!.inputStream
                val buf = ByteArray(16 * 1024)
                withContext(Dispatchers.Main) { onStatus("Recording: $fileName") }
                while (isActive) {
                    val read = input.read(buf)
                    if (read == -1) break
                    out.write(buf, 0, read)
                }
                withContext(Dispatchers.Main) { onStatus("Saved: Movies/StreamMatePro/$fileName") }
            } catch (_: Exception) {
                if (isActive) withContext(Dispatchers.Main) { onStatus("DVR stopped") }
            } finally {
                try { out?.flush(); out?.close() } catch (_: Exception) {}
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

fun saveSources(prefs: SharedPreferences, list: List<PlaylistSource>) {
    val arr = JSONArray()
    list.forEach {
        arr.put(JSONObject().apply {
            put("id", it.id); put("name", it.name); put("type", it.type); put("url", it.url)
            put("username", it.username); put("password", it.password)
            put("mac", it.mac); put("sn", it.sn)
            put("deviceId1", it.deviceId1); put("deviceId2", it.deviceId2)
        })
    }
    prefs.edit().putString("sources_json", arr.toString()).apply()
}

fun loadSources(prefs: SharedPreferences): List<PlaylistSource> {
    val raw = prefs.getString("sources_json", null) ?: return emptyList()
    val res = mutableListOf<PlaylistSource>()
    try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            res.add(
                PlaylistSource(
                    o.getString("id"), o.getString("name"), o.getString("type"), o.getString("url"),
                    o.optString("username", ""), o.optString("password", ""),
                    o.optString("mac", ""), o.optString("sn", ""),
                    o.optString("deviceId1", ""), o.optString("deviceId2", "")
                )
            )
        }
    } catch (_: Exception) { }
    return res
}

fun saveSettings(prefs: SharedPreferences, s: AppSettings) {
    prefs.edit()
        .putString("ua_preset", s.uaPreset)
        .putString("custom_ua", s.customUa)
        .putString("buffer_mode", s.bufferMode)
        .putString("decoder_mode", s.decoderMode)
        .putString("epg_url", s.epgUrl)
        .apply()
}

fun loadSettings(prefs: SharedPreferences): AppSettings = AppSettings(
    uaPreset = prefs.getString("ua_preset", "TiviMate") ?: "TiviMate",
    customUa = prefs.getString("custom_ua", "TiviMate/4.7.0 (Android)") ?: "TiviMate/4.7.0 (Android)",
    bufferMode = prefs.getString("buffer_mode", "Low RAM (TV)") ?: "Low RAM (TV)",
    decoderMode = prefs.getString("decoder_mode", "HW") ?: "HW",
    epgUrl = prefs.getString("epg_url", "") ?: ""
)

fun exportBackupJson(prefs: SharedPreferences): String {
    val obj = JSONObject()
    obj.put("sources", prefs.getString("sources_json", "[]"))
    obj.put("favs", JSONArray((prefs.getStringSet("favs", emptySet()) ?: emptySet()).toList()))
    obj.put("epg_url", prefs.getString("epg_url", ""))
    return obj.toString()
}

fun importBackupJson(prefs: SharedPreferences, jsonStr: String): Boolean {
    return try {
        val obj = JSONObject(jsonStr.trim())
        val edit = prefs.edit()
        if (obj.has("sources")) edit.putString("sources_json", obj.getString("sources"))
        if (obj.has("epg_url")) edit.putString("epg_url", obj.getString("epg_url"))
        if (obj.has("favs")) {
            val arr = obj.getJSONArray("favs")
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            edit.putStringSet("favs", set)
        }
        edit.apply()
        true
    } catch (_: Exception) { false }
}

fun loadRecentIds(prefs: SharedPreferences): List<String> {
    val raw = prefs.getString("recents", "") ?: ""
    return if (raw.isBlank()) emptyList() else raw.split(",")
}
