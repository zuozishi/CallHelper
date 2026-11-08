package com.zuozishi.callhelper

import com.zuozishi.callhelper.data.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class EmployeeExportTest {
    private val key = ByteArray(32) { it.toByte() }
    private val decryptor = EmployeeExportDecryptor(Base64.getEncoder().encodeToString(key))
    private val csv = "Id,Phone,Name,Post,TopOrgName,FullOrgName,AvatarUrl\r\n\"001\",\"13800000001\",\"测试人员\",\"开发\",\"单位\",\"集团·单位·研发\",\"\"\r\n".toByteArray()
    private fun packet(plain: ByteArray = csv): ByteArray {
        val nonce = ByteArray(12) { (it + 20).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(byteArrayOf(1))
        val encrypted = cipher.doFinal(plain)
        return byteArrayOf(1) + nonce + encrypted.takeLast(16).toByteArray() + encrypted.dropLast(16).toByteArray()
    }
    @Test fun documentedTagBeforeCiphertextRoundTripsCsv() {
        val body = (" \r\n" + Base64.getEncoder().encodeToString(packet()) + "\r\n").toByteArray()
        val plain = decryptor.decrypt(decodeExportBody(body))
        assertArrayEquals(csv, plain)
        assertEquals("001", PersonnelCsv.parse(plain).single().id)
    }
    @Test fun rejectsChangesToVersionNonceTagAndCiphertext() {
        listOf(0, 1, 13, 29).forEach { index ->
            val bytes = packet(); bytes[index] = (bytes[index].toInt() xor 1).toByte()
            assertTrue(runCatching { decryptor.decrypt(bytes) }.isFailure)
        }
    }
    @Test fun wrongKeyDoesNotReleasePlaintext() {
        val wrong = EmployeeExportDecryptor(Base64.getEncoder().encodeToString(ByteArray(32) { 9 }))
        assertTrue(runCatching { wrong.decrypt(packet()) }.isFailure)
    }
    @Test fun invalidBase64AndTruncatedEnvelopeAreRejected() {
        assertTrue(runCatching { decodeExportBody("not base64!".toByteArray()) }.isFailure)
        for (size in 0..28) assertTrue(runCatching { decryptor.decrypt(ByteArray(size)) }.isFailure)
    }
    @Test fun invalidKeysFailClosed() {
        assertTrue(runCatching { EmployeeExportDecryptor("invalid") }.isFailure)
        assertTrue(runCatching { EmployeeExportDecryptor(Base64.getEncoder().encodeToString(ByteArray(16))) }.isFailure)
    }
    @Test fun exportMayContainMultipleMainPostsForTheSameEmployee() {
        val bytes = ("Id,Phone,Name,Post,TopOrgName,FullOrgName\n" +
            "001,13800000001,测试人员,开发,甲,集团·甲\n001,13800000001,测试人员,经理,乙,集团·乙\n").toByteArray()
        val rows = PersonnelCsv.parse(bytes, allowDuplicateIds = true)
        assertEquals(2, rows.size)
        assertEquals(rows[0].id, rows[1].id)
        assertNotEquals(rows[0].rowKey, rows[1].rowKey)
        assertEquals(2, com.zuozishi.callhelper.domain.DirectorySnapshot(rows).lookup("13800000001").size)
        assertTrue(runCatching { PersonnelCsv.parse(bytes) }.isFailure)
    }
}
