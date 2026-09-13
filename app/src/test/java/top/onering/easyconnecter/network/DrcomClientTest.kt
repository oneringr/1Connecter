package top.onering.easyconnecter.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
}
