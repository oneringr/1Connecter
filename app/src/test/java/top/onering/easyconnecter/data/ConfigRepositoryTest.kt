package top.onering.easyconnecter.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.Carrier
import top.onering.easyconnecter.model.PendingRecovery

class ConfigRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun serviceModeDefaultsToOff() {
        assertFalse(ConfigRepository(temporaryFolder.root).keepAlive.value)
    }

    @Test
    fun remembersEnabledServiceModeAfterRepositoryRecreation() = runTest {
        val config = ConfigRepository(temporaryFolder.root)

        config.setKeepAlive(true)

        assertTrue(config.keepAlive.value)
        assertEquals("true", temporaryFolder.root.resolve("keep_alive_config.txt").readText())
        assertTrue(ConfigRepository(temporaryFolder.root).keepAlive.value)
    }

    @Test
    fun remembersExplicitlyDisabledServiceModeAfterRepositoryRecreation() = runTest {
        ConfigRepository(temporaryFolder.root).setKeepAlive(true)
        val recreated = ConfigRepository(temporaryFolder.root)

        recreated.setKeepAlive(false)

        assertFalse(recreated.keepAlive.value)
        assertFalse(ConfigRepository(temporaryFolder.root).keepAlive.value)
    }

    @Test
    fun atomicallyReplacesAndClearsRecoveryWithoutChangingLoginPreferences() = runTest {
        val config = ConfigRepository(temporaryFolder.root)
        config.recordSuccessfulLogin("one", Carrier.MOBILE)
        val preferencesBefore = temporaryFolder.root.resolve("login_preferences.json").readText()
        val first = PendingRecovery(Campus.CHENGBEI, "one", Carrier.MOBILE)
        val replacement = PendingRecovery(Campus.ZHONGSHAN, "two", Carrier.TELECOM)

        config.setPendingRecovery(first)
        assertEquals(first, ConfigRepository(temporaryFolder.root).pendingRecovery())
        config.setPendingRecovery(replacement)
        assertEquals(replacement, ConfigRepository(temporaryFolder.root).pendingRecovery())
        assertFalse(temporaryFolder.root.resolve("pending_recovery.json.tmp").exists())
        config.setPendingRecovery(null)

        assertNull(ConfigRepository(temporaryFolder.root).pendingRecovery())
        assertEquals(preferencesBefore, temporaryFolder.root.resolve("login_preferences.json").readText())
    }

    @Test
    fun interruptedOrFailedReplacementRetainsTheLastCompleteRecovery() = runTest {
        val config = ConfigRepository(temporaryFolder.root)
        val first = PendingRecovery(Campus.CHENGBEI, "one", Carrier.MOBILE)
        config.setPendingRecovery(first)
        val temporary = temporaryFolder.root.resolve("pending_recovery.json.tmp")
        temporary.writeText("{\"campus\":")
        assertEquals(first, ConfigRepository(temporaryFolder.root).pendingRecovery())
        assertTrue(temporary.delete())
        assertTrue(temporary.mkdir())

        val failed = runCatching {
            config.setPendingRecovery(PendingRecovery(Campus.CHENGBEI, "two", Carrier.TELECOM))
        }

        assertTrue(failed.isFailure)
        assertEquals(first, ConfigRepository(temporaryFolder.root).pendingRecovery())
    }
}
