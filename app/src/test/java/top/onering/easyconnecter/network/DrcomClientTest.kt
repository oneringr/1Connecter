package top.onering.easyconnecter.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.CipherSuite
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.TlsVersion
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.Carrier
import top.onering.easyconnecter.model.LoginOutcome
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DrcomClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DrcomClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DrcomClient(
            client = OkHttpClient(),
            endpointProvider = CampusEndpointProvider {
                CampusEndpoints(server.url("/"), server.url("/"))
            },
            connectivityProbe = ConnectivityProbe { false }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun buildsHarCompatibleLoginRequestForBothCarriers() = runTest {
        server.enqueue(MockResponse().setBody("dr1003({\"result\":1})"))
        server.enqueue(MockResponse().setBody("dr1003({\"result\":1})"))

        client.login("20230001", "123456", Campus.CHENGBEI, Carrier.MOBILE)
        client.login("20230001", "123456", Campus.CHENGBEI, Carrier.TELECOM)

        val mobile = server.takeRequest()
        val telecom = server.takeRequest()
        assertEquals("/drcom/login", mobile.requestUrl?.encodedPath)
        assertEquals("1", mobile.requestUrl?.queryParameter("R3"))
        assertEquals("2", telecom.requestUrl?.queryParameter("R3"))
        assertEquals(listOf("zh-cn", "zh"), mobile.requestUrl?.queryParameterValues("lang"))
        assertEquals("", mobile.requestUrl?.queryParameter("R2"))
        assertEquals("", mobile.requestUrl?.queryParameter("v6ip"))
        assertEquals("http://172.17.254.6/a79.htm", mobile.getHeader("Referer"))
        assertEquals("123456", mobile.requestUrl?.queryParameter("0MKKey"))
        assertEquals("1", mobile.requestUrl?.queryParameter("terminal_type"))
    }

    @Test
    fun parsesAccountErrorsThatMustRemoveTheAccount() {
        val invalid = client.parseLoginResponse(
            "dr1003({\"result\":0,\"msga\":\"userid error\"})",
            Carrier.MOBILE
        )
        val unbound = client.parseLoginResponse(
            "dr1003({\"result\":0,\"msga\":\"bind userid error\"})",
            Carrier.TELECOM
        )
        val invalid2 = client.parseLoginResponse(
            "dr1003({\"result\":0,\"msga\":\"userid error2\"})",
            Carrier.MOBILE
        )
        val operatorPassword = client.parseLoginResponse(
            "dr1003({\"result\":0,\"msga\":\"Oppp error\"})",
            Carrier.TELECOM
        )

        assertTrue(invalid is LoginOutcome.InvalidCredentials)
        assertTrue(unbound is LoginOutcome.OperatorNotBound)
        assertTrue(invalid2 is LoginOutcome.InvalidCredentials)
        assertTrue(operatorPassword is LoginOutcome.InvalidCredentials)
    }

    @Test
    fun parsesRetryableKnownJsonpOutcomes() {
        val limited = client.parseLoginResponse(
            "dr1003({\"result\":0,\"msga\":\"error5 waitsec <3\"})",
            Carrier.TELECOM
        )
        val clientIpOnline = client.parseLoginResponse(
            "dr1003({\"result\":0,\"msga\":\"clientip online\"})",
            Carrier.MOBILE
        )

        assertEquals(3, (limited as LoginOutcome.RateLimited).waitSeconds)
        assertTrue(clientIpOnline is LoginOutcome.ClientIpOnline)
    }

    @Test
    fun recognizesClientIpOnlineFromFallbackMessageIgnoringCaseAndOuterWhitespace() {
        val outcome = client.parseLoginResponse(
            "dr1003({\"result\":0,\"msg\":\" CLIENTIP ONLINE \"})",
            Carrier.MOBILE
        )

        assertTrue(outcome is LoginOutcome.ClientIpOnline)
    }

    @Test
    fun delegatesConnectivityCheckToPingProbe() = runTest {
        var probeCalls = 0
        val probeClient = DrcomClient(
            client = OkHttpClient(),
            endpointProvider = CampusEndpointProvider {
                CampusEndpoints(server.url("/"), server.url("/"))
            },
            connectivityProbe = ConnectivityProbe {
                probeCalls++
                true
            }
        )

        assertTrue(probeClient.isInternetConnected())
        assertEquals(1, probeCalls)
    }

    @Test
    fun reportsMalformedAndUnknownResponsesAsProtocolErrors() {
        val malformed = client.parseLoginResponse("not jsonp", Carrier.MOBILE)
        val unknown = client.parseLoginResponse(
            "dr1003({\"result\":0,\"msga\":\"new server error\"})",
            Carrier.MOBILE
        )

        assertTrue(malformed is LoginOutcome.ProtocolError)
        assertTrue(unknown is LoginOutcome.ProtocolError)
        listOf("{}", "{\"result\":2,\"msga\":\"userid error\"}", "{\"result\":[]}").forEach {
            assertTrue(client.parseLoginResponse(it, Carrier.MOBILE) is LoginOutcome.ProtocolError)
        }
        assertTrue(client.parseLoginResponse("{\"result\":\"ok\"}", Carrier.MOBILE) is LoginOutcome.Success)
    }

    @Test
    fun campusDefinesBothGatewayHosts() {
        assertEquals("172.17.254.6", Campus.CHENGBEI.host)
        assertEquals("192.168.2.27", Campus.ZHONGSHAN.host)
    }

    @Test
    fun parsesStatusAndBuildsUnbindAndLogoutRequests() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "dr1002({\"result\":0,\"uid\":\"20230001\",\"ss4\":\"aabbccddeeff\",\"ss5\":\"10.1.2.3\"})"
            )
        )
        server.enqueue(MockResponse().setBody("dr1002({\"result\":1})"))
        server.enqueue(MockResponse().setBody("dr1003({\"result\":1})"))

        val status = client.checkStatus(Campus.CHENGBEI).getOrThrow()
        client.unbindMac(Campus.CHENGBEI, "20230001", "AABBCCDDEEFF", "167838211").getOrThrow()
        client.logout(Campus.CHENGBEI).getOrThrow()

        assertFalse(status.loggedIn)
        server.takeRequest()
        val unbind = server.takeRequest()
        val logout = server.takeRequest()
        assertEquals("/eportal/portal/mac/unbind", unbind.requestUrl?.encodedPath)
        assertEquals("AABBCCDDEEFF", unbind.requestUrl?.queryParameter("wlan_user_mac"))
        assertEquals("167838211", unbind.requestUrl?.queryParameter("wlan_user_ip"))
        assertEquals("/drcom/logout", logout.requestUrl?.encodedPath)
    }

    @Test
    fun unknownStatusCannotBeMistakenForConfirmedOffline() = runTest {
        listOf("{}", "{\"result\":2}", "{\"result\":-1}", "{\"result\":\"ok\"}",
            "{\"result\":null}", "{\"result\":[]}", "<html>portal</html>").forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertTrue("Unexpected offline status for $body", client.checkStatus(Campus.CHENGBEI).isFailure)
        }
        server.enqueue(MockResponse().setBody("dr1001({\"result\":\"1\",\"uid\":\"20230001\"});"))
        assertTrue(client.checkStatus(Campus.CHENGBEI).getOrThrow().loggedIn)
        server.enqueue(MockResponse().setBody("{\"result\":\"0\"}"))
        assertFalse(client.checkStatus(Campus.CHENGBEI).getOrThrow().loggedIn)
    }

    @Test
    fun operationsRequireExplicitSuccessAndRejectCaptiveHtmlOrMissingResult() = runTest {
        val invalidBodies = listOf(
            "", "<html>portal</html>", "<script>dr1001({\"result\":1})</script>",
            "{}", "{\"result\":2}", "{\"result\":null}", "{\"result\":[]}",
            "{\"result\":0,\"msg\":\"ok\"}"
        )
        for (body in invalidBodies) {
            server.enqueue(MockResponse().setBody(body))
            server.enqueue(MockResponse().setBody(body))
            assertTrue("Unexpected logout success for $body", client.logout(Campus.CHENGBEI).isFailure)
            assertTrue("Unexpected unbind success for $body",
                client.unbindMac(Campus.CHENGBEI, "20230001", "AABBCCDDEEFF", "167838211").isFailure)
        }
        for (body in listOf("{\"result\":1}", "{\"result\":\"1\"}", "dr1002({\"result\":\"ok\"})")) {
            server.enqueue(MockResponse().setBody(body))
            server.enqueue(MockResponse().setBody(body))
            assertTrue(client.logout(Campus.CHENGBEI).isSuccess)
            assertTrue(client.unbindMac(Campus.CHENGBEI, "20230001", "AABBCCDDEEFF", "167838211").isSuccess)
        }
    }

    @Test
    fun cancellationStopsEveryPortalCallBeforeItCanReachTheServer() = runBlocking {
        val operations: List<suspend (DrcomClient) -> Unit> = listOf(
            { it.login("20230001", "123456", Campus.CHENGBEI, Carrier.MOBILE); Unit },
            { it.checkStatus(Campus.CHENGBEI); Unit },
            { it.logout(Campus.CHENGBEI); Unit },
            { it.unbindMac(Campus.CHENGBEI, "20230001", "AABBCCDDEEFF", "167838211"); Unit }
        )
        for (operation in operations) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failed = CountDownLatch(1)
            val capturedCall = AtomicReference<Call>()
            val httpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedCall.set(chain.call())
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    chain.proceed(chain.request())
                }
                .eventListener(object : EventListener() {
                    override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
                })
                .build()
            val cancellableClient = testClient(httpClient)
            val job = launch(Dispatchers.Default) { operation(cancellableClient) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(capturedCall.get().isCanceled())
            } finally {
                release.countDown()
            }
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertTrue(job.isCancelled)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellationAlsoClosesAResponseWhileItsBodyIsBeingRead() = runBlocking {
        val bodyStarted = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val capturedCall = AtomicReference<Call>()
        val httpClient = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) {
                    capturedCall.set(call)
                    bodyStarted.countDown()
                }
                override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
            })
            .build()
        server.enqueue(MockResponse().setBody("dr1001({\"result\":1})").throttleBody(1, 100, TimeUnit.MILLISECONDS))
        val job = launch(Dispatchers.Default) {
            testClient(httpClient).login("20230001", "123456", Campus.CHENGBEI, Carrier.MOBILE)
        }
        assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(capturedCall.get().isCanceled())
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertTrue(job.isCancelled)
    }

    @Test
    fun httpsProbeFallsBackToIndependentHostAfterNetworkFailure() = runTest {
        val hosts = mutableListOf<String>()
        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            hosts.add(chain.request().url.host)
            if (hosts.size == 1) throw IOException("First probe unavailable")
            probeResponse(chain.request(), 200)
        }.build()

        assertTrue(HttpsConnectivityProbe(httpClient).ping())
        assertEquals(listOf("www.baidu.com", "www.qq.com"), hosts)
    }

    @Test
    fun httpsProbeRejectsRedirectsHttpErrorsAndMissingTls() = runTest {
        for ((code, tls) in listOf(302 to true, 503 to true, 200 to false)) {
            val hosts = mutableListOf<String>()
            val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                hosts.add(chain.request().url.host)
                probeResponse(chain.request(), code, tls)
            }.build()
            assertFalse(HttpsConnectivityProbe(httpClient).ping())
            assertEquals(listOf("www.baidu.com", "www.qq.com"), hosts)
        }
    }

    @Test
    fun cancellingHttpsProbeDoesNotStartTheFallbackRequest() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val capturedCall = AtomicReference<Call>()
        val hosts = mutableListOf<String>()
        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            capturedCall.set(chain.call())
            hosts.add(chain.request().url.host)
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            try {
                throw IOException("Probe was cancelled")
            } finally {
                finished.countDown()
            }
        }.build()
        val job = launch(Dispatchers.Default) { HttpsConnectivityProbe(httpClient).ping() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(capturedCall.get().isCanceled())
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("www.baidu.com"), hosts)
        assertTrue(job.isCancelled)
    }

    private fun testClient(httpClient: OkHttpClient) = DrcomClient(
        client = httpClient,
        endpointProvider = CampusEndpointProvider { CampusEndpoints(server.url("/"), server.url("/")) },
        connectivityProbe = ConnectivityProbe { false }
    )

    // Transport-free probe responses exercise the HTTP/TLS decision without accessing public sites.
    private fun probeResponse(request: okhttp3.Request, code: Int, tls: Boolean = true): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Probe response")
            .header("Location", "http://portal.example/login")
            .body("".toResponseBody())
            .apply {
                if (tls) handshake(Handshake.get(
                    TlsVersion.TLS_1_2, CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                    emptyList(), emptyList()
                ))
            }
            .build()
}
