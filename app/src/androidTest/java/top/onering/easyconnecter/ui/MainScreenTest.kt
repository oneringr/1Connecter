package top.onering.easyconnecter.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import top.onering.easyconnecter.MainUiState
import top.onering.easyconnecter.data.ReleaseCheckResult
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.ui.theme._1ConnecterTheme
import java.io.File

class MainScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var csvExportRequests = 0
    private var restartRequests = 0
    private var restartDismissals = 0
    private var importRequests = 0
    private var campusChangeRequests = 0
    private val onboardingRequests = mutableListOf<Pair<Campus, Boolean>>()
    private var projectRequests = 0
    private var updateRequests = 0
    private val openedReleases = mutableListOf<String>()
    private val autoCloseRequests = mutableListOf<Boolean>()
    private val deletedAccountIndices = mutableListOf<Int>()
    private var state by mutableStateOf(MainUiState())

    @Before
    fun renderScreen() {
        csvExportRequests = 0
        restartRequests = 0
        restartDismissals = 0
        importRequests = 0
        campusChangeRequests = 0
        onboardingRequests.clear()
        projectRequests = 0
        updateRequests = 0
        openedReleases.clear()
        autoCloseRequests.clear()
        deletedAccountIndices.clear()
        state = MainUiState(
            accounts = listOf(
                Account("synthetic-account-A", "synthetic-password", "测试账号", "", "", ""),
                Account("synthetic-student-002", "synthetic-password", "Synthetic Beta", "", "", ""),
                Account("synthetic-student-003", "synthetic-password", "林同学", "", "", ""),
            )
        )
        composeRule.setContent {
            _1ConnecterTheme(themeMode = state.themeMode) {
                MainScreen(
                    state = state,
                    onSelectCampus = { state = state.copy(campus = it) },
                    onImportCsv = { importRequests++ },
                    onReimportCsv = {},
                    onExportCsv = { csvExportRequests++ },
                    onToggleKeepAlive = { state = state.copy(keepAlive = it) },
                    onToggleAutoScroll = { state = state.copy(autoScrollLogs = it) },
                    onShowAccounts = { state = state.copy(showAccountManager = true) },
                    onDismissAccounts = { state = state.copy(showAccountManager = false) },
                    onDeleteAccount = { deletedAccountIndices += it },
                    onShowCampusDialog = { state = state.copy(showCampusDialog = true) },
                    onDismissCampusDialog = { state = state.copy(showCampusDialog = false) },
                    onConfirmCampusChange = {
                        campusChangeRequests++
                        state = state.copy(campus = Campus.ZHONGSHAN, showCampusDialog = false)
                    },
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
                    onExportLogs = {},
                    onSetAutoClose = {
                        autoCloseRequests += it
                        state = state.copy(autoCloseEnabled = it)
                    },
                    onCompleteOnboarding = { campus, autoClose ->
                        onboardingRequests += campus to autoClose
                        state = state.copy(showOnboarding = false, campus = campus, autoCloseEnabled = autoClose)
                    },
                    onOpenProject = { projectRequests++ },
                    onCheckForUpdate = { updateRequests++ },
                    onOpenRelease = { openedReleases += it },
                    onSetThemeMode = { state = state.copy(themeMode = it) },
                    onUserInteraction = {},
                )
            }
        }
    }

    @Test
    fun accountsPageShowsAccountAndDistinguishesCandidateFromOnlineIdentity() {
        openPage("accounts")
        listOf("测试账号", "Synthetic Beta", "林同学").forEach { name ->
            composeRule.onNodeWithText(name).assertDoesNotExist()
        }
        composeRule.onNodeWithTag("account_delete_0").assertDoesNotExist()
        composeRule.onNodeWithTag("account_search").assertDoesNotExist()
        clickAction("accounts", "accounts_expand")
        composeRule.onNodeWithTag("accounts_list")
            .performScrollToNode(hasTestTag("account_delete_0"))
        composeRule.onNodeWithText("测试账号").assertIsDisplayed()
        composeRule.onNodeWithText("下次尝试").assertIsDisplayed()
        composeRule.onNodeWithText("在线账号：测试账号").assertDoesNotExist()
    }

    @Test
    fun settingsCampusChangeRequiresConfirmation() {
        openPage("settings")
        clickAction("settings", "change_campus")
        composeRule.onNodeWithText("将切换到忠山校区，并重启当前连接流程。").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
        assertEquals(0, campusChangeRequests)

        clickAction("settings", "change_campus")
        composeRule.onNodeWithText("切换").performClick()
        assertEquals(1, campusChangeRequests)
    }

    @Test
    fun settingsOpensAboutDialog() {
        openPage("settings")
        clickAction("settings", "about")
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
        openPage("settings")
        clickAction("settings", "logout")
        composeRule.onNodeWithText("解绑并注销").assertIsDisplayed()
        composeRule.onNodeWithText("解绑并注销").performClick()
        composeRule.onNodeWithText("是否立即重启软件？", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("暂不重启").performClick()
        assertEquals(1, restartDismissals)

        clickAction("settings", "logout")
        composeRule.onNodeWithText("解绑并注销").performClick()
        composeRule.onNodeWithText("立即重启").performClick()
        assertEquals(1, restartRequests)
    }

    @Test
    fun accountsPageRequestsCsvExport() {
        openPage("accounts")
        clickAction("accounts", "export_csv")
        assertEquals(1, csvExportRequests)
    }

    @Test
    fun accountSearchMatchesNamesAndStudentIdsAndCanRecoverFromNoMatches() {
        openPage("accounts")
        clickAction("accounts", "accounts_expand")

        searchAccounts("  sYnThEtIc BeTa  ")
        assertAccountShown(1, "Synthetic Beta")
        composeRule.onNodeWithTag("account_delete_0").assertDoesNotExist()
        composeRule.onNodeWithTag("account_delete_2").assertDoesNotExist()

        searchAccounts("  SYnThEtIc-STUDENT-003  ")
        assertAccountShown(2, "林同学")
        composeRule.onNodeWithTag("account_delete_1").assertDoesNotExist()

        searchAccounts("no-synthetic-account-matches")
        composeRule.onNodeWithTag("accounts_list")
            .performScrollToNode(hasText("没有匹配的账号"))
        composeRule.onNodeWithText("没有匹配的账号").assertIsDisplayed()
        composeRule.onNodeWithTag("account_delete_2").assertDoesNotExist()
        clickAction("accounts", "clear_account_search")
        composeRule.onNodeWithText("没有匹配的账号").assertDoesNotExist()
        assertAccountShown(0, "测试账号")
        assertAccountShown(1, "Synthetic Beta")
        assertAccountShown(2, "林同学")
    }

    @Test
    fun deletingFilteredAccountReportsItsOriginalIndex() {
        openPage("accounts")
        clickAction("accounts", "accounts_expand")
        searchAccounts("synthetic-student-003")
        assertAccountShown(2, "林同学")
        clickAction("accounts", "account_delete_2")
        assertEquals(listOf(2), deletedAccountIndices)
    }

    @Test
    fun accountExpansionAndSearchSurviveSwitchingTabs() {
        openPage("accounts")
        clickAction("accounts", "accounts_expand")
        searchAccounts("Beta")
        assertAccountShown(1, "Synthetic Beta")

        openPage("logs")
        openPage("accounts")
        composeRule.onNodeWithTag("accounts_list")
            .performScrollToNode(hasTestTag("account_search"))
        composeRule.onNodeWithTag("account_search").assertTextContains("Beta")
        assertAccountShown(1, "Synthetic Beta")
        composeRule.onNodeWithTag("account_delete_0").assertDoesNotExist()

        clickAction("accounts", "accounts_expand")
        openPage("settings")
        openPage("accounts")
        composeRule.onNodeWithTag("account_search").assertDoesNotExist()
        composeRule.onNodeWithTag("account_delete_1").assertDoesNotExist()
        clickAction("accounts", "accounts_expand")
        composeRule.onNodeWithTag("accounts_list")
            .performScrollToNode(hasTestTag("account_search"))
        composeRule.onNodeWithTag("account_search").assertTextContains("Beta")
    }

    @Test
    fun rapidNavigationEndsOnTheLastSelectedPage() {
        val positions = listOf("home", "accounts", "logs", "settings").associateWith { page ->
            composeRule.onNodeWithTag("nav_$page").fetchSemanticsNode().boundsInRoot.center
        }
        // Queue real touch clicks without waiting for each page transition to finish.
        composeRule.onRoot().performTouchInput {
            click(positions.getValue("accounts"))
            click(positions.getValue("logs"))
            click(positions.getValue("home"))
            click(positions.getValue("settings"))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_settings").assertIsSelected()
        composeRule.onNodeWithTag("settings_list").assertIsDisplayed()
        listOf("home", "accounts", "logs").forEach { page ->
            composeRule.onNodeWithTag("nav_$page").assertIsNotSelected()
            composeRule.onNodeWithTag("${page}_list").assertDoesNotExist()
        }
    }

    @Test
    fun onboardingRequiresCsvBeforeOptionsAndOnlyAppliesChoicesOnFinish() {
        val imported = state.accounts
        composeRule.runOnIdle { state = state.copy(showOnboarding = true, accounts = emptyList()) }
        composeRule.onNodeWithTag("nav_home").assertDoesNotExist()
        composeRule.onNodeWithTag("onboarding_next").assertIsNotEnabled()
        composeRule.onNodeWithTag("onboarding_import").performScrollTo().performClick()
        assertEquals(1, importRequests)
        // Cancellation or failure leaves accounts empty and cannot bypass the first step.
        composeRule.onNodeWithTag("onboarding_next").assertIsNotEnabled()
        composeRule.runOnIdle { state = state.copy(accounts = imported) }
        composeRule.onNodeWithTag("onboarding_next").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("onboarding_auto_close").performScrollTo().assertIsOn().performClick()
        composeRule.onNodeWithTag("onboarding_campus_zhongshan").performScrollTo().performClick()
        assertEquals(emptyList<Pair<Campus, Boolean>>(), onboardingRequests)
        composeRule.onNodeWithTag("onboarding_finish").performClick()
        assertEquals(listOf(Campus.ZHONGSHAN to false), onboardingRequests)
        composeRule.onNodeWithTag("home_list").assertIsDisplayed()
        composeRule.onNodeWithText("知道了").assertDoesNotExist()
    }

    @Test
    fun onboardingWithImportedAccountsStartsAtOptionsAndPreservesDraftWhenGoingBack() {
        composeRule.runOnIdle { state = state.copy(showOnboarding = true) }
        composeRule.onNodeWithTag("onboarding_auto_close").performScrollTo().assertIsOn().performClick()
        composeRule.onNodeWithTag("onboarding_previous").performClick()
        composeRule.onNodeWithTag("onboarding_next").performClick()
        composeRule.onNodeWithTag("onboarding_auto_close").performScrollTo().assertIsOff()
        composeRule.runOnIdle { state = state.copy(isBusy = true) }
        composeRule.onNodeWithTag("onboarding_finish").assertIsNotEnabled()
        composeRule.runOnIdle { state = state.copy(isBusy = false) }
        composeRule.onNodeWithTag("onboarding_finish").performClick()
        assertEquals(listOf(Campus.CHENGBEI to false), onboardingRequests)
        composeRule.onNodeWithTag("home_list").assertIsDisplayed()
    }

    @Test
    fun aboutProjectAndNewReleaseOpenTheirDestinations() {
        openPage("settings")
        clickAction("settings", "about")
        composeRule.onNodeWithTag("project_link").performScrollTo().performClick()
        assertEquals(1, projectRequests)
        composeRule.onNodeWithTag("check_update").performScrollTo().performClick()
        assertEquals(1, updateRequests)
        val url = "https://github.com/oneringr/1Connecter/releases/tag/1.8"
        composeRule.runOnIdle { state = state.copy(releaseCheckResult = ReleaseCheckResult.Available("1.8", url)) }
        composeRule.onNodeWithText("有新版本，点击跳转 Release").assertIsDisplayed()
        composeRule.onNodeWithTag("check_update").performClick()
        assertEquals(listOf(url), openedReleases)
        assertEquals(1, updateRequests)
    }

    @Test
    fun aboutUpdateCheckShowsProgressAndAllowsRetryAfterFailure() {
        composeRule.runOnIdle { state = state.copy(showAboutDialog = true, isCheckingUpdate = true) }
        composeRule.onNodeWithTag("check_update").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("正在检查新版本…").assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(isCheckingUpdate = false, releaseCheckResult = ReleaseCheckResult.Failure("网络连接失败"))
        }
        composeRule.onNodeWithText("网络连接失败 点击重试").assertIsDisplayed()
        composeRule.onNodeWithTag("check_update").assertIsEnabled().performClick()
        assertEquals(1, updateRequests)
        composeRule.runOnIdle { state = state.copy(releaseCheckResult = ReleaseCheckResult.UpToDate) }
        composeRule.onNodeWithText("当前已是最新版本，点击重新检查").assertIsDisplayed()
    }

    @Test
    fun busyStatePreventsRepeatedConnectionAndAccountChangesWhileTabsRemainUsable() {
        composeRule.runOnIdle { state = state.copy(isBusy = true) }
        openPage("accounts")
        assertActionDisabled("accounts", "reimport_csv")
        clickAction("accounts", "accounts_expand")
        assertActionDisabled("accounts", "account_delete_0")
        openPage("settings")
        assertActionDisabled("settings", "keep_alive_switch")
        assertActionDisabled("settings", "change_campus")
        assertActionDisabled("settings", "logout")
        openPage("logs")
        composeRule.onNodeWithText("还没有运行日志").assertIsDisplayed()
    }

    @Test
    fun settingsExposeSwitchNamesAndCurrentThemeToAccessibility() {
        openPage("settings")
        composeRule.onNodeWithTag("keep_alive_switch")
            .assertContentDescriptionEquals("服务模式").assertIsOff().performClick().assertIsOn()
        composeRule.onNodeWithTag("auto_close_switch")
            .assertContentDescriptionEquals("连接成功后自动关闭").assertIsOn()
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("theme_system"))
        composeRule.onNodeWithTag("theme_system").assertIsSelected()
        clickAction("settings", "theme_light")
        composeRule.onNodeWithTag("theme_light").assertIsSelected()
        composeRule.onNodeWithTag("theme_system").assertIsNotSelected()
    }

    @Test
    fun emptyAccountHomeGuidesUserToCampusSelectionAndCsvImport() {
        composeRule.runOnIdle { state = state.copy(accounts = emptyList()) }
        composeRule.onNodeWithText("从导入账号开始").assertIsDisplayed()
        clickAction("home", "campus_zhongshan")
        composeRule.runOnIdle { assertEquals(Campus.ZHONGSHAN, state.campus) }
        composeRule.onNodeWithTag("campus_zhongshan").assertIsSelected()
        composeRule.onNodeWithTag("campus_chengbei").assertIsNotSelected()
        clickAction("home", "import_csv")
        assertEquals(1, importRequests)
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

    private fun openPage(page: String) {
        composeRule.onNodeWithTag("nav_$page").assertIsEnabled().performClick()
        composeRule.waitForIdle()
    }

    private fun clickAction(page: String, tag: String) {
        composeRule.onNodeWithTag("${page}_list").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun assertActionDisabled(page: String, tag: String) {
        composeRule.onNodeWithTag("${page}_list").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).assertIsDisplayed().assertIsNotEnabled()
    }

    private fun searchAccounts(query: String) {
        composeRule.onNodeWithTag("accounts_list")
            .performScrollToNode(hasTestTag("account_search"))
        composeRule.onNodeWithTag("account_search").performTextReplacement(query)
        composeRule.onNodeWithTag("account_search").performImeAction()
    }

    private fun assertAccountShown(index: Int, name: String) {
        composeRule.onNodeWithTag("accounts_list")
            .performScrollToNode(hasTestTag("account_delete_$index"))
        composeRule.onNodeWithText(name).assertIsDisplayed()
        composeRule.onNodeWithTag("account_delete_$index").assertIsDisplayed()
    }
}
