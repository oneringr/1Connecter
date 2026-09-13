package top.onering.easyconnecter.data

import kotlinx.coroutines.flow.StateFlow
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.Carrier

interface AccountStore {
    val accounts: StateFlow<List<Account>>
    suspend fun replace(accounts: List<Account>)
    suspend fun removeAt(index: Int): Account?
}

interface ConfigStore {
    val currentIndex: StateFlow<Int>
    val campus: StateFlow<Campus>
    // Remember the user's switch choice, independently of whether the service can run now.
    val keepAlive: StateFlow<Boolean>
    suspend fun setCurrentIndex(index: Int)
    suspend fun setCampus(campus: Campus)
    suspend fun setKeepAlive(enabled: Boolean)
    suspend fun preferredCarrier(studentId: String): Carrier?
    suspend fun recordSuccessfulLogin(studentId: String, carrier: Carrier)
    suspend fun lastSuccessfulStudentId(): String?
    suspend fun removeAccountPreferences(studentId: String)
    suspend fun clearLoginPreferences()
}

fun interface EventLogger {
    fun add(message: String)
}
