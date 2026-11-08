package com.zuozishi.callhelper

import com.zuozishi.callhelper.update.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ReleaseUpdateTest {
    private val apkUrl = "$REPOSITORY/releases/download/v0.2.1/callhelper-0.2.1-release.apk"
    private fun release(tag: String = "v0.2.1", flags: String = "", url: String = apkUrl) = """
        {"tag_name":"$tag","published_at":"2026-11-08T01:00:00Z","body":"更新说明",$flags
        "assets":[{"name":"callhelper-0.2.1-release.apk","size":4,"browser_download_url":"$url"}]}
    """.trimIndent()

    private class Response(url: String, private val body: ByteArray, private val status: Int = 200,
        private val type: String = "application/json", private val length: Long = body.size.toLong()) : HttpURLConnection(URL(url)) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentType() = type
        override fun getContentLengthLong() = length
    }

    @Test fun numericVersionsAndStableOnly() {
        assertTrue(isNewerVersion("v0.10.0", "0.2.9"))
        assertFalse(isNewerVersion("v0.2.0", "0.2.0.0"))
        assertFalse(isNewerVersion("v0.1.99", "0.2.0"))
        listOf("v0.3.0-beta", "0.x.1", "2147483648.0", "", "v1").forEach { assertNull(stableVersion(it)) }
    }

    @Test fun onlyRepositoryReleaseApksAreAccepted() {
        assertTrue(trustedAssetUrl(apkUrl))
        listOf(apkUrl.replace("https:", "http:"), apkUrl.replace("github.com", "github.com.evil.test"),
            apkUrl.replace("/CallHelper/", "/Other/"), apkUrl.replace("/download/", "/../"),
            "$apkUrl?token=x", apkUrl.replace("github.com", "user@github.com"), "$apkUrl.html").forEach {
            assertFalse(it, trustedAssetUrl(it))
        }
    }

    @Test fun invalidMirrorJsonFallsBackToOfficial() = runBlocking {
        val calls = mutableListOf<String>()
        val client = ReleaseClient { url -> calls += url; Response(url, (if (calls.size == 1) "<html>blocked</html>" else release()).toByteArray()) }
        assertEquals("v0.2.1", client.latest()?.tag)
        assertEquals(listOf(MIRROR + RELEASE_API + "/latest", RELEASE_API + "/latest"), calls)
    }

    @Test fun mirrorHttpErrorFallsBackAndOfficialFailurePropagates() = runBlocking {
        val calls = mutableListOf<String>()
        val client = ReleaseClient { url -> calls += url; Response(url, byteArrayOf(), 503) }
        assertTrue(runCatching { client.latest() }.isFailure)
        assertEquals(2, calls.size)
    }

    @Test fun successfulMirrorDoesNotCallOfficial() = runBlocking {
        val calls = mutableListOf<String>()
        assertEquals("v0.2.1", ReleaseClient { url -> calls += url; Response(url, release().toByteArray()) }.latest()?.tag)
        assertEquals(1, calls.size)
    }

    @Test fun cancellationNeverTriggersFallback() = runBlocking {
        var calls = 0
        try { mirrorThenOfficial(apkUrl) { calls++; throw CancellationException() } }
        catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun historyFiltersDraftsAndPrereleasesAndUntrustedAssets() {
        val list = parseHistory("[${release()},${release(flags = "\"draft\":true,")},${release(flags = "\"prerelease\":true,")}]")
        assertEquals(1, list.size)
        assertEquals("2026-11-08", list.single().date)
        assertTrue(parseHistory("[${release(url = "https://evil.test/update.apk")}]").single().assets.isEmpty())
    }

    @Test fun selectsUniversalOrMatchingAbiAndRejectsHugePackages() {
        val x86 = ReleaseAsset("x86_64.apk", apkUrl, 4, null)
        val arm = ReleaseAsset("arm64-v8a.apk", apkUrl, 4, null)
        assertEquals(arm, AppRelease("v0.2.1", "", "", listOf(x86, arm)).apk(listOf("arm64-v8a")))
        assertNull(AppRelease("v0.2.1", "", "", listOf(x86)).apk(listOf("arm64-v8a")))
        assertNull(AppRelease("v0.2.1", "", "", listOf(arm.copy(size = MAX_APK_SIZE + 1))).apk(listOf("arm64-v8a")))
    }

    @Test fun htmlMirrorDownloadRestartsUsingOfficial() = runBlocking {
        val file = File.createTempFile("callhelper-test", ".part")
        try {
            val calls = mutableListOf<String>()
            var verified = 0
            val client = ReleaseClient { url -> calls += url
                if (calls.size == 1) Response(url, "HTML".toByteArray(), type = "text/html")
                else Response(url, byteArrayOf(1, 2, 3, 4), type = "application/octet-stream")
            }
            client.download(ReleaseAsset("update.apk", apkUrl, 4, null), file, { _, _ -> }) { verified++; assertEquals(4L, it.length()) }
            assertEquals(2, calls.size); assertEquals(1, verified)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
        } finally { file.delete() }
    }

    @Test fun truncatedDownloadAndDigestMismatchDeleteThePartialFile() = runBlocking {
        val file = File.createTempFile("callhelper-test", ".part")
        try {
            val client = ReleaseClient { url -> Response(url, byteArrayOf(1, 2), length = 4) }
            assertTrue(runCatching { client.download(ReleaseAsset("update.apk", apkUrl, 4, null), file, { _, _ -> }) { fail() } }.isFailure)
            assertFalse(file.exists())
            val invalidDigest = "sha256:" + "0".repeat(64)
            val badHashClient = ReleaseClient { url -> Response(url, byteArrayOf(1, 2)) }
            assertTrue(runCatching { badHashClient.download(ReleaseAsset("update.apk", apkUrl, 2, invalidDigest), file, { _, _ -> }) { fail() } }.isFailure)
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun signatureVerifierFailureRetriesOfficialAndLeavesNoInstallableFile() = runBlocking {
        val file = File.createTempFile("callhelper-test", ".part")
        var attempts = 0
        try {
            val client = ReleaseClient { url -> Response(url, byteArrayOf(1, 2)) }
            assertTrue(runCatching { client.download(ReleaseAsset("update.apk", apkUrl, 2, null), file, { _, _ -> }) {
                attempts++; throw UpdateRejected("bad signature")
            } }.isFailure)
            assertEquals(2, attempts); assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun matchingDigestAllowsVerification() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val digest = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File.createTempFile("callhelper-test", ".part")
        var verified = false
        try {
            ReleaseClient { url -> Response(url, bytes) }.download(ReleaseAsset("update.apk", apkUrl, 4, digest), file, { _, _ -> }) { verified = true }
            assertTrue(verified)
        } finally { file.delete() }
    }
}
