package com.zuozishi.callhelper

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HierarchyDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun hierarchyNavigationSearchAndReturn() {
        fun checkpoint(stage: String) { android.util.Log.i("HierarchyCheck", stage) }
        checkpoint("launch")
        val app = ui.activity.application as CallHelperApplication
        ui.waitUntil(30000) { app.directory.snapshot.value.people.isNotEmpty() }
        ui.onNodeWithTag("nav-1").performClick()
        checkpoint("directory")
        ui.onNodeWithTag("organization-path").onChildren().filter(hasText("全部组织")).assertCountEquals(1)
        ui.onAllNodesWithTag("directory-person").assertCountEquals(0)
        ui.onAllNodesWithTag("organization-folder").onFirst().performClick()
        checkpoint("entered-root")
        ui.onNodeWithContentDescription("返回上一级").assertExists()
        val beforeSearch = ui.onNodeWithTag("organization-path").fetchSemanticsNode().children.map { it.config.toString() }
        val first = app.directory.snapshot.value.people.first()
        ui.onNodeWithTag("search-input").performTextInput(first.phone)
        ui.waitUntil(10000) { ui.onAllNodesWithText("1 人").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("result-count").assertTextEquals("1 人")
        checkpoint("searched")
        ui.onNodeWithTag("app-list").performScrollToNode(hasTestTag("directory-person"))
        ui.onNodeWithTag("directory-person").performClick()
        checkpoint("detail")
        ui.onNodeWithContentDescription("拨号").assertHasClickAction()
        val dial = personnelDialIntent(first.phone)
        assertEquals(android.content.Intent.ACTION_DIAL, dial.action)
        assertEquals("tel", dial.data?.scheme)
        assertEquals(first.phone, dial.data?.schemeSpecificPart)
        checkpoint("dial-contract")
        ui.onNodeWithContentDescription("关闭").performClick()
        checkpoint("closed")
        ui.onNodeWithTag("app-list").performScrollToIndex(0)
        ui.onNodeWithContentDescription("清空搜索").performClick()
        checkpoint("cleared")
        ui.waitForIdle()
        assertEquals(beforeSearch, ui.onNodeWithTag("organization-path").fetchSemanticsNode().children.map { it.config.toString() })
        ui.onNodeWithContentDescription("返回上一级").performClick()
        checkpoint("returned")
        ui.onNodeWithTag("organization-path").onChildren().filter(hasText("全部组织")).assertCountEquals(1)
        ui.onNodeWithContentDescription("返回上一级").assertDoesNotExist()
    }
}
