package com.zuozishi.callhelper.data

import java.net.URL
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection

/** Downloads the documented text/plain Base64 envelope, with no cookies or credentials. */
class EmployeeExportTransport(private val url: String) : EncryptedCsvTransport {
    override suspend fun download(): ByteArray {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "text/plain")
            connection.setRequestProperty("Cache-Control", "no-store")
            require(connection.responseCode == 200) { "人员同步失败（HTTP ${connection.responseCode}）" }
            val maximum = ((PersonnelCsv.MAX_BYTES + 29L + 2) / 3 * 4 + 2048).toInt()
            require(connection.contentLengthLong <= maximum) { "下载文件过大" }
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= maximum) { "下载文件过大" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            return decodeExportBody(bytes)
        } finally { connection.disconnect() }
    }
}

internal fun decodeExportBody(body: ByteArray): ByteArray {
    val text = body.toString(Charsets.US_ASCII).trim()
    return try { Base64.getDecoder().decode(text) }
    catch (_: IllegalArgumentException) { throw IllegalArgumentException("导出响应不是有效的 Base64") }
}

class EmployeeExportDecryptor(keyBase64: String) : CsvPayloadDecryptor {
    private val key = try { Base64.getDecoder().decode(keyBase64) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("导出密钥配置无效") }
    init { require(key.size == 32) { "导出密钥必须为 32 字节" } }
    override fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size in 29..PersonnelCsv.MAX_BYTES + 29 && payload[0] == 1.toByte()) { "不支持的导出格式" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, payload.copyOfRange(1, 13)))
        cipher.updateAAD(payload.copyOfRange(0, 1))
        // Java expects ciphertext || tag; the API sends version || nonce || tag || ciphertext.
        val authenticated = payload.copyOfRange(29, payload.size) + payload.copyOfRange(13, 29)
        return try { cipher.doFinal(authenticated) }
        catch (_: java.security.GeneralSecurityException) { throw IllegalArgumentException("导出数据认证失败，原人员库已保留") }
    }
}
