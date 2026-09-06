package top.onering.easyconnecter.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class SharedLogExporterTest {
    @Test
    fun createsTimestampedLogFileName() {
        assertEquals(
            "connect_logs_20240102_030405_678.txt",
            logFileName(1_704_164_645_678L, TimeZone.getTimeZone("UTC"))
        )
    }
}
