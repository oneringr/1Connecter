package top.onering.easyconnecter.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.Carrier
import top.onering.easyconnecter.model.LoginOutcome
import top.onering.easyconnecter.model.SessionStatus
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class CampusEndpoints(
    val drcomBase: HttpUrl,
    val portalBase: HttpUrl
)

fun interface CampusEndpointProvider {
    fun endpoints(campus: Campus): CampusEndpoints
}

fun interface ConnectivityProbe {
    suspend fun ping(): Boolean
}

private class SystemPingConnectivityProbe(
    private val host: String = "baidu.com"
) : ConnectivityProbe {
    override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", host)
                .redirectErrorStream(true)
                .start()
            process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) {
            false
        } finally {
            runCatching { process?.inputStream?.close() }
            if (process?.isAlive == true) process.destroy()
        }
    }
}

interface NetworkGateway {
    suspend fun isInternetConnected(): Boolean
    suspend fun login(studentId: String, password: String, campus: Campus, carrier: Carrier): LoginOutcome
    suspend fun checkStatus(campus: Campus): Result<SessionStatus>
    suspend fun unbindMac(campus: Campus, account: String, mac: String, decimalIpv4: String): Result<Unit>
    suspend fun logout(campus: Campus): Result<Unit>
}

class DrcomClient(
    private val client: OkHttpClient = defaultClient(),
    private val endpointProvider: CampusEndpointProvider = CampusEndpointProvider { campus ->
        CampusEndpoints(
            drcomBase = "http://${campus.host}/".toHttpUrl(),
            portalBase = "http://${campus.host}:801/".toHttpUrl()
        )
    },
    private val connectivityProbe: ConnectivityProbe = SystemPingConnectivityProbe()
) : NetworkGateway {
    private val gson = Gson()

    override suspend fun isInternetConnected(): Boolean = connectivityProbe.ping()

    override suspend fun login(
        studentId: String,
        password: String,
        campus: Campus,
        carrier: Carrier
    ): LoginOutcome = withContext(Dispatchers.IO) {
        val endpoints = endpointProvider.endpoints(campus)
        val url = endpoints.drcomBase.newBuilder()
            .encodedPath("/drcom/login")
            .addQueryParameter("callback", randomCallback())
            .addQueryParameter("DDDDD", studentId)
            .addQueryParameter("upass", password)
            .addQueryParameter("0MKKey", "123456")
            .addQueryParameter("R1", "0")
            .addQueryParameter("R2", "")
            .addQueryParameter("R3", carrier.r3.toString())
            .addQueryParameter("R6", "0")
            .addQueryParameter("para", "00")
            .addQueryParameter("v6ip", "")
            .addQueryParameter("terminal_type", "1")
            .addQueryParameter("lang", "zh-cn")
            .addQueryParameter("jsVersion", "4.2")
            .addQueryParameter("v", randomNonce())
            .addQueryParameter("lang", "zh")
            .build()

        val request = portalRequest(url, campus)
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext LoginOutcome.NetworkError("HTTP ${response.code}")
                }
                parseLoginResponse(body, carrier)
            }
        } catch (error: IOException) {
            LoginOutcome.NetworkError("网络请求异常: ${error.message ?: "未知错误"}")
        } catch (error: Exception) {
            LoginOutcome.ProtocolError("登录响应处理失败: ${error.message ?: "未知错误"}")
        }
    }

    override suspend fun checkStatus(campus: Campus): Result<SessionStatus> = withContext(Dispatchers.IO) {
        val url = endpointProvider.endpoints(campus).drcomBase.newBuilder()
            .encodedPath("/drcom/chkstatus")
            .addQueryParameter("callback", randomCallback())
            .addQueryParameter("jsVersion", "4.X")
            .addQueryParameter("v", randomNonce())
            .addQueryParameter("lang", "zh")
            .build()
        executeJsonp(portalRequest(url, campus)).mapCatching { json ->
            val result = json.intValue("result")
                ?: throw IOException("状态响应缺少 result")
            SessionStatus(
                loggedIn = result == 1,
                account = json.stringValue("uid"),
                mac = json.stringValue("ss4"),
                ipv4 = json.stringValue("ss5") ?: json.stringValue("v46ip")
            )
        }
    }

    override suspend fun unbindMac(
        campus: Campus,
        account: String,
        mac: String,
        decimalIpv4: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val url = endpointProvider.endpoints(campus).portalBase.newBuilder()
            .encodedPath("/eportal/portal/mac/unbind")
            .addQueryParameter("callback", randomCallback())
            .addQueryParameter("user_account", account)
            .addQueryParameter("wlan_user_mac", mac)
            .addQueryParameter("wlan_user_ip", decimalIpv4)
            .addQueryParameter("jsVersion", "4.2")
            .addQueryParameter("v", randomNonce())
            .addQueryParameter("lang", "zh")
            .build()
        executeOperation(portalRequest(url, campus), "MAC 解绑")
    }

    override suspend fun logout(campus: Campus): Result<Unit> = withContext(Dispatchers.IO) {
        val url = endpointProvider.endpoints(campus).drcomBase.newBuilder()
            .encodedPath("/drcom/logout")
            .addQueryParameter("callback", randomCallback())
            .addQueryParameter("jsVersion", "4.2")
            .addQueryParameter("v", randomNonce())
            .addQueryParameter("lang", "zh")
            .build()
        executeOperation(portalRequest(url, campus), "校园网注销")
    }

    internal fun parseLoginResponse(body: String, carrier: Carrier): LoginOutcome {
        val json = try {
            parseJsonp(body)
        } catch (error: Exception) {
            return LoginOutcome.ProtocolError("无法解析登录响应: ${error.message ?: "未知错误"}")
        }
        if (json.intValue("result") == 1) return LoginOutcome.Success(carrier)

        val serverMessage = json.stringValue("msga")
            ?: json.stringValue("msg")
            ?: "服务端未提供失败原因"
        val normalized = serverMessage.trim().lowercase()
        return when {
            normalized == "userid error" || normalized == "userid error2" ->
                LoginOutcome.InvalidCredentials("校园网账号或密码错误")
            normalized == "bind userid error" -> LoginOutcome.OperatorNotBound("未绑定${carrier.displayName}运营商账号")
            normalized == "oppp error" ->
                LoginOutcome.InvalidCredentials("绑定的${carrier.displayName}运营商账号密码错误")
            normalized == "clientip online" -> LoginOutcome.ClientIpOnline(serverMessage)
            RATE_LIMIT_REGEX.matches(normalized) -> {
                val waitSeconds = RATE_LIMIT_REGEX.matchEntire(normalized)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(MIN_LOGIN_INTERVAL_SECONDS)
                    ?: MIN_LOGIN_INTERVAL_SECONDS
                LoginOutcome.RateLimited(waitSeconds, serverMessage)
            }
            else -> LoginOutcome.ProtocolError("登录失败: $serverMessage")
        }
    }

    internal fun parseJsonp(body: String): JsonObject {
        val trimmed = body.trim().removeSuffix(";").trim()
        val jsonText = if (trimmed.startsWith("{")) {
            trimmed
        } else {
            val start = trimmed.indexOf('(')
            val end = trimmed.lastIndexOf(')')
            require(start >= 0 && end > start) { "不是有效的 JSONP" }
            trimmed.substring(start + 1, end).trim()
        }
        return gson.fromJson(jsonText, JsonObject::class.java)
            ?: throw IllegalArgumentException("JSON 内容为空")
    }

    private fun portalRequest(url: HttpUrl, campus: Campus): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Referer", "http://${campus.host}/a79.htm")
        .header("Accept", "*/*")
        .build()

    private fun executeJsonp(request: Request): Result<JsonObject> = try {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Result.failure(IOException("HTTP ${response.code}"))
            } else {
                runCatching { parseJsonp(body) }
            }
        }
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun executeOperation(request: Request, operation: String): Result<Unit> = try {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Result.failure(IOException("$operation HTTP ${response.code}"))
            } else if (body.isBlank()) {
                Result.success(Unit)
            } else {
                val json = runCatching { parseJsonp(body) }.getOrNull()
                val result = json?.intValue("result")
                if (result == null || result == 1) {
                    Result.success(Unit)
                } else {
                    val message = json?.stringValue("msg") ?: json?.stringValue("msga") ?: "服务端拒绝请求"
                    Result.failure(IOException("${operation}失败: $message"))
                }
            }
        }
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun JsonObject.stringValue(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.intValue(name: String): Int? =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.toIntOrNull()

    private fun randomCallback(): String = "dr${Random.nextInt(1000, 10_000)}"
    private fun randomNonce(): String = Random.nextInt(1000, 20_000).toString()

    companion object {
        private const val MIN_LOGIN_INTERVAL_SECONDS = 3
        private val RATE_LIMIT_REGEX = Regex("error5\\s+waitsec\\s*<(\\d+)", RegexOption.IGNORE_CASE)
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
