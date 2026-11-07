package com.zuozishi.callhelper
import android.content.Context
import java.io.InputStream
import com.zuozishi.callhelper.data.DirectoryRepository
import com.zuozishi.callhelper.data.PersonnelCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
object BundledData {
    fun open(context: Context): InputStream? = runCatching { context.assets.open("private/members.csv") }.getOrNull()
    suspend fun refresh(context: Context, repository: DirectoryRepository,
        preferences: android.content.SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE),
    ): Boolean = withContext(Dispatchers.IO) {
        val bytes = open(context)?.use { it.readBytes() } ?: return@withContext false
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (preferences.getString("fixtureSha256", null) == fingerprint) return@withContext false
        val source = preferences.getString("directorySource", null)
        if (source == "manual") return@withContext false
        val current = repository.snapshot.value.people
        val incoming = PersonnelCsv.parse(bytes)
        // Migrate the original debug package only when its core records still match.
        val eligible = current.isEmpty() || source == "bundled" ||
            current.map { it.copy(avatarUrl = "") } == incoming.map { it.copy(avatarUrl = "") }
        if (!eligible) return@withContext false
        repository.importCsv(bytes.inputStream())
        preferences.edit().putString("directorySource", "bundled").putString("fixtureSha256", fingerprint).apply()
        true
    }
}
