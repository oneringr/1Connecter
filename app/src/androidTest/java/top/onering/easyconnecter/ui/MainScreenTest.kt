package top.onering.easyconnecter.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.onering.easyconnecter.MainUiState
import top.onering.easyconnecter.model.Account

class MainScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun drawerActionsOpenAccountCampusAndAboutDialogs() {
        var csvExportRequests = 0
        var restartRequests = 0
        var restartDismissals = 0
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

        openDrawerAndClick("管理账号")
        composeRule.onNodeWithText("账号管理 (1)").assertIsDisplayed()
        composeRule.onNodeWithText("关闭").performClick()

        openDrawerAndClick("切换校区")
        composeRule.onNodeWithText("将切换到忠山校区，并重启当前连接流程。").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()

        openDrawerAndClick("关于")
        composeRule.onNodeWithText("1Connecter（医连接）", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("确定").performClick()

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

        openDrawerAndClick("导出配置 (CSV)")
        assertEquals(1, csvExportRequests)
    }

    private fun openDrawerAndClick(label: String) {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }
}
