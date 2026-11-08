package com.zuozishi.callhelper.data

import com.zuozishi.callhelper.domain.Personnel
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Strict UTF-8 / RFC 4180 reader. Parsing completes before the active directory changes. */
object PersonnelCsv {
    const val MAX_BYTES = 20 * 1024 * 1024
    private val columns = listOf("Id", "Phone", "Name", "Post", "TopOrgName", "FullOrgName")
    fun parse(bytes: ByteArray, allowDuplicateIds: Boolean = false): List<Personnel> {
        require(bytes.size <= MAX_BYTES) { "人员文件超过 20 MB" }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: Exception) { throw IllegalArgumentException("请选择 UTF-8 编码的 CSV") }
        val rows = rows(text)
        require(rows.isNotEmpty()) { "人员文件为空" }
        val header = rows.first().map { it.trim() }
        require(header.distinct().size == header.size && columns.all { it in header }) { "CSV 字段不完整或重复" }
        val indices = columns.map { header.indexOf(it) }
        val ids = HashSet<String>()
        val occurrences = HashMap<String, Int>()
        val people = rows.drop(1).filterNot { it.all(String::isBlank) }.mapIndexed { index, row ->
            require(row.size == header.size) { "第 ${index + 2} 行列数不正确" }
            val fields = indices.map { row[it].trim() }
            require(fields.all { it.length <= 2000 }) { "第 ${index + 2} 行字段过长" }
            require(fields[0].isNotBlank() && fields[2].isNotBlank() && com.zuozishi.callhelper.domain.PhoneKey.normalize(fields[1]).isNotEmpty()) { "第 ${index + 2} 行缺少有效人员信息" }
            require(ids.add(fields[0]) || allowDuplicateIds) { "第 ${index + 2} 行人员 Id 重复" }
            val occurrence = occurrences.getOrDefault(fields[0], 0)
            occurrences[fields[0]] = occurrence + 1
            val avatar = header.indexOf("AvatarUrl").takeIf { it >= 0 }?.let { row[it] }.orEmpty()
            Personnel(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], com.zuozishi.callhelper.domain.AvatarAddress.normalize(avatar), "${fields[0].length}:${fields[0]}:$occurrence")
        }
        require(people.isNotEmpty()) { "CSV 没有人员记录" }
        return people
    }

    private fun rows(text: String): List<List<String>> {
        val result = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var i = 0
        fun endField() { row.add(field.toString()); field.setLength(0); closed = false }
        fun endRow() { endField(); result.add(row); row = ArrayList(); require(result.size <= 100001) { "人员记录过多" } }
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
                    else { quoted = false; closed = true }
                } else field.append(ch)
            } else when (ch) {
                '"' -> { require(field.isEmpty() && !closed) { "CSV 引号格式不正确" }; quoted = true }
                ',' -> endField()
                '\r', '\n' -> { endRow(); if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++ }
                else -> { require(!closed) { "CSV 引号后的内容不正确" }; field.append(ch) }
            }
            i++
        }
        require(!quoted) { "CSV 引号未闭合" }
        if (field.isNotEmpty() || row.isNotEmpty() || closed) endRow()
        return result
    }
}
