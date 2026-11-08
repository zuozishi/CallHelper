package com.zuozishi.callhelper.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/** Versioned, portable configuration. Never include its contents in logs. */
@ConsistentCopyVisibility
data class DirectorySyncConfig private constructor(val url: String, val keyBase64: String) {
    override fun toString() = "DirectorySyncConfig(redacted)"
    fun toQrContent(): String = "callhelper://directory-sync?v=1&algorithm=AES-256-GCM&url=${encode(url)}&key=${encode(keyBase64)}"

    companion object {
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        fun create(url: String, key: String): DirectorySyncConfig {
            val cleanUrl = url.trim()
            require(cleanUrl.length in 1..1024) { "同步 URL 不能为空且不能超过 1024 个字符" }
            val uri = try { URI(cleanUrl) } catch (_: Exception) { throw IllegalArgumentException("同步 URL 格式无效") }
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) {
                "请输入有效的 HTTPS 同步 URL，不包含用户名、密码或片段"
            }
            val bytes = try { Base64.getDecoder().decode(key.trim()) }
                catch (_: IllegalArgumentException) { throw IllegalArgumentException("解密密钥必须为 Base64 格式") }
            require(bytes.size == 32) { "解密密钥必须为 32 字节的 AES-256 密钥" }
            return DirectorySyncConfig(uri.toASCIIString(), Base64.getEncoder().encodeToString(bytes))
        }

        fun fromQrContent(content: String): DirectorySyncConfig {
            require(content.length <= 4096) { "二维码内容过长" }
            try {
                val uri = URI(content)
                require(uri.scheme == "callhelper" && uri.host == "directory-sync" && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null && uri.rawUserInfo == null && uri.port == -1)
                val fields = uri.rawQuery.split('&').map {
                    val parts = it.split('=', limit = 2)
                    require(parts.size == 2)
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                }
                require(fields.size == 4 && fields.map { it.first }.toSet() == setOf("v", "algorithm", "url", "key"))
                val values = fields.toMap()
                require(values["v"] == "1" && values["algorithm"] == "AES-256-GCM")
                return create(values.getValue("url"), values.getValue("key"))
            } catch (_: Exception) { throw IllegalArgumentException("不是有效的人员库同步配置二维码") }
        }
    }
}
