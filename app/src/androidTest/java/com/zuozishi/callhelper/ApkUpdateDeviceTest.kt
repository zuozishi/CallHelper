package com.zuozishi.callhelper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zuozishi.callhelper.update.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.content.ContextWrapper
import java.util.UUID

/** Requires scripts/create_update_test_fixtures.py and four APKs copied into app files via run-as. */
@RunWith(AndroidJUnit4::class)
class ApkUpdateDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(name: String) = File(context.filesDir, "update-fixtures/$name.apk").also { assertTrue("Missing fixture: $it", it.exists()) }

    @Test fun validNewerApkPassesCryptographicAndUpgradeChecks() {
        UpdateApkVerifier(context).verify(fixture("next"))
    }

    @Test fun rejectsWrongSignerWrongPackageAndOlderVersion() {
        for (name in listOf("wrong-signer", "wrong-package", "old")) {
            val error = runCatching { UpdateApkVerifier(context).verify(fixture(name)) }.exceptionOrNull()
            assertTrue("$name must fail verification", error is UpdateRejected)
        }
    }

    @Test fun verifiedApkOpensSystemInstallerWithoutConfirmingInstallation() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val folder = File(context.filesDir, "updates").apply { mkdirs() }
        val file = fixture("next").copyTo(File(folder, "installer-test.apk"), overwrite = true)
        try {
            UpdateApkVerifier(context).verify(file)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            fun hasInstallAction(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isClickable && node.text?.toString() in listOf("更新", "安装", "Update", "Install")) return true
                return (0 until node.childCount).any { hasInstallAction(node.getChild(it)) }
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < deadline && !hasInstallAction(inst.uiAutomation.rootInActiveWindow)) android.os.SystemClock.sleep(100)
            assertTrue(inst.uiAutomation.rootInActiveWindow?.packageName.toString().contains("packageinstaller"))
            assertTrue("System installer must offer an installation action", hasInstallAction(inst.uiAutomation.rootInActiveWindow))
            inst.uiAutomation.waitForIdle(500, 5000)
            android.os.SystemClock.sleep(500) // Allow the system window's entrance animation to settle.
            val bitmap = inst.uiAutomation.takeScreenshot()
            val out = File(context.getExternalFilesDir(null), "update-screens").apply { mkdirs() }
            File(out, "system-installer.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally {
            inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            file.delete()
        }
    }

    @Test fun completedDownloadRequestsInstallationAndCanResumeAfterPermission() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val folder = File(context.cacheDir, "update-test-${UUID.randomUUID()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir(): File = folder }
        val bytes = fixture("next").readBytes()
        val nextVersion = BuildConfig.VERSION_NAME.substringBeforeLast('.') + "." + (BuildConfig.VERSION_NAME.substringAfterLast('.').toInt() + 1)
        val json = """{"tag_name":"v$nextVersion","assets":[{"name":"callhelper-$nextVersion-release.apk","size":${bytes.size},"browser_download_url":"$REPOSITORY/releases/download/v$nextVersion/callhelper-$nextVersion-release.apk"}]}"""
        val client = ReleaseClient { url -> object : HttpURLConnection(URL(url)) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = (if (url.endsWith(".apk")) bytes else json.toByteArray()).inputStream()
            override fun getContentLengthLong() = if (url.endsWith(".apk")) bytes.size.toLong() else -1L
        } }
        try {
            val updates = withContext(Dispatchers.Main) { UpdateController(wrapped, scope, client).also { it.check() } }
            withTimeout(5000) { while (updates.state.value.checking) delay(20) }
            assertEquals(UpdateSheet.Release, updates.state.value.sheet)
            withContext(Dispatchers.Main) { updates.download() }
            withTimeout(10000) { while (updates.state.value.downloading) delay(20) }
            assertNull(updates.state.value.downloadError)
            assertTrue(updates.state.value.ready)
            assertTrue(updates.state.value.installRequested)
            assertTrue(updates.apk.exists())
            withContext(Dispatchers.Main) {
                updates.consumeInstallRequest(); updates.needPermission()
                assertEquals(UpdateSheet.Permission, updates.state.value.sheet)
                updates.openPermission(); updates.permissionReturned(false)
                assertFalse(updates.state.value.installRequested)
                updates.openPermission(); updates.permissionReturned(true)
                assertTrue(updates.state.value.installRequested)
            }
        } finally {
            scope.cancel()
            folder.deleteRecursively()
        }
    }
}
