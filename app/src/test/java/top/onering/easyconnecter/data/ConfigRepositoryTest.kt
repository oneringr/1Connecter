package top.onering.easyconnecter.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}
