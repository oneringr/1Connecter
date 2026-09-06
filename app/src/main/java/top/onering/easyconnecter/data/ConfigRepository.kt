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
import java.io.File

class ConfigRepository(context: Context) : ConfigStore {
    private val gson = Gson()
    private val indexFile = File(context.filesDir, "current_index.txt")
    private val campusFile = File(context.filesDir, "campus_config.txt")
    private val keepAliveFile = File(context.filesDir, "keep_alive_config.txt")
    private val loginPreferencesFile = File(context.filesDir, "login_preferences.json")
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
}
