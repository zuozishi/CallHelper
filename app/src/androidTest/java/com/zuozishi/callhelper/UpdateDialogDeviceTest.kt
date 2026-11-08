package com.zuozishi.callhelper

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zuozishi.callhelper.ui.UpdateDialog
import com.zuozishi.callhelper.update.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateDialogDeviceTest {
    @get:Rule val ui = createComposeRule()

    @Test fun updateDownloadAndPermissionSheetsRenderActionableControls() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val updates = UpdateController(context, scope)
        val nextVersion = BuildConfig.VERSION_NAME.substringBeforeLast('.') + "." + (BuildConfig.VERSION_NAME.substringAfterLast('.').toInt() + 1)
        val release = AppRelease("v$nextVersion", "2026-11-08", "新增关于页与应用版本检查\n支持应用内下载并调起安装\n优化启动检查与下载体验",
            listOf(ReleaseAsset("callhelper-$nextVersion-release.apk", "$REPOSITORY/releases/download/v$nextVersion/callhelper-$nextVersion-release.apk", 19_503_514, null)))
        val display = mutableStateOf(UpdateState(release = release, sheet = UpdateSheet.Release))
        try {
            ui.setContent { MiuixTheme { UpdateDialog(display.value, updates, {}, {}) } }
            ui.onNodeWithTag("download-update").assertIsDisplayed()
            screenshot("update-available")
            ui.runOnIdle { display.value = display.value.copy(sheet = UpdateSheet.Download, downloading = true, downloaded = 12_092_179, total = 19_503_514) }
            ui.onNodeWithText("收起").assertIsDisplayed()
            ui.onNodeWithText("取消下载").assertIsDisplayed()
            screenshot("downloading")
            ui.runOnIdle { display.value = display.value.copy(sheet = UpdateSheet.Permission, downloading = false, ready = true) }
            ui.onNodeWithText("前往设置").assertIsDisplayed()
            screenshot("install-permission")
        } finally { scope.cancel() }
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation()
        val bitmap = inst.uiAutomation.takeScreenshot()
        val folder = File(inst.targetContext.getExternalFilesDir(null), "update-screens").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
