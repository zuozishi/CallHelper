package com.zuozishi.callhelper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zuozishi.callhelper.data.DirectoryRepository
import com.zuozishi.callhelper.data.EncryptedDirectoryStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteSyncDeviceTest {
    @Test fun authenticatedCacheReloadAndNoCsvResources() = runBlocking {
        android.util.Log.i("RemoteSyncCheck", "test-entered")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CallHelperApplication
        // Live transfer is checked during a normal foreground launch. Instrumentation
        // independently checks its authenticated offline cache without awaiting networking.
        withTimeout(30_000) { app.ready.await() }
        android.util.Log.i("RemoteSyncCheck", "local-ready")
        assertEquals("remote", app.prefs.getString("directorySource", null))
        val active = app.directory.snapshot.value
        assertTrue(active.people.isNotEmpty())
        assertEquals(active.people.size, active.organizations.root.total)
        assertTrue(active.people.all { active.lookup(it.phone).any { match -> match.id == it.id } })
        val reloaded = DirectoryRepository(EncryptedDirectoryStore(app))
        reloaded.load()
        android.util.Log.i("RemoteSyncCheck", "reload-completed")
        assertEquals(active.people.size, reloaded.snapshot.value.people.size)
        assertTrue(active.people == reloaded.snapshot.value.people)
        assertTrue(runCatching { app.assets.open("private/members.csv").close() }.isFailure)
        assertTrue(app.prefs.getLong("updated", 0) > 0)
    }
}
