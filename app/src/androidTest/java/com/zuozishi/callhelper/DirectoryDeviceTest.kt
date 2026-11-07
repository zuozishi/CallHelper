package com.zuozishi.callhelper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zuozishi.callhelper.data.*
import com.zuozishi.callhelper.domain.DirectorySnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DirectoryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture() = instrumentation.context.assets.open("private/members.csv")
    @Test fun fullRealDatasetLookupAndReload() = runBlocking {
        val store = EncryptedDirectoryStore(context, "test-import")
        val repo = DirectoryRepository(store)
        assertEquals(4300, repo.importCsv(fixture()))
        val snapshot = repo.snapshot.value
        assertEquals(417, snapshot.unitCount); assertEquals(0, snapshot.duplicatePhoneGroups)
        assertTrue(snapshot.people.all { it.post.isNotEmpty() && it.fullOrgName.isNotEmpty() })
        assertTrue(snapshot.people.all { it.avatarUrl.startsWith("https://") })
        snapshot.people.forEach { p -> assertTrue(snapshot.lookup("+86" + p.phone).single().id == p.id) }
        val stored = File(context.noBackupFilesDir, "test-import.bin").readBytes()
        assertFalse(stored.toString(Charsets.UTF_8).contains("Id,Phone,Name"))
        val fresh = DirectoryRepository(store); fresh.load()
        assertTrue(fresh.snapshot.value.people == snapshot.people)
        assertTrue(fresh.snapshot.value.search(snapshot.people.first().fullOrgName).isNotEmpty())
    }
    @Test fun malformedCsvAndFailedDecryptKeepCurrentSnapshot() = runBlocking {
        val store = EncryptedDirectoryStore(context, "test-retention")
        val repo = DirectoryRepository(store); repo.importCsv(fixture())
        val old = repo.snapshot.value
        try { repo.importCsv("bad,csv".byteInputStream()); fail("bad CSV accepted") } catch (_: IllegalArgumentException) { }
        assertSame(old, repo.snapshot.value)
        try { repo.sync(EncryptedCsvTransport { byteArrayOf(1, 2, 3) }, CsvPayloadDecryptor { throw IllegalArgumentException("invalid envelope") }); fail("invalid envelope accepted") } catch (_: IllegalArgumentException) { }
        assertSame(old, repo.snapshot.value)
        val fresh = DirectoryRepository(store); fresh.load(); assertTrue(fresh.snapshot.value.people == old.people)
    }
    @Test fun tamperedCiphertextIsRejected() {
        val store = EncryptedDirectoryStore(context, "test-tamper")
        fixture().use { store.write(it.readBytes()) }
        val file = File(context.noBackupFilesDir, "test-tamper.bin")
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertTrue(runCatching { store.read() }.isFailure)
    }
    @Test fun remoteAdapterUsesSameCsvModel() = runBlocking {
        val bytes = fixture().use { it.readBytes() }
        val repo = DirectoryRepository(EncryptedDirectoryStore(context, "test-remote"))
        assertEquals(4300, repo.sync(EncryptedCsvTransport { bytes.reversedArray() }, CsvPayloadDecryptor { it.reversedArray() }))
        assertEquals(4300, repo.snapshot.value.people.size)
    }
    @Test fun interruptedAtomicWriteRecoversBackup() = runBlocking {
        val store = EncryptedDirectoryStore(context, "test-backup")
        val repo = DirectoryRepository(store); repo.importCsv(fixture())
        val file = File(context.noBackupFilesDir, "test-backup.bin")
        val backup = File(file.path + ".bak")
        assertTrue(file.renameTo(backup))
        assertTrue(store.exists())
        val fresh = DirectoryRepository(store); fresh.load()
        assertEquals(4300, fresh.snapshot.value.people.size)
    }
    @Test fun realAvatarEndpointDecodes() = runBlocking {
        val people = fixture().use { PersonnelCsv.parse(it.readBytes()) }
        val common = people.groupingBy { it.avatarUrl }.eachCount().maxBy { it.value }.key
        val address = people.first { it.avatarUrl != common }.avatarUrl
        val result = coil3.SingletonImageLoader.get(context).execute(
            coil3.request.ImageRequest.Builder(context).data(address).size(96).build())
        assertTrue("Avatar request did not decode", result is coil3.request.SuccessResult)
        if (result is coil3.request.SuccessResult) assertTrue(result.image.width > 0 && result.image.height > 0)
    }
    @Test fun debugFixtureUpgradeAddsAvatarsButPreservesManualImport() = runBlocking {
        val source = fixture().use { PersonnelCsv.parse(it.readBytes()) }
        val header = "Id,Phone,Name,Post,TopOrgName,FullOrgName\n"
        val oldCsv = header + source.joinToString("\n") { p ->
            listOf(p.id, p.phone, p.name, p.post, p.topOrgName, p.fullOrgName)
                .joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
        }
        val preferences = context.getSharedPreferences("fixture-upgrade-test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val repo = DirectoryRepository(EncryptedDirectoryStore(context, "test-fixture-upgrade"))
        repo.importCsv(oldCsv.byteInputStream())
        assertTrue(repo.snapshot.value.people.all { it.avatarUrl.isEmpty() })
        assertTrue(BundledData.refresh(context, repo, preferences))
        assertTrue(repo.snapshot.value.people.all { it.avatarUrl.isNotEmpty() })
        assertFalse(BundledData.refresh(context, repo, preferences))
        repo.importCsv(oldCsv.byteInputStream())
        preferences.edit().putString("directorySource", "manual").remove("fixtureSha256").commit()
        assertFalse(BundledData.refresh(context, repo, preferences))
        assertTrue(repo.snapshot.value.people.all { it.avatarUrl.isEmpty() })
    }
}
