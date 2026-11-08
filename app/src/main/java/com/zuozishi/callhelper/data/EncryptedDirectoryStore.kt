package com.zuozishi.callhelper.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedDirectoryStore(context: Context, name: String = "directory") {
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.bin"))
    private val alias = "callhelper.$name.v1"
    fun exists() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    fun delete() { file.delete() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    fun write(plain: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("CallHelperCSV1".toByteArray())
        val encrypted = cipher.doFinal(plain)
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(1, cipher.iv.size.toByte())); stream.write(cipher.iv); stream.write(encrypted)
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun read(): ByteArray {
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > 30 && bytes[0] == 1.toByte() && bytes[1] == 12.toByte()) { "本地人员库格式错误" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(2, 14)))
        cipher.updateAAD("CallHelperCSV1".toByteArray())
        return cipher.doFinal(bytes.copyOfRange(14, bytes.size))
    }
}
