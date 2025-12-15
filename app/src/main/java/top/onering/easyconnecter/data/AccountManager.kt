package top.onering.easyconnecter.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import top.onering.easyconnecter.model.Account
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class AccountManager(private val context: Context) {
    private val gson = Gson()
    private val accountsFile = File(context.filesDir, "accounts.json")
    private val indexFile = File(context.filesDir, "current_index.txt")
    private val campusFile = File(context.filesDir, "campus_config.txt")
    private val keepAliveFile = File(context.filesDir, "keep_alive_config.txt")

    fun hasAccounts(): Boolean {
        return accountsFile.exists() && accountsFile.length() > 0
    }

    fun saveAccounts(accounts: List<Account>) {
        accountsFile.writeText(gson.toJson(accounts))
    }

    fun loadAccounts(): List<Account> {
        if (!hasAccounts()) return emptyList()
        val type = object : TypeToken<List<Account>>() {}.type
        return try {
            gson.fromJson(accountsFile.readText(), type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCurrentIndex(index: Int) {
        indexFile.writeText(index.toString())
    }

    fun loadCurrentIndex(): Int {
        if (!indexFile.exists()) return 0
        return try {
            indexFile.readText().trim().toInt()
        } catch (e: Exception) {
            0
        }
    }

    fun saveCampusUrl(url: String) {
        campusFile.writeText(url)
    }

    fun loadCampusUrl(): String? {
        if (!campusFile.exists()) return null
        return try {
            campusFile.readText().trim()
        } catch (e: Exception) {
            null
        }
    }

    fun saveKeepAlive(enabled: Boolean) {
        keepAliveFile.writeText(enabled.toString())
    }

    fun loadKeepAlive(): Boolean {
        if (!keepAliveFile.exists()) return false
        return try {
            keepAliveFile.readText().trim().toBoolean()
        } catch (e: Exception) {
            false
        }
    }

    fun parseCsv(inputStream: java.io.InputStream): List<Account> {
        val accounts = mutableListOf<Account>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            val lines = reader.readLines()

            var headerMap = mutableMapOf<String, Int>()
            var isHeaderParsed = false

            for (line in lines) {
                if (line.isBlank()) continue

                var currentLine = line
                // Remove BOM if present
                if (currentLine.startsWith("\uFEFF")) {
                    currentLine = currentLine.substring(1)
                }

                val parts = currentLine.split(",")

                if (!isHeaderParsed) {
                    val tempMap = mutableMapOf<String, Int>()
                    parts.forEachIndexed { index, header ->
                        tempMap[header.trim()] = index
                    }
                    // Ensure required columns exist
                    if (tempMap.containsKey("学号") && tempMap.containsKey("身份证号")) {
                        headerMap = tempMap
                        isHeaderParsed = true
                    }
                    continue
                }

                fun getVal(key: String): String {
                    val idx = headerMap[key] ?: return ""
                    if (idx < parts.size) return parts[idx].trim()
                    return ""
                }

                val studentId = getVal("学号").replace(".0", "")
                val idCard = getVal("身份证号")
                val name = getVal("姓名")
                val className = getVal("班级")
                val phone = getVal("联系电话")

                if (studentId.isNotBlank() && idCard.isNotBlank()) {
                    val password = if (idCard.length >= 6) {
                        idCard.takeLast(6).replace("X", "0", ignoreCase = true)
                    } else {
                        "123456"
                    }

                    accounts.add(Account(studentId, password, name, className, phone, idCard))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return accounts
    }
}