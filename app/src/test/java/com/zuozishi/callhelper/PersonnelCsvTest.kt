package com.zuozishi.callhelper

import com.zuozishi.callhelper.data.PersonnelCsv
import com.zuozishi.callhelper.domain.DirectorySnapshot
import com.zuozishi.callhelper.domain.PhoneKey
import org.junit.Assert.*
import org.junit.Test

class PersonnelCsvTest {
    private val header = "Id,Phone,Name,Post,TopOrgName,FullOrgName\r\n"
    private val row = "a,13800000001,林知远,经理,星河,星河/产品中心"
    private fun parse(s: String) = PersonnelCsv.parse(s.toByteArray())
    @Test fun bomAndCrLf() { val p = parse("\uFEFF" + header + row + "\r\n\r\n"); assertEquals(1, p.size); assertEquals("经理", p[0].post) }
    @Test fun quotesCommasNewlinesAndEscapedQuotes() {
        val p = parse(header + "a,13800000001,林知远,\"经理,产品\",星河,\"星河/\"\"第一\"\"部\n产品组\"")
        assertEquals("经理,产品", p[0].post); assertEquals("星河/\"第一\"部\n产品组", p[0].fullOrgName)
    }
    @Test fun reorderedHeaderAndExtraColumn() { assertEquals("林知远", parse("Name,Id,Phone,FullOrgName,TopOrgName,Post,Extra\n林知远,a,13800000001,星河/产品,星河,经理,x")[0].name) }
    @Test fun unknownAndInternationalNumbersDoNotSuffixMatch() {
        val s = DirectorySnapshot(parse(header + row))
        assertEquals(1, s.lookup("+86 (138) 0000-0001").size)
        assertEquals(1, s.lookup("008613800000001").size)
        assertTrue(s.lookup("00000001").isEmpty()); assertTrue(s.lookup("+113800000001").isEmpty())
        assertTrue(s.lookup("").isEmpty()); assertTrue(s.lookup("private").isEmpty())
    }
    @Test fun sharedPhoneKeepsAllIdentities() {
        val s = DirectorySnapshot(parse(header + row + "\n" + row.replace("a,", "b,").replace("林知远", "陈亦宁")))
        assertEquals(2, s.lookup("13800000001").size); assertEquals(1, s.duplicatePhoneGroups)
    }
    @Test fun searchByPostUnitPathOrNumber() {
        val s = DirectorySnapshot(parse(header + row))
        listOf("经理", "星河", "产品中心", "林知远", "+8613800000001").forEach { assertEquals(1, s.search(it).size) }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateIds() { parse(header + row + "\n" + row) }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnclosedQuote() { parse(header + row + ",\"bad") }
    @Test(expected = IllegalArgumentException::class) fun rejectsStrayQuote() { parse(header + row.replace("经理", "经\"理")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsTextAfterQuote() { parse(header + row.replace("经理", "\"经理\"x")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingFields() { parse("Id,Phone,Name\na,13800000001,林知远") }
    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateHeader() { parse(header.trim() + ",Id\n" + row + ",a") }
    @Test(expected = IllegalArgumentException::class) fun rejectsRaggedRows() { parse(header + row + ",extra") }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidUtf8() { PersonnelCsv.parse(byteArrayOf(0xC3.toByte(), 0x28)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyDirectory() { parse(header) }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidPhone() { parse(header + row.replace("13800000001", "not-a-number")) }
    @Test fun hiddenNumberRejected() { assertEquals("", PhoneKey.normalize("匿名来电")) }
    @Test fun optionalAvatarUrlPreservesOldCsvCompatibility() {
        assertEquals("", parse(header + row).single().avatarUrl)
        val avatarHeader = header.trim() + ",AvatarUrl\n"
        assertEquals("https://example.invalid/a.png", parse(avatarHeader + row + ",https://example.invalid/a.png").single().avatarUrl)
        listOf("http://example.invalid/a.png", "file:///private/a.png", "https://user:password@example.invalid/a.png", "invalid").forEach {
            assertEquals("", parse(avatarHeader + row + "," + it).single().avatarUrl)
        }
    }
}
