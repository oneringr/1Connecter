package top.onering.easyconnecter.data

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.Carrier
import top.onering.easyconnecter.model.PendingRecovery
import top.onering.easyconnecter.model.ThemeMode
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ConfigRepositoryDeviceTest {
    @Test
    fun presentationPreferencesKeepDefaultsAndSurviveRepositoryRecreation() = runBlocking {
        val applicationContext = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(
            applicationContext.cacheDir.toPath(), "presentation-preferences-test-"
        ).toFile()
        val isolatedContext = object : ContextWrapper(applicationContext) {
            override fun getFilesDir(): File = directory
        }
        try {
            val initialRepository = ConfigRepository(isolatedContext)
            assertTrue(initialRepository.autoCloseEnabled.value)
            assertFalse(initialRepository.onboardingCompleted.value)
            assertEquals(ThemeMode.SYSTEM, initialRepository.themeMode.value)

            initialRepository.ensureOnboardingInitialized()
            val afterImport = ConfigRepository(isolatedContext, hasExistingAccounts = true)
            assertFalse(afterImport.onboardingCompleted.value)
            afterImport.ensureOnboardingInitialized()
            assertFalse(afterImport.onboardingCompleted.value)

            initialRepository.setCampus(Campus.ZHONGSHAN)
            initialRepository.setAutoClose(false)
            initialRepository.setThemeMode(ThemeMode.DARK)
            initialRepository.markOnboardingCompleted()

            val recreatedRepository = ConfigRepository(isolatedContext)
            assertTrue(recreatedRepository.onboardingCompleted.value)
            assertFalse(recreatedRepository.autoCloseEnabled.value)
            assertEquals(Campus.ZHONGSHAN, recreatedRepository.campus.value)
            assertEquals(ThemeMode.DARK, recreatedRepository.themeMode.value)
        } finally {
            assertTrue("Test-created cache directory must be removed", directory.deleteRecursively())
        }
    }

    @Test
    fun pendingRecoverySurvivesRepositoryRecreationReplacementAndClear() = runBlocking {
        val applicationContext = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(
            applicationContext.cacheDir.toPath(), "recovery-repository-test-"
        ).toFile()
        val isolatedContext = object : ContextWrapper(applicationContext) {
            override fun getFilesDir(): File = directory
        }
        try {
            val first = PendingRecovery(Campus.CHENGBEI, "synthetic-account-A", Carrier.TELECOM)
            val second = PendingRecovery(Campus.ZHONGSHAN, "synthetic-account-B", Carrier.MOBILE, 2, setOf(Carrier.TELECOM))
            val initialRepository = ConfigRepository(isolatedContext)
            initialRepository.setKeepAlive(true)
            initialRepository.setPendingRecovery(first)

            val afterCreation = ConfigRepository(isolatedContext)
            assertEquals(first, afterCreation.pendingRecovery())
            assertTrue(afterCreation.keepAlive.value)
            afterCreation.setPendingRecovery(second)

            val afterReplacement = ConfigRepository(isolatedContext)
            assertEquals(second, afterReplacement.pendingRecovery())
            assertTrue(afterReplacement.keepAlive.value)
            afterReplacement.setPendingRecovery(null)

            val afterClear = ConfigRepository(isolatedContext)
            assertNull(afterClear.pendingRecovery())
            assertTrue(afterClear.keepAlive.value)
        } finally {
            assertTrue("Test-created cache directory must be removed", directory.deleteRecursively())
        }
    }
}
