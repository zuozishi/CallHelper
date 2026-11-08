package com.zuozishi.callhelper.update

import java.net.URI
import kotlinx.coroutines.CancellationException

internal const val REPOSITORY = "https://github.com/zuozishi/CallHelper"
internal const val RELEASE_API = "https://api.github.com/repos/zuozishi/CallHelper/releases"
internal const val MIRROR = "https://gh-proxy.com/"

/** Stable numeric versions only; prerelease tags must never become upgrade prompts. */
internal fun stableVersion(tag: String): List<Int>? =
    tag.trim().removePrefix("v").takeIf { it.matches(Regex("\\d+(\\.\\d+){1,3}")) }
        ?.split('.')?.map { it.toIntOrNull() ?: return null }

internal fun isNewerVersion(candidate: String, installed: String): Boolean {
    val next = stableVersion(candidate) ?: return false
    val current = stableVersion(installed) ?: return false
    for (i in 0 until maxOf(next.size, current.size)) {
        val compared = (next.getOrElse(i) { 0 }).compareTo(current.getOrElse(i) { 0 })
        if (compared != 0) return compared > 0
    }
    return false
}

internal fun trustedAssetUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.host.equals("github.com", true) && uri.rawUserInfo == null &&
        uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null &&
        uri.path.startsWith("/zuozishi/CallHelper/releases/download/") &&
        uri.path.endsWith(".apk", true) && uri.path.split('/').none { it == ".." || it == "." }
}.getOrDefault(false)

/** Each source gets a fresh attempt, including parsing and verification. Never retry cancellation. */
internal suspend fun <T> mirrorThenOfficial(url: String, attempt: suspend (String) -> T): T {
    try { return attempt(MIRROR + url) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { return attempt(url) }
}

internal data class ReleaseAsset(val name: String, val url: String, val size: Long, val digest: String?)
internal data class AppRelease(val tag: String, val date: String, val notes: String, val assets: List<ReleaseAsset>) {
    fun apk(abis: List<String>): ReleaseAsset? {
        val packages = assets.filter { trustedAssetUrl(it.url) && it.size in 1..MAX_APK_SIZE }
        return packages.firstOrNull { it.name.equals("callhelper-${tag.removePrefix("v")}-release.apk", true) }
            ?: packages.firstOrNull { it.name.contains("universal", true) }
            ?: abis.firstNotNullOfOrNull { abi -> packages.firstOrNull {
                it.name.contains(abi, true) && (abi != "x86" || !it.name.contains("x86_64", true))
            } }
            ?: packages.singleOrNull()?.takeIf { asset ->
                listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").none { asset.name.contains(it, true) }
            }
    }
}

internal const val MAX_APK_SIZE = 256L * 1024 * 1024
