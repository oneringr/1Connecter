package top.onering.easyconnecter.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.model.CsvImportResult
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class AccountRepository(context: Context) : AccountStore {
    private val gson = Gson()
    private val accountsFile = File(context.filesDir, "accounts.json")
    private val parser = CsvAccountParser()
    private val mutex = Mutex()
    private val _accounts = MutableStateFlow(readAccounts())

    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    suspend fun importCsv(inputStream: InputStream): CsvImportResult = withContext(Dispatchers.IO) {
        parser.parse(inputStream)
    }

    suspend fun exportCsv(outputStream: OutputStream): Int = withContext(Dispatchers.IO) {
        val snapshot = accounts.value
        parser.write(snapshot, outputStream)
        snapshot.size
    }

    override suspend fun replace(accounts: List<Account>) = mutex.withLock {
        withContext(Dispatchers.IO) { accountsFile.writeText(gson.toJson(accounts)) }
        _accounts.value = accounts
    }

    override suspend fun removeAt(index: Int): Account? = mutex.withLock {
        val current = _accounts.value
        if (index !in current.indices) return@withLock null
        val removed = current[index]
        val updated = current.toMutableList().also { it.removeAt(index) }
        withContext(Dispatchers.IO) { accountsFile.writeText(gson.toJson(updated)) }
        _accounts.value = updated
        removed
    }

    private fun readAccounts(): List<Account> {
        if (!accountsFile.exists() || accountsFile.length() == 0L) return emptyList()
        val type = object : TypeToken<List<Account>>() {}.type
        return try {
            gson.fromJson<List<Account>>(accountsFile.readText(), type)
                ?.filter { it.studentId.isNotBlank() }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
