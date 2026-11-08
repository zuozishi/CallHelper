package com.zuozishi.callhelper

import com.zuozishi.callhelper.domain.*
import org.junit.Assert.*
import org.junit.Test

class OrganizationIndexTest {
    private fun person(id: String, top: String, path: String) = Personnel(id, "13800000001", id, "开发", top, path)
    @Test fun exportAncestorsArePreservedAndCountsIncludeDescendants() {
        val index = OrganizationIndex(listOf(person("a", "单位", "集团·地区·单位·研发·移动"), person("b", "单位", "集团·地区·单位")))
        val group = index.nodes.getValue(index.root.children.single())
        assertEquals("集团", group.name); assertEquals(2, group.total)
        val top = index.nodes.values.single { it.name == "单位" }
        assertEquals(listOf("b"), top.people.map { it.id })
        val mobile = index.nodes.values.single { it.name == "移动" }
        assertEquals(1, mobile.total)
        assertEquals(listOf("全部组织", "集团", "地区", "单位", "研发", "移动"), index.path(mobile.key).map { it.name })
    }
    @Test fun identicalDepartmentNamesNeverMergeAcrossUnits() {
        val index = OrganizationIndex(listOf(person("a", "甲", "甲/研发"), person("b", "乙", "乙/研发")))
        assertEquals(2, index.root.children.size)
        assertEquals(2, index.nodes.values.count { it.name == "研发" })
        assertEquals(2, index.nodes.values.flatMap { it.people }.size)
    }
    @Test fun missingOrganizationRetainsEveryoneAndUnknownPathsArePreserved() {
        val index = OrganizationIndex(listOf(person("a", "", ""), person("b", "单位", "外部·部门")))
        assertEquals(2, index.root.total)
        assertEquals(1, index.nodes.values.single { it.name == "未归属组织" }.total)
        assertEquals(listOf("全部组织", "外部", "部门"), index.path(index.nodes.values.single { it.name == "部门" }.key).map { it.name })
    }
    @Test fun emptySegmentsAndPathKeyPunctuationAreSafe() {
        val index = OrganizationIndex(listOf(person("a", "甲", "甲// 研发 /"), person("b", "甲", "甲/3:研发")))
        assertEquals(2, index.nodes.getValue(index.root.children.single()).children.size)
        assertEquals(2, index.nodes.values.flatMap { it.people }.size)
        assertEquals(index.root, index.path("missing").single())
    }
    @Test fun largeLibraryIsIndexedOnceWithoutDuplicatingPersonnel() {
        val people = (0 until 10000).map { person("p$it", "单位${it % 100}", "上级·单位${it % 100}·研发·组${it % 7}") }
        val index = OrganizationIndex(people)
        assertEquals(1, index.root.children.size)
        assertEquals("上级", index.nodes.getValue(index.root.children.single()).name)
        assertEquals(100, index.nodes.getValue(index.root.children.single()).children.size)
        assertEquals(10000, index.root.total)
        assertEquals(10000, index.nodes.values.sumOf { it.people.size })
        assertTrue(index.root.people.isEmpty())
    }
}
