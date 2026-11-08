package com.zuozishi.callhelper.data

import com.zuozishi.callhelper.domain.DirectorySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Download and authenticated decryption complete before an atomic library replacement. */
fun interface EncryptedCsvTransport { suspend fun download(): ByteArray }
fun interface CsvPayloadDecryptor { fun decrypt(payload: ByteArray): ByteArray }

class DirectoryRepository(private val store: EncryptedDirectoryStore) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(DirectorySnapshot(emptyList()))
    val snapshot = mutable.asStateFlow()
    suspend fun load(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { if (store.exists()) mutable.value = DirectorySnapshot(PersonnelCsv.parse(store.read(), allowDuplicateIds = true)) }
    }
    suspend fun importCsv(input: InputStream): Int = withContext(Dispatchers.IO) {
        val bytes = input.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                total += count
                require(total <= PersonnelCsv.MAX_BYTES) { "人员文件超过 20 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        importBytes(bytes)
    }
    private suspend fun importBytes(bytes: ByteArray, allowDuplicateIds: Boolean = false): Int = mutex.withLock {
        val next = DirectorySnapshot(PersonnelCsv.parse(bytes, allowDuplicateIds))
        store.write(bytes) // AtomicFile and authenticated encryption preserve last good version on failure.
        mutable.value = next
        next.people.size
    }
    suspend fun sync(transport: EncryptedCsvTransport, decryptor: CsvPayloadDecryptor): Int = withContext(Dispatchers.IO) {
        val encrypted = transport.download()
        require(encrypted.size <= PersonnelCsv.MAX_BYTES + 1024) { "下载文件过大" }
        importBytes(decryptor.decrypt(encrypted), allowDuplicateIds = true)
    }
}
