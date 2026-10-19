package top.onering.easyconnecter.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.Carrier
import top.onering.easyconnecter.model.PendingRecovery
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ConfigRepository internal constructor(directory: File) : ConfigStore {
    constructor(context: Context) : this(context.filesDir)

    private val gson = Gson()
    private val indexFile = File(directory, "current_index.txt")
    private val campusFile = File(directory, "campus_config.txt")
    private val keepAliveFile = File(directory, "keep_alive_config.txt")
    private val loginPreferencesFile = File(directory, "login_preferences.json")
    private val pendingRecoveryFile = File(directory, "pending_recovery.json")
    private val mutex = Mutex()

    private val _currentIndex = MutableStateFlow(readInt(indexFile, 0))
    private val _campus = MutableStateFlow(Campus.fromStored(readText(campusFile)))
    private val _keepAlive = MutableStateFlow(readText(keepAliveFile)?.toBooleanStrictOrNull() ?: false)
    private var loginPreferences = readLoginPreferences()

    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()
    override val campus: StateFlow<Campus> = _campus.asStateFlow()
    override val keepAlive: StateFlow<Boolean> = _keepAlive.asStateFlow()

    override suspend fun setCurrentIndex(index: Int) = mutex.withLock {
        withContext(Dispatchers.IO) { indexFile.writeText(index.coerceAtLeast(0).toString()) }
        _currentIndex.value = index.coerceAtLeast(0)
    }

    override suspend fun setCampus(campus: Campus) = mutex.withLock {
        // Keep the legacy URL representation so older builds can still read this file.
        withContext(Dispatchers.IO) { campusFile.writeText(campus.loginUrl) }
        _campus.value = campus
    }

    override suspend fun setKeepAlive(enabled: Boolean) = mutex.withLock {
        withContext(Dispatchers.IO) { keepAliveFile.writeText(enabled.toString()) }
        _keepAlive.value = enabled
    }

    override suspend fun preferredCarrier(studentId: String): Carrier? = mutex.withLock {
        loginPreferences.preferredCarrierByStudentId[studentId]
            ?.let { name -> Carrier.entries.firstOrNull { it.name == name } }
    }

    override suspend fun recordSuccessfulLogin(studentId: String, carrier: Carrier) = mutex.withLock {
        loginPreferences = loginPreferences.copy(
            preferredCarrierByStudentId = loginPreferences.preferredCarrierByStudentId +
                (studentId to carrier.name),
            lastSuccessfulStudentId = studentId
        )
        writeLoginPreferences()
    }

    override suspend fun lastSuccessfulStudentId(): String? = mutex.withLock {
        loginPreferences.lastSuccessfulStudentId
    }

    override suspend fun removeAccountPreferences(studentId: String) = mutex.withLock {
        loginPreferences = loginPreferences.copy(
            preferredCarrierByStudentId = loginPreferences.preferredCarrierByStudentId - studentId,
            lastSuccessfulStudentId = loginPreferences.lastSuccessfulStudentId
                ?.takeUnless { it == studentId }
        )
        writeLoginPreferences()
    }

    override suspend fun clearLoginPreferences() = mutex.withLock {
        loginPreferences = PersistedLoginPreferences()
        writeLoginPreferences()
    }

    override suspend fun pendingRecovery(): PendingRecovery? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!pendingRecoveryFile.exists()) return@withContext null
            try {
                val saved = gson.fromJson(pendingRecoveryFile.readText(), PersistedRecovery::class.java)
                    ?: throw IOException("恢复记录为空")
                val campus = Campus.entries.firstOrNull { it.name == saved.campus }
                    ?: throw IOException("恢复记录校区无效")
                val carrier = Carrier.entries.firstOrNull { it.name == saved.carrier }
                    ?: throw IOException("恢复记录运营商无效")
                val studentId = saved.studentId?.takeIf { it.isNotBlank() }
                    ?: throw IOException("恢复记录账号为空")
                PendingRecovery(campus, studentId, carrier)
            } catch (error: Exception) {
                throw IOException("无法读取待恢复会话", error)
            }
        }
    }

    override suspend fun setPendingRecovery(recovery: PendingRecovery?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (recovery == null) {
                Files.deleteIfExists(pendingRecoveryFile.toPath())
            } else {
                val temporaryFile = File(pendingRecoveryFile.parentFile, "pending_recovery.json.tmp")
                try {
                    val json = gson.toJson(PersistedRecovery(
                        campus = recovery.campus.name,
                        studentId = recovery.studentId,
                        carrier = recovery.carrier.name
                    ))
                    FileOutputStream(temporaryFile).use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    Files.move(
                        temporaryFile.toPath(), pendingRecoveryFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                    )
                } finally {
                    if (temporaryFile.isFile) temporaryFile.delete()
                }
            }
            Unit
        }
    }

    private suspend fun writeLoginPreferences() = withContext(Dispatchers.IO) {
        loginPreferencesFile.writeText(gson.toJson(loginPreferences))
    }

    private fun readLoginPreferences(): PersistedLoginPreferences {
        if (!loginPreferencesFile.exists()) return PersistedLoginPreferences()
        return try {
            val persisted = gson.fromJson(loginPreferencesFile.readText(), PersistedLoginPreferences::class.java)
            PersistedLoginPreferences(
                version = 1,
                preferredCarrierByStudentId = persisted?.preferredCarrierByStudentId.orEmpty(),
                lastSuccessfulStudentId = persisted?.lastSuccessfulStudentId
            )
        } catch (_: Exception) {
            PersistedLoginPreferences()
        }
    }

    private fun readText(file: File): String? = try {
        file.takeIf { it.exists() }?.readText()?.trim()
    } catch (_: Exception) {
        null
    }

    private fun readInt(file: File, default: Int): Int = readText(file)?.toIntOrNull() ?: default

    private data class PersistedLoginPreferences(
        val version: Int = 1,
        val preferredCarrierByStudentId: Map<String, String> = emptyMap(),
        val lastSuccessfulStudentId: String? = null
    )

    private data class PersistedRecovery(
        val campus: String? = null,
        val studentId: String? = null,
        val carrier: String? = null
    )
}
