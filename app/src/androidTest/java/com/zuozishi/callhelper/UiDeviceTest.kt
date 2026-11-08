package com.zuozishi.callhelper

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UiDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun activeDirectorySearchAndLiquidTabs() {
        val app = ui.activity.application as CallHelperApplication
        ui.waitUntil(90000) { app.initialSync.isCompleted && app.directory.snapshot.value.people.isNotEmpty() }
        ui.onNodeWithTag("person-count").assertTextEquals(app.directory.snapshot.value.people.size.toString())
        ui.onNodeWithTag("liquid-navigation").assertExists()
        ui.onNodeWithTag("nav-1").performClick()
        val first = app.directory.snapshot.value.people.first()
        ui.onNodeWithTag("search-input").performTextInput(first.phone)
        ui.waitUntil(5000) { ui.onAllNodesWithText("1 人").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("result-count").assertTextEquals("1 人")
        ui.onNodeWithTag("nav-3").performClick()
        ui.onNodeWithTag("theme-dropdown").performClick()
        ui.onNodeWithText("深色").performClick()
        ui.onNodeWithTag("theme-dropdown").performClick()
        ui.onNodeWithText("浅色").performClick()
        ui.onNodeWithTag("nav-0").performClick()
    }
    @Test fun realWindowOverlayAndDismiss() {
        val app = ui.activity.application as CallHelperApplication
        ui.waitUntil(90000) { app.initialSync.isCompleted && app.directory.snapshot.value.people.isNotEmpty() }
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.executeShellCommand("appops set ${ui.activity.packageName} SYSTEM_ALERT_WINDOW allow").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        var shown = false
        inst.runOnMainSync { app.prefs.edit().putBoolean("enabled", true).commit(); shown = app.overlay.show("", app.directory.snapshot.value.people.take(1)) }
        assertTrue(shown)
        inst.runOnMainSync { assertTrue(app.overlay.isShowing); app.overlay.dismiss(); assertFalse(app.overlay.isShowing) }
    }
}
