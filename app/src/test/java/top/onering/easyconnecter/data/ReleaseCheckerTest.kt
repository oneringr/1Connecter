package top.onering.easyconnecter.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ReleaseCheckerTest {
    private lateinit var server: MockWebServer
    private lateinit var checker: ReleaseChecker

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        checker = checkerWith(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun reportsNewerNumericReleaseWithItsMatchingPage() = runTest {
        for ((current, latest) in listOf("1.9" to "1.10", "1.7" to "v1.7.1", "1.7" to "2")) {
            server.enqueue(MockResponse().setBody(release(latest)))
            assertEquals(
                ReleaseCheckResult.Available(latest, "$RELEASES_URL/tag/$latest"),
                checker.check(current),
            )
        }
        val request = server.takeRequest()
        assertEquals("/repos/oneringr/1Connecter/releases/latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
    }

    @Test
    fun treatsTrailingZerosAsEqualAndDoesNotOfferOlderReleases() = runTest {
        for ((current, latest) in listOf("1.7" to "1.7.0", "v1.7.0" to "1.7", "1.10" to "1.9")) {
            server.enqueue(MockResponse().setBody(release(latest)))
            assertEquals(ReleaseCheckResult.UpToDate, checker.check(current))
        }
    }

    @Test
    fun rejectsDraftAndPrereleaseResults() = runTest {
        for (body in listOf(release("2.0", draft = true), release("2.0", prerelease = true))) {
            server.enqueue(MockResponse().setBody(body))
            assertFailure(checker.check("1.7"), "不是正式 Release")
        }
    }

    @Test
    fun unknownVersionTagsNeverBecomeAnUpToDateResult() = runTest {
        for (tag in listOf("latest", "1.8-beta", "release-1.8", "1..8")) {
            server.enqueue(MockResponse().setBody(release(tag)))
            assertFailure(checker.check("1.7"), "版本号无法识别")
        }
        val requestsBefore = server.requestCount
        assertFailure(checker.check("development"), "当前版本号无法识别")
        assertEquals(requestsBefore, server.requestCount)
    }

    @Test
    fun malformedOrIncompleteResponsesNeverBecomeAnUpToDateResult() = runTest {
        for (body in listOf(
            "not JSON", "[]", "null", "{}", """{"tag_name":"1.7","draft":"false"}""",
            release("1.7").replace('"', '\''), release("1.7") + " /* not JSON */",
        )) {
            server.enqueue(MockResponse().setBody(body))
            assertFailure(checker.check("1.7"), "数据无效")
        }
    }

    @Test
    fun rejectsLinksOutsideTheExactTrustedReleasePage() = runTest {
        val links = listOf(
            "http://github.com/oneringr/1Connecter/releases/tag/1.8",
            "https://github.com.evil.example/oneringr/1Connecter/releases/tag/1.8",
            "https://github.com/another-owner/1Connecter/releases/tag/1.8",
            "$RELEASES_URL/tag/1.7",
            "$RELEASES_URL/download/1.8/application.apk",
            "https://user@github.com/oneringr/1Connecter/releases/tag/1.8",
        )
        for (link in links) {
            server.enqueue(MockResponse().setBody(release("1.8", url = link)))
            assertFailure(checker.check("1.7"), "链接无效")
        }
        server.enqueue(MockResponse().setBody(release("1.7", url = "https://example.com")))
        assertFailure(checker.check("1.7"), "链接无效")
    }

    @Test
    fun distinguishesMissingReleasesRateLimitsAndServerFailures() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertFailure(checker.check("1.7"), "未找到")
        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0"))
        assertFailure(checker.check("1.7"), "频率已受限")
        server.enqueue(MockResponse().setResponseCode(429))
        assertFailure(checker.check("1.7"), "频率已受限")
        server.enqueue(MockResponse().setResponseCode(503))
        assertFailure(checker.check("1.7"), "HTTP 503")
    }

    @Test
    fun reportsTimeoutWithoutClaimingTheAppIsUpToDate() = runBlocking {
        val boundedClient = OkHttpClient.Builder()
            .readTimeout(50, TimeUnit.MILLISECONDS)
            .callTimeout(1, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        server.enqueue(MockResponse().setBody(release("1.8")).setHeadersDelay(200, TimeUnit.MILLISECONDS))
        assertFailure(checkerWith(boundedClient).check("1.7"), "超时")
    }

    @Test
    fun reportsNetworkFailuresWithoutClaimingTheAppIsUpToDate() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        assertFailure(checkerWith(client).check("1.7"), "无法连接 GitHub")
    }

    @Test
    fun cancellationClosesTheCallEvenWhileReadingTheResponseBody() = runBlocking {
        val bodyStarted = CountDownLatch(1)
        val failedCall = CountDownLatch(1)
        val capturedCall = AtomicReference<Call>()
        val completedResult = AtomicReference<ReleaseCheckResult>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) {
                capturedCall.set(call)
                bodyStarted.countDown()
            }

            override fun callFailed(call: Call, ioe: IOException) {
                failedCall.countDown()
            }
        }).build()
        server.enqueue(MockResponse().setBody(release("1.8")).throttleBody(1, 100, TimeUnit.MILLISECONDS))
        val job = launch(Dispatchers.Default) {
            completedResult.set(checkerWith(client).check("1.7"))
        }
        try {
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(capturedCall.get().isCanceled())
            assertTrue(failedCall.await(5, TimeUnit.SECONDS))
            assertTrue(job.isCancelled)
            assertNull(completedResult.get())
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun checkerWith(client: OkHttpClient) = ReleaseChecker(
        client = client,
        apiUrl = server.url("/repos/oneringr/1Connecter/releases/latest"),
    )

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        url: String = "$RELEASES_URL/tag/$tag",
    ): String = Gson().toJson(mapOf(
        "tag_name" to tag, "draft" to draft, "prerelease" to prerelease, "html_url" to url,
    ))

    private fun assertFailure(result: ReleaseCheckResult, messagePart: String) {
        assertTrue("Expected failure but was $result", result is ReleaseCheckResult.Failure)
        assertTrue((result as ReleaseCheckResult.Failure).message.contains(messagePart))
    }
}
