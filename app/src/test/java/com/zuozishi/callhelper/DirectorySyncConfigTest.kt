package com.zuozishi.callhelper

import com.zuozishi.callhelper.data.DirectorySyncConfig
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class DirectorySyncConfigTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 251.toByte() })
    private val url = "https://example.com/export?token=a%2Bb&scope=one%26two"

    @Test fun configurationSurvivesRealQrEncodingAndDecoding() {
        val config = DirectorySyncConfig.create(" $url ", " $key ")
        val matrix = QRCodeWriter().encode(config.toQrContent(), BarcodeFormat.QR_CODE, 1024, 1024)
        val pixels = IntArray(matrix.width * matrix.height) { if (matrix[it % matrix.width, it / matrix.width]) 0xff000000.toInt() else -1 }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))).text
        assertEquals(config, DirectorySyncConfig.fromQrContent(decoded))
        assertEquals(url, config.url)
        assertFalse(config.toString().contains(key))
    }

    @Test fun invalidUrlsAndKeysAreRejected() {
        listOf("", "http://example.com/export", "https://", "https://user:password@example.com/export", "https://example.com/export#secret", "https://example.com:99999/export").forEach {
            assertTrue(it, runCatching { DirectorySyncConfig.create(it, key) }.isFailure)
        }
        listOf("", "invalid!", Base64.getEncoder().encodeToString(ByteArray(16))).forEach {
            assertTrue(runCatching { DirectorySyncConfig.create(url, it) }.isFailure)
        }
    }

    @Test fun unrelatedOrAmbiguousQrCodesAreRejected() {
        val valid = DirectorySyncConfig.create(url, key).toQrContent()
        listOf("https://example.com", valid.replace("v=1", "v=2"), valid + "&v=1", valid.replace("AES-256-GCM", "AES-CBC"), valid + "#extra", "x".repeat(4097)).forEach {
            assertTrue(runCatching { DirectorySyncConfig.fromQrContent(it) }.isFailure)
        }
    }
}
