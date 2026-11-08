package com.zuozishi.callhelper

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zuozishi.callhelper.update.ReleaseClient
import com.zuozishi.callhelper.update.UpdateController
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class AboutUpdateDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun fifthTabAndOfflineReleaseHistory() {
        ui.onNodeWithTag("nav-4").performClick()
        ui.onNodeWithText("关于").assertExists()
        ui.onNodeWithText("zuozishi / CallHelper").assertExists()
        ui.onNodeWithText("作者").assertExists()
        ui.onNodeWithTag("check-update").assertExists()
        screenshot("about-light")
        ui.onNodeWithText("更新记录").performClick()
        ui.onAllNodesWithTag("release-notes").onFirst().assertExists()
        screenshot("history")
        ui.onNodeWithContentDescription("返回关于").performClick()
        ui.onNodeWithText("作者").assertExists()
        ui.onNodeWithTag("nav-3").performClick()
        ui.onNodeWithTag("theme-dropdown").performClick()
        ui.onNodeWithText("深色").performClick()
        ui.onNodeWithTag("nav-4").performClick()
        screenshot("about-dark")
        ui.onNodeWithTag("nav-3").performClick()
        ui.onNodeWithTag("theme-dropdown").performClick()
        ui.onNodeWithText("浅色").performClick()
    }

    @Test fun automaticNetworkFailureIsSilentAndManualFailureIsVisible() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val context = ui.activity.applicationContext
        val isolated = File(context.cacheDir, "update-test-${UUID.randomUUID()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir(): File = isolated }
        var calls = 0
        lateinit var updates: UpdateController
        try {
            ui.runOnIdle {
                updates = UpdateController(wrapped, scope, ReleaseClient { calls++; throw IOException("offline") })
                updates.startupCheck()
                updates.startupCheck()
            }
            ui.waitUntil(5000) { !updates.state.value.checking }
            assertEquals(2, calls) // mirror then official, despite two startup invocations
            assertNull(updates.state.value.message)
            assertNull(updates.state.value.sheet)
            ui.runOnIdle { updates.check() }
            ui.waitUntil(5000) { !updates.state.value.checking }
            assertEquals(4, calls)
            assertEquals("无法连接，请重试", updates.state.value.message)
        } finally {
            scope.cancel()
            isolated.deleteRecursively()
        }
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val out = File(ui.activity.getExternalFilesDir(null), "update-screens").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
