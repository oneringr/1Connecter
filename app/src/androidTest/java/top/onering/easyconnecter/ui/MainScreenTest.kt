package top.onering.easyconnecter.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import top.onering.easyconnecter.MainUiState
import top.onering.easyconnecter.model.Account
import java.io.File

class MainScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var csvExportRequests = 0
    private var restartRequests = 0
    private var restartDismissals = 0

    @Before
    fun renderScreen() {
        csvExportRequests = 0
        restartRequests = 0
        restartDismissals = 0
        composeRule.setContent {
            var state by remember {
                mutableStateOf(
                    MainUiState(
                        accounts = listOf(
                            Account("20230001", "123456", "测试账号", "", "", "510000200001011234")
                        )
                    )
                )
            }
            MainScreen(
                state = state,
                onSelectCampus = {},
                onImportCsv = {},
                onReimportCsv = {},
                onExportCsv = { csvExportRequests++ },
                onToggleKeepAlive = {},
                onToggleAutoScroll = {},
                onShowAccounts = { state = state.copy(showAccountManager = true) },
                onDismissAccounts = { state = state.copy(showAccountManager = false) },
                onDeleteAccount = {},
                onShowCampusDialog = { state = state.copy(showCampusDialog = true) },
                onDismissCampusDialog = { state = state.copy(showCampusDialog = false) },
                onConfirmCampusChange = {},
                onShowAbout = { state = state.copy(showAboutDialog = true) },
                onDismissAbout = { state = state.copy(showAboutDialog = false) },
                onShowLogout = { state = state.copy(showLogoutDialog = true) },
                onDismissLogout = { state = state.copy(showLogoutDialog = false) },
                onConfirmLogout = {
                    state = state.copy(
                        showLogoutDialog = false,
                        restartAfterLogoutMessage = "校园网已解绑并注销"
                    )
                },
                onRestartAfterLogout = {
                    restartRequests++
                    state = state.copy(restartAfterLogoutMessage = null)
                },
                onDismissRestartAfterLogout = {
                    restartDismissals++
                    state = state.copy(restartAfterLogoutMessage = null)
                },
                onExportLogs = {}
            )
        }
    }

    @Test
    fun drawerOpensAccountManager() {
        openDrawerAndClick("管理账号")
        composeRule.onNodeWithText("账号管理 (1)").assertIsDisplayed()
        composeRule.onNodeWithText("关闭").performClick()
    }

    @Test
    fun drawerOpensCampusChangeDialog() {
        openDrawerAndClick("切换校区")
        composeRule.onNodeWithText("将切换到忠山校区，并重启当前连接流程。").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
    }

    @Test
    fun drawerOpensAboutDialog() {
        openDrawerAndClick("关于")
        try {
            composeRule.onNodeWithText("1Connecter（医连接）", substring = true).assertIsDisplayed()
        } catch (failure: AssertionError) {
            captureAboutFailure()
            throw failure
        }
        composeRule.onNodeWithText("确定").performClick()
    }

    @Test
    fun logoutPromptAllowsDismissingThenRequestingRestart() {
        openDrawerAndClick("注销校园网")
        composeRule.onNodeWithText("解绑并注销").assertIsDisplayed()
        composeRule.onNodeWithText("解绑并注销").performClick()
        composeRule.onNodeWithText("是否立即重启软件？", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("暂不重启").performClick()
        assertEquals(1, restartDismissals)

        openDrawerAndClick("注销校园网")
        composeRule.onNodeWithText("解绑并注销").performClick()
        composeRule.onNodeWithText("立即重启").performClick()
        assertEquals(1, restartRequests)
    }

    @Test
    fun drawerRequestsCsvExport() {
        openDrawerAndClick("导出配置 (CSV)")
        assertEquals(1, csvExportRequests)
    }

    private fun captureAboutFailure() {
        runCatching {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val matches = composeRule.onAllNodesWithText("1Connecter（医连接）", substring = true)
                .fetchSemanticsNodes()
            val bounds = matches.joinToString { node ->
                "id=${node.id}, root=${node.boundsInRoot}, window=${node.boundsInWindow}"
            }
            val summary = "About text matches=${matches.size}; bounds=[$bounds]"
            val tree = composeRule.onAllNodes(isRoot(), useUnmergedTree = true).printToString()
            File(instrumentation.targetContext.cacheDir, "ui-about-failure.txt")
                .writeText("$summary\n$tree")
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                try {
                    File(instrumentation.targetContext.cacheDir, "ui-about-failure.png")
                        .outputStream().use { output ->
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
                        }
                } finally {
                    screenshot.recycle()
                }
            }
            Log.e("MainScreenTest", summary)
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "\nABOUT_DIAGNOSTIC: $summary\n")
            })
        }.onFailure { diagnosticFailure ->
            Log.e("MainScreenTest", "About failure diagnostics could not be saved", diagnosticFailure)
        }
    }

    private fun openDrawerAndClick(label: String) {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
    }
}
