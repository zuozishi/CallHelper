package com.zuozishi.callhelper.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONObject

internal class ReleaseClient(private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }) {
    private fun connection(url: String, json: Boolean) = open(url).apply {
        connectTimeout = 7_000
        readTimeout = if (json) 10_000 else 20_000
        setRequestProperty("User-Agent", "CallHelper-Android")
        setRequestProperty("Accept", if (json) "application/vnd.github+json" else "application/octet-stream")
        if (json) setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
    }

    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        mirrorThenOfficial("$RELEASE_API/latest") { url ->
            json(url) { raw -> parseRelease(JSONObject(raw)).also { require(stableVersion(it.tag) != null) } }
        }
    }

    suspend fun history(): String = withContext(Dispatchers.IO) {
        mirrorThenOfficial("$RELEASE_API?per_page=30") { url ->
            json(url) { raw -> parseHistory(raw); raw }
        }
    }

    private suspend fun <T> json(url: String, parse: (String) -> T): T = timedAttempt(25_000) {
        val conn = connection(url, true)
        try {
            require(conn.responseCode == 200) { "Release request failed" }
            require(conn.url.protocol == "https")
            val data = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 2 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            parse(data)
        } finally { conn.disconnect() }
    }

    suspend fun download(asset: ReleaseAsset, file: File, progress: (Long, Long) -> Unit, verify: (File) -> Unit) = withContext(Dispatchers.IO) {
        require(trustedAssetUrl(asset.url))
        mirrorThenOfficial(asset.url) { url ->
            file.delete()
            try {
                timedAttempt(10 * 60_000L) {
                    val conn = connection(url, false)
                    try {
                        require(conn.responseCode == 200 && conn.url.protocol == "https")
                        require(!conn.contentType.orEmpty().contains("text/html", true))
                        val length = conn.contentLengthLong
                        require(length <= MAX_APK_SIZE)
                        if (length > 0) require(length == asset.size)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        progress(0, asset.size)
                        conn.inputStream.use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                require(count <= asset.size && count <= MAX_APK_SIZE)
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                progress(count, asset.size)
                            }
                        } }
                        require(count == asset.size)
                        asset.digest?.let { expected ->
                            require(expected.matches(Regex("sha256:[a-fA-F0-9]{64}")))
                            val actual = digest.digest().joinToString("") { "%02x".format(it) }
                            require(actual.equals(expected.substringAfter(':'), true))
                        }
                        verify(file)
                    } finally { conn.disconnect() }
                }
            } catch (error: Exception) { file.delete(); throw error }
        }
    }
}

private suspend fun <T> timedAttempt(timeout: Long, block: suspend () -> T): T = try {
    withTimeout(timeout) { block() }
} catch (error: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw java.io.IOException("Update request timed out", error)
}

internal fun parseRelease(json: JSONObject): AppRelease {
    require(!json.optBoolean("draft") && !json.optBoolean("prerelease"))
    val tag = json.getString("tag_name")
    require(stableVersion(tag) != null)
    val assets = json.getJSONArray("assets")
    return AppRelease(tag, json.optString("published_at").take(10), json.optString("body").take(40_000),
        (0 until assets.length()).mapNotNull { i ->
            val item = assets.getJSONObject(i)
            val url = item.optString("browser_download_url")
            if (!trustedAssetUrl(url)) null else ReleaseAsset(item.getString("name"), url, item.getLong("size"),
                item.optString("digest").takeIf { it.isNotBlank() && it != "null" })
        })
}

internal fun parseHistory(raw: String): List<AppRelease> {
    val array = JSONArray(raw)
    return (0 until array.length()).mapNotNull { i ->
        val json = array.getJSONObject(i)
        if (json.optBoolean("draft") || json.optBoolean("prerelease") || stableVersion(json.optString("tag_name")) == null) null
        else parseRelease(json)
    }
}
