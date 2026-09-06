package top.onering.easyconnecter.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ExportedLog(
    val fileName: String,
    val displayPath: String
)

class SharedLogExporter(
    private val context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun export(lines: List<String>): Result<ExportedLog> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = logFileName(nowMillis())
            val content = lines.joinToString("\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportWithMediaStore(fileName, content)
            } else {
                exportToLegacyDownloads(fileName, content)
            }
            ExportedLog(fileName, "/sdcard/Download/$fileName")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportWithMediaStore(fileName: String, content: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在公共下载目录创建日志文件")
        try {
            val output = requireNotNull(resolver.openOutputStream(uri, "w")) {
                "无法打开日志文件"
            }
            output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun exportToLegacyDownloads(fileName: String, content: String) {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        check(directory.exists() || directory.mkdirs()) { "无法创建公共下载目录" }
        File(directory, fileName).writeText(content, Charsets.UTF_8)
    }
}

internal fun logFileName(
    epochMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault()
): String {
    val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
        this.timeZone = timeZone
    }
    return "connect_logs_${formatter.format(Date(epochMillis))}.txt"
}
