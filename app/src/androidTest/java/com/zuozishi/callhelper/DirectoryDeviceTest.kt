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
    private fun fixture() = ("Id,Phone,Name,Post,TopOrgName,FullOrgName,AvatarUrl\n" +
        "a,13800000001,测试甲,开发,单位甲,集团·单位甲·研发,https://example.invalid/a.png\n" +
        "b,13800000002,测试乙,经理,单位甲,集团·单位甲,https://example.invalid/b.png\n" +
        "c,13800000003,测试丙,开发,单位乙,集团·单位乙,https://example.invalid/c.png\n").byteInputStream()
    @Test fun syntheticLookupAndReload() = runBlocking {
        val store = EncryptedDirectoryStore(context, "test-import")
        val repo = DirectoryRepository(store)
        assertEquals(3, repo.importCsv(fixture()))
        val snapshot = repo.snapshot.value
        assertEquals(2, snapshot.unitCount); assertEquals(0, snapshot.duplicatePhoneGroups)
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
        assertEquals(3, repo.sync(EncryptedCsvTransport { bytes.reversedArray() }, CsvPayloadDecryptor { it.reversedArray() }))
        assertEquals(3, repo.snapshot.value.people.size)
    }
    @Test fun interruptedAtomicWriteRecoversBackup() = runBlocking {
        val store = EncryptedDirectoryStore(context, "test-backup")
        val repo = DirectoryRepository(store); repo.importCsv(fixture())
        val file = File(context.noBackupFilesDir, "test-backup.bin")
        val backup = File(file.path + ".bak")
        assertTrue(file.renameTo(backup))
        assertTrue(store.exists())
        val fresh = DirectoryRepository(store); fresh.load()
        assertEquals(3, fresh.snapshot.value.people.size)
    }
}
