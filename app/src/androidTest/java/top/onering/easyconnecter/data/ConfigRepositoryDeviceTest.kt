package top.onering.easyconnecter.data

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.Carrier
import top.onering.easyconnecter.model.PendingRecovery
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ConfigRepositoryDeviceTest {
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
            val second = PendingRecovery(Campus.ZHONGSHAN, "synthetic-account-B", Carrier.MOBILE)
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
