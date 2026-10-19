package top.onering.easyconnecter.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.io.StringReader
import java.math.BigInteger
import java.util.concurrent.TimeUnit

const val PROJECT_URL = "https://github.com/oneringr/1Connecter"
const val RELEASES_URL = "$PROJECT_URL/releases"

sealed interface ReleaseCheckResult {
    data object UpToDate : ReleaseCheckResult
    data class Available(val version: String, val url: String) : ReleaseCheckResult
    data class Failure(val message: String) : ReleaseCheckResult
}

/** Checks only published releases; downloading or opening a release remains a UI action. */
class ReleaseChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
    private val apiUrl: HttpUrl =
        "https://api.github.com/repos/oneringr/1Connecter/releases/latest".toHttpUrl(),
) {
    private val gson = Gson()

    suspend fun check(currentVersion: String): ReleaseCheckResult {
        currentCoroutineContext().ensureActive()
        val current = parseVersion(currentVersion)
            ?: return ReleaseCheckResult.Failure("当前版本号无法识别，暂时无法比较更新。")
        val request = Request.Builder().url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "1Connecter")
            .build()
        return try {
            executeCancellable(request) { response -> readRelease(response, current) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InterruptedIOException) {
            ReleaseCheckResult.Failure("检查更新超时，请检查网络后重试。")
        } catch (_: IOException) {
            ReleaseCheckResult.Failure("无法连接 GitHub，请检查网络后重试。")
        } catch (_: JsonParseException) {
            invalidResponse()
        } catch (_: IllegalStateException) {
            invalidResponse()
        }
    }

    private fun readRelease(response: Response, current: List<BigInteger>): ReleaseCheckResult {
        if (!response.isSuccessful) {
            val message = when {
                response.code == 404 -> "未找到 GitHub 正式 Release，请稍后再试。"
                response.code == 429 || response.code == 403 &&
                    (response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null) ->
                    "GitHub 检查频率已受限，请稍后再试。"
                response.code == 403 -> "GitHub 拒绝了更新检查请求，请稍后再试。"
                else -> "检查更新失败（HTTP ${response.code}），请稍后再试。"
            }
            return ReleaseCheckResult.Failure(message)
        }
        val body = response.body ?: return invalidResponse()
        // Read before JSON parsing so transport errors keep their network/timeout meaning.
        val release = parseReleaseBody(body.string()) ?: return invalidResponse()
        val draft = release.boolean("draft") ?: return invalidResponse()
        val prerelease = release.boolean("prerelease") ?: return invalidResponse()
        if (draft || prerelease) {
            return ReleaseCheckResult.Failure("GitHub 返回的版本不是正式 Release，暂时无法检查更新。")
        }
        val tag = release.string("tag_name") ?: return invalidResponse()
        val latest = parseVersion(tag)
            ?: return ReleaseCheckResult.Failure("GitHub 发布的版本号无法识别，暂时无法比较更新。")
        val releaseUrl = release.string("html_url")?.let { trustedReleaseUrl(it, tag) }
            ?: return ReleaseCheckResult.Failure("GitHub 返回的 Release 链接无效，暂时无法检查更新。")
        return if (compareVersions(latest, current) > 0) {
            ReleaseCheckResult.Available(tag, releaseUrl.toString())
        } else {
            ReleaseCheckResult.UpToDate
        }
    }

    // Cancellation stays attached until parsing has consumed and closed the response body.
    private suspend fun executeCancellable(
        request: Request,
        readResponse: (Response) -> ReleaseCheckResult,
    ): ReleaseCheckResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    continuation.resumeWith(Result.success(response.use(readResponse)))
                } catch (error: Exception) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        })
    }

    private fun trustedReleaseUrl(value: String, tag: String): HttpUrl? {
        val url = value.toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments
        return url.takeIf {
            it.isHttps && it.host == "github.com" && it.port == 443 &&
                it.username.isEmpty() && it.password.isEmpty() &&
                it.query == null && it.fragment == null &&
                segments.size == 5 && segments[0].equals("oneringr", ignoreCase = true) &&
                segments[1].equals("1Connecter", ignoreCase = true) &&
                segments[2] == "releases" && segments[3] == "tag" && segments[4] == tag
        }
    }

    private fun parseReleaseBody(body: String): JsonObject? {
        // Gson 2.10's object parser is lenient; reject non-JSON input before using it.
        try {
            JsonReader(StringReader(body)).use { reader ->
                reader.isLenient = false
                reader.skipValue()
                if (reader.peek() != JsonToken.END_DOCUMENT) return null
            }
        } catch (_: IOException) {
            return null
        }
        return gson.fromJson(body, JsonObject::class.java)
    }

    private fun parseVersion(value: String): List<BigInteger>? {
        if (!VERSION_PATTERN.matches(value)) return null
        return value.removePrefix("v").removePrefix("V").split('.').map { it.toBigInteger() }
    }

    private fun compareVersions(left: List<BigInteger>, right: List<BigInteger>): Int {
        for (index in 0 until maxOf(left.size, right.size)) {
            val comparison = (left.getOrNull(index) ?: BigInteger.ZERO)
                .compareTo(right.getOrNull(index) ?: BigInteger.ZERO)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun JsonObject.string(name: String): String? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.boolean(name: String): Boolean? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun invalidResponse() = ReleaseCheckResult.Failure("GitHub 返回的数据无效，请稍后再试。")

    private companion object {
        val VERSION_PATTERN = Regex("[vV]?[0-9]+(?:\\.[0-9]+)*")
    }
}
