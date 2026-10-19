package top.onering.easyconnecter.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.ThemeMode

class UiPreferencesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun newInstallationPersistsUnfinishedOnboardingBeforeAccountsAreImported() = runTest {
        temporaryFolder.root.resolve("keep_alive_config.txt").writeText("true")
        temporaryFolder.root.resolve("auto_close_intro_seen.txt").writeText("true")
        val config = ConfigRepository(temporaryFolder.root)

        assertTrue(config.autoCloseEnabled.value)
        assertFalse(config.onboardingCompleted.value)
        assertEquals(ThemeMode.SYSTEM, config.themeMode.value)
        config.ensureOnboardingInitialized()
        config.setAutoClose(false)
        config.setThemeMode(ThemeMode.DARK)

        val reopened = ConfigRepository(temporaryFolder.root, hasExistingAccounts = true)
        reopened.ensureOnboardingInitialized()
        assertFalse(reopened.onboardingCompleted.value)
        assertEquals("false", temporaryFolder.root.resolve("onboarding_completed.txt").readText())
        assertTrue(reopened.keepAlive.value)
    }

    @Test
    fun explicitAutoCloseChoiceSurvivesRepositoryRecreation() = runTest {
        val config = ConfigRepository(temporaryFolder.root)
        config.setAutoClose(false)
        assertFalse(config.autoCloseEnabled.value)
        val reopened = ConfigRepository(temporaryFolder.root)
        assertFalse(reopened.autoCloseEnabled.value)

        reopened.setAutoClose(true)
        assertTrue(reopened.autoCloseEnabled.value)
        assertTrue(ConfigRepository(temporaryFolder.root).autoCloseEnabled.value)
    }

    @Test
    fun completingOnboardingSurvivesRecreationAndLaterRemovalOfAllAccounts() = runTest {
        val config = ConfigRepository(temporaryFolder.root)
        config.ensureOnboardingInitialized()
        config.setCampus(Campus.ZHONGSHAN)
        config.setAutoClose(false)
        config.markOnboardingCompleted()

        assertTrue(config.onboardingCompleted.value)
        val reopened = ConfigRepository(temporaryFolder.root, hasExistingAccounts = false)
        assertTrue(reopened.onboardingCompleted.value)
        assertFalse(reopened.autoCloseEnabled.value)
        assertEquals(Campus.ZHONGSHAN, reopened.campus.value)
    }

    @Test
    fun failedCompletionKeepsOnboardingPendingAndCanBeRetried() = runTest {
        val config = ConfigRepository(temporaryFolder.root)
        config.ensureOnboardingInitialized()
        val completionFile = temporaryFolder.root.resolve("onboarding_completed.txt")
        assertTrue(completionFile.delete())
        assertTrue(completionFile.mkdir())

        assertTrue(runCatching { config.markOnboardingCompleted() }.isFailure)
        assertFalse(config.onboardingCompleted.value)
        assertFalse(ConfigRepository(temporaryFolder.root, hasExistingAccounts = true).onboardingCompleted.value)

        assertTrue(completionFile.delete())
        config.markOnboardingCompleted()
        assertTrue(ConfigRepository(temporaryFolder.root).onboardingCompleted.value)
    }

    @Test
    fun existingAccountsMigrateToCompletedWithoutChangingTheirSettings() = runTest {
        val oldSettings = ConfigRepository(temporaryFolder.root)
        oldSettings.setCampus(Campus.ZHONGSHAN)
        oldSettings.setAutoClose(false)
        oldSettings.setKeepAlive(true)
        oldSettings.setThemeMode(ThemeMode.DARK)

        val migrated = ConfigRepository(temporaryFolder.root, hasExistingAccounts = true)
        assertTrue(migrated.onboardingCompleted.value)
        migrated.ensureOnboardingInitialized()

        val afterAccountRemoval = ConfigRepository(temporaryFolder.root, hasExistingAccounts = false)
        assertTrue(afterAccountRemoval.onboardingCompleted.value)
        assertEquals(Campus.ZHONGSHAN, afterAccountRemoval.campus.value)
        assertFalse(afterAccountRemoval.autoCloseEnabled.value)
        assertTrue(afterAccountRemoval.keepAlive.value)
        assertEquals(ThemeMode.DARK, afterAccountRemoval.themeMode.value)
    }

    @Test
    fun failedInitializationIsReportedAndCanBeRetriedBeforeStartingConnections() = runTest {
        val missingDirectory = temporaryFolder.root.resolve("not-created-yet")
        val migrated = ConfigRepository(missingDirectory, hasExistingAccounts = true)

        assertTrue(runCatching { migrated.ensureOnboardingInitialized() }.isFailure)
        assertFalse(missingDirectory.resolve("onboarding_completed.txt").exists())

        assertTrue(missingDirectory.mkdir())
        migrated.ensureOnboardingInitialized()
        assertTrue(ConfigRepository(missingDirectory, hasExistingAccounts = false).onboardingCompleted.value)
    }

    @Test
    fun allThemeChoicesSurviveRepositoryRecreation() = runTest {
        val config = ConfigRepository(temporaryFolder.root)
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)) {
            config.setThemeMode(mode)
            assertEquals(mode, config.themeMode.value)
            assertEquals(mode, ConfigRepository(temporaryFolder.root).themeMode.value)
        }
    }

    @Test
    fun unrecognizedSettingsNeverCompleteOnboardingImplicitly() {
        temporaryFolder.root.resolve("auto_close_config.txt").writeText("incomplete")
        temporaryFolder.root.resolve("onboarding_completed.txt").writeText("incomplete")
        temporaryFolder.root.resolve("theme_mode_config.txt").writeText("UNKNOWN")

        val config = ConfigRepository(temporaryFolder.root, hasExistingAccounts = true)
        assertTrue(config.autoCloseEnabled.value)
        assertFalse(config.onboardingCompleted.value)
        assertEquals(ThemeMode.SYSTEM, config.themeMode.value)
    }
}
