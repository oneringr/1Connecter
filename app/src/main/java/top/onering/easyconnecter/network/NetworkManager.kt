package top.onering.easyconnecter.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class NetworkManager {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 尝试访问百度HTTPS
            // 校园网劫持通常只针对HTTP或者无法伪造HTTPS证书，因此HTTPS请求在未登录时会失败
            val request = Request.Builder()
                .url("https://www.baidu.com")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()

            val testClient = client.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            testClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun login(studentId: String, password: String, baseUrl: String): LoginResult =
        withContext(Dispatchers.IO) {
            val callback = "dr${Random.nextInt(1000, 9999)}"
            val vParam = Random.nextInt(1000, 9999).toString()

            val url = "$baseUrl?callback=$callback&DDDDD=$studentId&upass=$password" +
                    "&0MKKey=123456&R1=0&R2=&R3=1&R6=0&para=00&v6ip=&terminal_type=1" +
                    "&lang=zh-cn&jsVersion=4.2&v=$vParam&lang=zh"

            val host = try {
                URL(baseUrl).host
            } catch (e: Exception) {
                "172.17.254.6"
            }

            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36 Edg/137.0.0.0"
                )
                .header("Referer", "http://$host/")
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .header("Host", host)
                .build()

            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    if (body.contains("\"result\":1")) {
                        LoginResult(true, "登录成功", body)
                    } else if (body.contains("userid error")) {
                        LoginResult(false, "账号或密码错误", body)
                    } else {
                        LoginResult(false, "登录失败: 未知错误", body)
                    }
                } else {
                    LoginResult(false, "网络错误: HTTP ${response.code}", body)
                }
            } catch (e: IOException) {
                LoginResult(false, "网络请求异常: ${e.message}", "")
            } catch (e: Exception) {
                LoginResult(false, "发生错误: ${e.message}", "")
            }
        }
}

data class LoginResult(
    val success: Boolean,
    val message: String,
    val responseText: String
)