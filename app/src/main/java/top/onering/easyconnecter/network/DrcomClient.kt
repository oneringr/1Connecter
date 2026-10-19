package top.onering.easyconnecter.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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

internal class HttpsConnectivityProbe(
    client: OkHttpClient = OkHttpClient(),
    private val urls: List<HttpUrl> = listOf(
        "https://www.baidu.com/".toHttpUrl(),
        "https://www.qq.com/".toHttpUrl()
    )
) : ConnectivityProbe {
    private val probeClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    init {
        require(urls.isNotEmpty() && urls.all { it.isHttps }) { "联网探测必须使用 HTTPS" }
    }

    override suspend fun ping(): Boolean {
        for (url in urls) {
            val reachable = try {
                probeClient.executeCancellable(Request.Builder().url(url).build()) { response ->
                    response.isSuccessful && response.handshake != null && response.request.url.isHttps
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                false
            }
            if (reachable) return true
        }
        return false
    }
}

// Keep cancellation attached until the response body has been consumed and closed.
// Cancelling only an enclosing withContext leaves a blocking OkHttp call running.
private suspend fun <T> OkHttpClient.executeCancellable(
    request: Request,
    readResponse: (Response) -> T
): T = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            continuation.resumeWith(Result.failure(error))
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use(readResponse)
                continuation.resumeWith(Result.success(result))
            } catch (error: Exception) {
                continuation.resumeWith(Result.failure(error))
            }
        }
    })
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
    private val connectivityProbe: ConnectivityProbe = HttpsConnectivityProbe()
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
            client.executeCancellable(request) { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    LoginOutcome.NetworkError("HTTP ${response.code}")
                } else {
                    parseLoginResponse(body, carrier)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
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
            val result = json.stringValue("result")
            if (result != "0" && result != "1") throw IOException("状态响应 result 未知")
            SessionStatus(
                loggedIn = result == "1",
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
        val result = json.stringValue("result")
        if (result == "1" || result == "ok") return LoginOutcome.Success(carrier)
        if (result != "0") return LoginOutcome.ProtocolError("登录响应 result 未知")

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
            require(start > 0 && end > start && end == trimmed.lastIndex &&
                JSONP_CALLBACK_REGEX.matches(trimmed.substring(0, start).trim())) { "不是有效的 JSONP" }
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

    private suspend fun executeJsonp(request: Request): Result<JsonObject> = try {
        client.executeCancellable(request) { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Result.failure(IOException("HTTP ${response.code}"))
            } else {
                runCatching { parseJsonp(body) }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun executeOperation(request: Request, operation: String): Result<Unit> = try {
        client.executeCancellable(request) { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Result.failure(IOException("$operation HTTP ${response.code}"))
            } else {
                val json = parseJsonp(body)
                val result = json.stringValue("result")
                if (result == "1" || result == "ok") {
                    Result.success(Unit)
                } else {
                    val message = json.stringValue("msg") ?: json.stringValue("msga") ?: "响应未确认成功"
                    Result.failure(IOException("${operation}失败: $message"))
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun JsonObject.stringValue(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun randomCallback(): String = "dr${Random.nextInt(1000, 10_000)}"
    private fun randomNonce(): String = Random.nextInt(1000, 20_000).toString()

    companion object {
        private const val MIN_LOGIN_INTERVAL_SECONDS = 3
        private val RATE_LIMIT_REGEX = Regex("error5\\s+waitsec\\s*<(\\d+)", RegexOption.IGNORE_CASE)
        private val JSONP_CALLBACK_REGEX = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
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
