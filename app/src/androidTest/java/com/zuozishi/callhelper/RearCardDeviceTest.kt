package com.zuozishi.callhelper

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.zuozishi.callhelper.domain.Personnel
import com.zuozishi.callhelper.rear.NativeRearProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in hardware acceptance: scoped native host must already have been restarted. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class RearCardDeviceTest {
    @Test fun nativeCardLifecyclePrivacyAndProviderBoundary() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as CallHelperApplication
        val prefs = app.prefs
        val original = listOf("enabled", "rearEnabled", "hideUnmatched", "rearShowLocked").associateWith {
            if (prefs.contains(it)) prefs.getBoolean(it, false) else null
        }
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 12000
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("$message; status=${app.rear.status.value}", predicate())
        }
        try {
            assertEquals(NativeRearProvider.SUPPORTED_VERSION, app.rear.targetVersion())
            val person = Personnel("rear-fixture", "10000", "背屏测试", "技术验证", "测试单位", "测试单位／测试部门")
            inst.runOnMainSync {
                prefs.edit().putBoolean("enabled", true).putBoolean("hideUnmatched", true).putBoolean("rearShowLocked", true).commit()
                app.rear.setEnabled(true)
                app.rear.show(person.phone, listOf(person), true)
            }
            val first = app.rear.card.value!!.token
            await("Native RemoteViews not attached") { app.rear.hostState() == Triple(first, true, true) }
            val selfData = app.contentResolver.call(NativeRearProvider.URI, "views", null, null)!!
            assertTrue(selfData.getBoolean("active"))
            inst.runOnMainSync {
                val rendered = app.rear.render(false).apply(app, null)
                assertEquals(person.name, rendered.findViewById<TextView>(R.id.rear_name).text.toString())
                prefs.edit().putBoolean("rearShowLocked", false).commit()
                val aod = app.rear.render(true).apply(app, null)
                assertEquals("来电信息", aod.findViewById<TextView>(R.id.rear_name).text.toString())
                assertEquals(View.GONE, aod.findViewById<TextView>(R.id.rear_org).visibility)
                app.rear.show(person.phone, listOf(person.copy(name = "替换测试")), true)
                app.rear.dismiss(first)
                assertNotNull(app.rear.card.value)
            }
            val replacement = app.rear.card.value!!.token
            await("Replacement did not reach native host") { app.rear.hostState() == Triple(replacement, true, true) }
            inst.runOnMainSync { app.rear.dismissCall() }
            await("Native card not removed") { !app.rear.hostState().second && !app.rear.hostState().third }
            inst.runOnMainSync {
                app.rear.show("10001", emptyList(), true)
                assertNull(app.rear.card.value)
                app.rear.show(person.phone, listOf(person), true)
                app.rear.hideAfter(150)
            }
            await("Expiry did not clear payload") { app.rear.card.value == null }
            await("Expiry did not remove native card") { !app.rear.hostState().second }
            val pipes = inst.uiAutomation.executeShellCommandRwe("content call --uri content://com.zuozishi.callhelper.rear --method views")
            pipes[1].close()
            val denial = listOf(pipes[0], pipes[2]).joinToString("\n") { pipe ->
                ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { it.readText() }
            }
            assertTrue("Shell must not read personnel RemoteViews", denial.contains("SecurityException") || denial.contains("Unexpected caller"))
            inst.runOnMainSync {
                app.rear.show(person.phone, listOf(person))
                val rendered = app.rear.render(false).apply(app, null)
                assertTrue(rendered.findViewById<View>(R.id.rear_close).performClick())
            }
            await("Close PendingIntent did not clear card") { app.rear.card.value == null }
            await("Close did not remove native card") { !app.rear.hostState().second }
            inst.runOnMainSync { app.rear.show(person.phone, listOf(person)); app.rear.setEnabled(false) }
            await("Disable did not remove native card") { !app.rear.hostState().second }
        } finally {
            inst.runOnMainSync {
                app.rear.dismiss()
                prefs.edit().apply { original.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) } }.commit()
                app.rear.refreshStatus()
            }
        }
    }
}
