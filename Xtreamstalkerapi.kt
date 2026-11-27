package com.streammate.pro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private fun httpGet(urlStr: String, userAgent: String, headers: Map<String, String> = emptyMap()): String {
    var currentUrl = urlStr.trim()
    var redirects = 0
    while (true) {
        val conn = URL(currentUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "*/*")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        if (code in 300..399 && redirects < 5) {
            val loc = conn.getHeaderField("Location") ?: return ""
            currentUrl = URL(URL(currentUrl), loc).toString()
            redirects++
            conn.disconnect()
        } else {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            return text
        }
    }
}

// --- 1. XTREAM CODES API (Live TV + Movies) ---
suspend fun fetchXtreamApi(
    serverUrl: String,
    username: String,
    password: String,
    userAgent: String
): List<Channel> = withContext(Dispatchers.IO) {
    val result = mutableListOf<Channel>()
    val base = serverUrl.trim().removeSuffix("/")
    val u = URLEncoder.encode(username.trim(), "UTF-8")
    val p = URLEncoder.encode(password.trim(), "UTF-8")

    // Live TV
    try {
        val liveJson = httpGet("$base/player_api.php?username=$u&password=$p&action=get_live_streams", userAgent)
        val arr = JSONArray(liveJson)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            if (id.isNotBlank()) {
                result.add(
                    Channel(
                        id = "xt_live_$id",
                        name = o.optString("name", "Live $id"),
                        url = "$base/live/$username/$password/$id.ts",
                        logo = o.optString("stream_icon", ""),
                        group = "Live TV",
                        mediaType = "LIVE"
                    )
                )
            }
        }
    } catch (_: Exception) { }

    // Movies (VOD)
    try {
        val vodJson = httpGet("$base/player_api.php?username=$u&password=$p&action=get_vod_streams", userAgent)
        val arr = JSONArray(vodJson)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            val ext = o.optString("container_extension", "mp4").ifBlank { "mp4" }
            if (id.isNotBlank()) {
                result.add(
                    Channel(
                        id = "xt_vod_$id",
                        name = o.optString("name", "Movie $id"),
                        url = "$base/movie/$username/$password/$id.$ext",
                        logo = o.optString("stream_icon", ""),
                        group = "Movies",
                        mediaType = "MOVIE"
                    )
                )
            }
        }
    } catch (_: Exception) { }

    result
}

// --- 2. STALKER PORTAL API (MAC + SN + Device ID 1 & 2) ---
suspend fun fetchStalkerApi(
    portalUrl: String,
    mac: String,
    sn: String,
    deviceId1: String,
    deviceId2: String,
    userAgent: String
): List<Channel> = withContext(Dispatchers.IO) {
    val result = mutableListOf<Channel>()
    try {
        val cleanBase = portalUrl.trim().removeSuffix("/").removeSuffix("/c")
        val endpoint = if (cleanBase.endsWith(".php")) cleanBase else "$cleanBase/portal.php"
        val cleanMac = mac.trim().uppercase()
        val cookie = "mac=${URLEncoder.encode(cleanMac, "UTF-8")}; stb_lang=en; timezone=Asia/Kolkata"
        val baseHeaders = mutableMapOf(
            "Cookie" to cookie,
            "X-User-Agent" to "Model: MAG250; Link: WiFi",
            "Referer" to "$cleanBase/c/"
        )

        // Step A: Handshake
        val hsRaw = httpGet("$endpoint?type=stb&action=handshake&token=&JsHttpRequest=1-xml", userAgent, baseHeaders)
        val token = JSONObject(hsRaw).optJSONObject("js")?.optString("token", "") ?: ""
        if (token.isBlank()) return@withContext emptyList()
        baseHeaders["Authorization"] = "Bearer $token"

        // Step B: Profile auth with SN, Device ID 1 & 2
        val profUrl = "$endpoint?type=stb&action=get_profile&hd=1&sn=${URLEncoder.encode(sn.trim(), "UTF-8")}" +
            "&device_id=${URLEncoder.encode(deviceId1.trim(), "UTF-8")}" +
            "&device_id2=${URLEncoder.encode(deviceId2.trim(), "UTF-8")}&JsHttpRequest=1-xml"
        httpGet(profUrl, userAgent, baseHeaders)

        // Step C: Fetch Live Channels
        val chRaw = httpGet("$endpoint?type=itv&action=get_all_channels&JsHttpRequest=1-xml", userAgent, baseHeaders)
        val dataArr = JSONObject(chRaw).optJSONObject("js")?.optJSONArray("data") ?: return@withContext emptyList()
        for (i in 0 until dataArr.length()) {
            val o = dataArr.getJSONObject(i)
            val rawCmd = o.optString("cmd", "").replace("ffmpeg ", "").replace("ffrt ", "").trim()
            if (rawCmd.startsWith("http")) {
                result.add(
                    Channel(
                        id = "stk_${o.optString("id", i.toString())}",
                        name = o.optString("name", "Channel ${i + 1}"),
                        url = rawCmd,
                        logo = o.optString("logo", ""),
                        group = "Stalker Live",
                        mediaType = "LIVE"
                    )
                )
            }
        }
    } catch (_: Exception) { }
    result
}
