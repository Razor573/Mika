package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.TimeUnit

class AntigravityAuthTest {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun loopbackServer(): MockWebServer = MockWebServer().apply {
        start(InetAddress.getByName("127.0.0.1"), 0)
    }

    private fun newAuth(server: MockWebServer, now: () -> Long = { 1_700_000_000_000L }): AntigravityAuth {
        val base = server.url("/").toString().trimEnd('/')
        return AntigravityAuth(
            http = http,
            authorizeUrl = "$base/o/oauth2/v2/auth",
            tokenUrl = "$base/token",
            userinfoUrl = "$base/oauth2/v2/userinfo",
            prodEndpoint = base,
            dailyEndpoint = base,
            now = now,
        )
    }

    private fun jwtWithClaims(jsonPayload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray(Charsets.UTF_8))
        val payload = enc.encodeToString(jsonPayload.toByteArray(Charsets.UTF_8))
        return "$header.$payload.sig"
    }

    private fun queryMap(url: String): Map<String, String> {
        val raw = URI(url).rawQuery ?: return emptyMap()
        return raw.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val idx = pair.indexOf('=')
            val k = if (idx < 0) pair else pair.substring(0, idx)
            val v = if (idx < 0) "" else pair.substring(idx + 1)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }
    }

    @Test
    fun credentialsAndWireConstantsMatchSpec() {
        assertTrue(AntigravityAuth.CLIENT_ID.endsWith(".apps.googleusercontent.com"))
        assertTrue(AntigravityAuth.CLIENT_SECRET.startsWith("GOCSPX-"))
        assertEquals("antigravity/hub/2.31.2 windows/amd64", AntigravityAuth.USER_AGENT)
        assertFalse(AntigravityAuth.USER_AGENT.contains("cl="))
        assertEquals("google-cloud-sdk vscode_cloudshelleditor/0.1", AntigravityAuth.GOOG_API_CLIENT)
        assertEquals(
            """{"ideType":"ANTIGRAVITY","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI"}""",
            AntigravityAuth.CLIENT_METADATA,
        )
        assertEquals(
            listOf(
                "openid",
                "https://www.googleapis.com/auth/cloud-platform",
                "https://www.googleapis.com/auth/userinfo.email",
                "https://www.googleapis.com/auth/userinfo.profile",
                "https://www.googleapis.com/auth/cclog",
                "https://www.googleapis.com/auth/experimentsandconfigs",
            ),
            AntigravityAuth.SCOPES_LIST,
        )
    }

    @Test
    fun rejectsNonLoopbackHttpEndpoints() {
        val badUrls = listOf(
            "http://accounts.google.com/o/oauth2/v2/auth",
            "http://oauth2.googleapis.com/token",
            "http://cloudcode-pa.googleapis.com",
            "ftp://cloudcode-pa.googleapis.com",
        )
        for (bad in badUrls) {
            for (slot in 0..4) {
                try {
                    AntigravityAuth(
                        http = http,
                        authorizeUrl = if (slot == 0) bad else AntigravityAuth.AUTHORIZE_URL,
                        tokenUrl = if (slot == 1) bad else AntigravityAuth.TOKEN_URL,
                        userinfoUrl = if (slot == 2) bad else AntigravityAuth.USERINFO_URL,
                        prodEndpoint = if (slot == 3) bad else AntigravityAuth.PROD_ENDPOINT,
                        dailyEndpoint = if (slot == 4) bad else AntigravityAuth.DAILY_ENDPOINT,
                    )
                    fail("Expected IllegalArgumentException for slot=$slot url=$bad")
                } catch (_: IllegalArgumentException) {
                    // expected
                }
            }
        }
    }

    @Test
    fun loopbackSupportsCustomPathAndFallsBackWhenPortBusy() = runBlocking<Unit> {
        LoopbackCallback.open(port = 0, path = "oauth-callback").use { cb ->
            assertEquals("/oauth-callback", cb.path)
            assertEquals("http://localhost:${cb.port}/oauth-callback", cb.redirectUri("localhost"))

            val sender = launch(Dispatchers.IO) {
                http.newCall(Request.Builder().url("http://127.0.0.1:${cb.port}/auth/callback?code=wrong").build()).execute().use { resp ->
                    assertEquals(404, resp.code)
                }
                http.newCall(Request.Builder().url("http://127.0.0.1:${cb.port}/oauth-callback?code=ok_code&state=s1").build()).execute().use { resp ->
                    assertEquals(200, resp.code)
                    assertEquals("done", resp.body?.string())
                }
            }

            val received = cb.await(timeoutMs = 5_000L, accept = { it["state"] == "s1" }) { "done" }
            assertEquals("ok_code", received?.get("code"))
            sender.join()
        }

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val busyPort = occupied.localPort
            try {
                LoopbackCallback.open(port = busyPort, path = "/oauth-callback").close()
                fail("Expected PortBusyException when port $busyPort is already bound")
            } catch (e: PortBusyException) {
                assertEquals(busyPort, e.port)
            }

            val auth = AntigravityAuth(http)
            try {
                auth.openCallback(port = busyPort, strictPort = true).close()
                fail("Expected PortBusyException with strictPort=true")
            } catch (e: PortBusyException) {
                assertEquals(busyPort, e.port)
            }

            auth.openCallback(port = busyPort, strictPort = false).use { fallback ->
                assertEquals(AntigravityAuth.CALLBACK_PATH, fallback.path)
                assertTrue(fallback.port > 0 && fallback.port != busyPort)
            }
        }
    }

    @Test
    fun authorizeUrlAndCallbackValidation() {
        val auth = AntigravityAuth(http)
        val attempt = auth.newAttempt("http://localhost:51121/oauth-callback")
        val url = auth.authorizeUrl(attempt)
        val q = queryMap(url)

        assertEquals(AntigravityAuth.CLIENT_ID, q["client_id"])
        assertEquals("code", q["response_type"])
        assertEquals("http://localhost:51121/oauth-callback", q["redirect_uri"])
        assertEquals(AntigravityAuth.SCOPES, q["scope"])
        assertEquals(attempt.challenge, q["code_challenge"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(attempt.state, q["state"])
        assertEquals("offline", q["access_type"])
        assertEquals("consent", q["prompt"])

        assertTrue(auth.accepts(mapOf("code" to "c1", "state" to attempt.state), attempt))
        assertFalse(auth.accepts(mapOf("code" to "c1", "state" to "wrong"), attempt))
        assertTrue(auth.accepts(mapOf("error" to "access_denied"), attempt))

        assertEquals("auth_code_1", auth.readCallback(mapOf("code" to "auth_code_1", "state" to attempt.state), attempt))

        try {
            auth.readCallback(mapOf("error" to "access_denied", "state" to attempt.state), attempt)
            fail("Expected Denied")
        } catch (e: AccountSignInException) {
            assertEquals(AccountSignInFailure.Denied, e.reason)
        }

        try {
            auth.readCallback(mapOf("code" to "c1", "state" to "bad_state"), attempt)
            fail("Expected Rejected on state mismatch")
        } catch (e: AccountSignInException) {
            assertEquals(AccountSignInFailure.Rejected, e.reason)
        }

        try {
            auth.readCallback(mapOf("state" to attempt.state), attempt)
            fail("Expected Rejected on missing code")
        } catch (e: AccountSignInException) {
            assertEquals(AccountSignInFailure.Rejected, e.reason)
        }
    }

    @Test
    fun accountSessionRoundTripBackcompatSignedOutAndRedaction() {
        val session = AccountSession(
            provider = AntigravityAuth.PROVIDER,
            accountId = "sub-1",
            label = "user@example.com",
            accessToken = "secret-access-token",
            refreshToken = "secret-refresh-token",
            expiresAtMs = 1_700_003_600_000L,
            scopes = AntigravityAuth.SCOPES_LIST,
            apiBase = AntigravityAuth.DAILY_ENDPOINT,
            clientId = AntigravityAuth.CLIENT_ID,
            issuer = AntigravityAuth.ISSUER,
            tokenUrl = AntigravityAuth.TOKEN_URL,
            projectId = "proj-123",
        )
        val parsed = AccountSession.fromJson(session.toJson())
        assertEquals(session, parsed)
        assertTrue(session.signedIn)

        val repr = session.toString()
        assertFalse(repr.contains("secret-access-token"))
        assertFalse(repr.contains("secret-refresh-token"))
        assertTrue(repr.contains("proj-123"))

        val legacyJson = """{"provider":"antigravity","accountId":"sub-legacy","accessToken":"at-1","refreshToken":"rt-1","expiresAtMs":123}"""
        val legacy = AccountSession.fromJson(legacyJson)
        assertNotNull(legacy)
        assertEquals("", legacy!!.projectId)

        val out = session.signedOut()
        assertFalse(out.signedIn)
        assertEquals("", out.accessToken)
        assertEquals("", out.refreshToken)
        assertEquals(0L, out.expiresAtMs)
        assertEquals("", out.projectId)
        assertEquals("user@example.com", out.label)
    }

    @Test
    fun tokenExchangeRefreshAndTokensCache() = runBlocking<Unit> {
        loopbackServer().use { server ->
            var clock = 1_700_000_000_000L
            val auth = newAuth(server) { clock }
            val attempt = auth.newAttempt("http://localhost:51121/oauth-callback")

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"access_token":"at-initial","refresh_token":"rt-1","expires_in":3600,"id_token":"${jwtWithClaims("""{"sub":"s1","email":"a@b.com"}""")}","scope":"openid email"}""",
                ),
            )
            val tokens = auth.exchange("code-123", attempt)
            assertEquals("at-initial", tokens.accessToken)
            assertEquals("rt-1", tokens.refreshToken)
            assertEquals(3_600L, tokens.expiresInSec)

            val exReq = server.takeRequest()
            val exBody = URLDecoder.decode(exReq.body.readUtf8(), Charsets.UTF_8)
            assertTrue(exBody.contains("grant_type=authorization_code"))
            assertTrue(exBody.contains("code=code-123"))
            assertTrue(exBody.contains("code_verifier=${attempt.verifier}"))
            assertTrue(exBody.contains("client_secret=${AntigravityAuth.CLIENT_SECRET}"))

            val expiringSession = AccountSession(
                provider = AntigravityAuth.PROVIDER,
                accountId = "s1",
                label = "a@b.com",
                accessToken = "at-old",
                refreshToken = "rt-1",
                expiresAtMs = clock + 60_000L,
                tokenUrl = server.url("/token").toString(),
                projectId = "proj-kept",
            )

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"access_token":"at-refreshed","expires_in":7200}""",
                ),
            )
            val refreshed = auth.refresh(expiringSession) as OauthTokenResult.Ok
            assertEquals("at-refreshed", refreshed.tokens.accessToken)
            assertNull(refreshed.tokens.refreshToken)

            val refReq = server.takeRequest()
            val refBody = URLDecoder.decode(refReq.body.readUtf8(), Charsets.UTF_8)
            assertTrue(refBody.contains("grant_type=refresh_token"))
            assertTrue(refBody.contains("refresh_token=rt-1"))

            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            val rejected = auth.refresh(expiringSession) as OauthTokenResult.Rejected
            assertEquals("invalid_grant", rejected.code)
            assertTrue(rejected.terminal)
            server.takeRequest()

            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
            val unauth = auth.refresh(expiringSession) as OauthTokenResult.Rejected
            assertTrue(unauth.terminal)
            server.takeRequest()

            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            val failed = auth.refresh(expiringSession) as OauthTokenResult.Failed
            assertTrue(failed.error is ModelError.Server)
            server.takeRequest()

            var stored: AccountSession? = expiringSession
            val savedStates = mutableListOf<AccountSession>()
            val tokenProvider = auth.tokens(
                load = { stored },
                save = { _, after ->
                    stored = after
                    savedStates.add(after)
                },
                now = { clock },
                refreshAheadMs = 5 * 60_000L,
            )

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"access_token":"at-auto","expires_in":3600}"""))
            val autoAccess = tokenProvider.get(false)
            assertEquals("at-auto", autoAccess)
            assertEquals(1, savedStates.size)
            assertEquals("rt-1", savedStates[0].refreshToken)
            assertEquals("proj-kept", savedStates[0].projectId)
            server.takeRequest()

            // Fresh token should be served from cache without another network round-trip.
            val beforeCacheHit = server.requestCount
            assertEquals("at-auto", tokenProvider.get(false))
            assertEquals(beforeCacheHit, server.requestCount)
        }
    }

    @Test
    fun fetchUserEmailPrefersIdTokenClaimAndFallsBackToUserinfo() = runBlocking<Unit> {
        loopbackServer().use { server ->
            val auth = newAuth(server)
            val idToken = jwtWithClaims("""{"sub":"user-1","email":"claim@example.com"}""")
            assertEquals("claim@example.com", auth.fetchUserEmail("at-1", idToken))
            assertEquals(0, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"sub":"user-2","email":"info@example.com"}"""))
            assertEquals("info@example.com", auth.fetchUserEmail("at-2", null))
            val req = server.takeRequest()
            assertEquals("Bearer at-2", req.getHeader("Authorization"))
            assertEquals("/oauth2/v2/userinfo", req.path)
        }
    }

    @Test
    fun discoverProjectHandlesDirectObjectPollingAndFallback() = runBlocking<Unit> {
        loopbackServer().use { server ->
            val auth = newAuth(server)

            // 1. Direct string project from loadCodeAssist
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cloudaicompanionProject":"direct-proj-1"}"""))
            assertEquals("direct-proj-1", auth.discoverProject("at-1", pollDelayMs = 0L))
            val loadReq = server.takeRequest()
            assertEquals("/v1internal:loadCodeAssist", loadReq.path)
            assertEquals("Bearer at-1", loadReq.getHeader("Authorization"))
            assertEquals(AntigravityAuth.USER_AGENT, loadReq.getHeader("User-Agent"))
            assertEquals(AntigravityAuth.GOOG_API_CLIENT, loadReq.getHeader("X-Goog-Api-Client"))
            assertEquals(AntigravityAuth.CLIENT_METADATA, loadReq.getHeader("Client-Metadata"))

            // 2. Direct object { id } project
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cloudaicompanionProject":{"id":"obj-proj-2"}}"""))
            assertEquals("obj-proj-2", auth.discoverProject("at-2", pollDelayMs = 0L))
            server.takeRequest()

            // 3. Onboard polling across attempts until response.cloudaicompanionProject appears
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"allowedTiers":[{"id":"free-tier","isDefault":false},{"id":"pro-tier","isDefault":true}]}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"done":false}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"done":false}"""))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"done":true,"response":{"cloudaicompanionProject":{"id":"onboard-proj-3"}}}""",
                ),
            )
            val delays = mutableListOf<Long>()
            assertEquals(
                "onboard-proj-3",
                auth.discoverProject("at-3", maxAttempts = 5, pollDelayMs = 25L, sleep = { delays.add(it) }),
            )
            assertEquals(listOf(25L, 25L), delays)
            server.takeRequest()
            val onboardReq = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("pro-tier", onboardReq["tierId"]?.jsonPrimitive?.content)
            server.takeRequest()
            server.takeRequest()

            // 4. Fallback to rising-fact-p41fc ONLY when setup_no_project + probe returns non-empty models
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"allowedTiers":[{"id":"free-tier","isDefault":true}]}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"done":true}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":{"gemini-4-hydrogen-medium":{}}}"""))
            assertEquals(
                AntigravityAuth.DEFAULT_PROJECT_ID,
                auth.resolveProjectWithFallback("at-4", maxAttempts = 3, pollDelayMs = 0L),
            )
            server.takeRequest()
            server.takeRequest()
            val probeReq = server.takeRequest()
            assertEquals("/v1internal:fetchAvailableModels", probeReq.path)
            assertEquals("""{"project":"rising-fact-p41fc"}""", probeReq.body.readUtf8())

            // 5. Does NOT fall back when probe returns empty models
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"allowedTiers":[]}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"done":true}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":{}}"""))
            try {
                auth.resolveProjectWithFallback("at-5", maxAttempts = 2, pollDelayMs = 0L)
                fail("Expected AccountSignInException when probe models empty")
            } catch (e: AccountSignInException) {
                assertEquals(AccountSignInFailure.NoSubscription, e.reason)
                assertEquals("setup_no_project", e.code)
            }
            server.takeRequest()
            server.takeRequest()
            server.takeRequest()

            // 6. Does NOT fall back on HTTP 500 or 403 setup errors
            server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))
            try {
                auth.resolveProjectWithFallback("at-6", maxAttempts = 2, pollDelayMs = 0L)
                fail("Expected Network failure on HTTP 500")
            } catch (e: AccountSignInException) {
                assertEquals(AccountSignInFailure.Network, e.reason)
                assertEquals("http_500", e.code)
            }
            server.takeRequest()

            server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
            try {
                auth.resolveProjectWithFallback("at-7", maxAttempts = 2, pollDelayMs = 0L)
                fail("Expected Rejected failure on HTTP 403")
            } catch (e: AccountSignInException) {
                assertEquals(AccountSignInFailure.Rejected, e.reason)
                assertEquals("http_403", e.code)
            }
            server.takeRequest()
        }
    }

    @Test
    fun fullLoopbackSignInPopulatesSessionWithProjectId() = runBlocking<Unit> {
        loopbackServer().use { server ->
            val auth = newAuth(server)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"access_token":"at-full","refresh_token":"rt-full","expires_in":3600,"id_token":"${jwtWithClaims("""{"sub":"uid-42","email":"pilot@mika.dev"}""")}","scope":"openid"}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"cloudaicompanionProject":"proj-full-9"}"""),
            )

            val senderJob = mutableListOf<kotlinx.coroutines.Job>()
            val session = auth.signIn(
                port = 0,
                openBrowser = { authorizeUrl ->
                    val q = queryMap(authorizeUrl)
                    val redirect = q["redirect_uri"]!!
                    val state = q["state"]!!
                    val callbackUrl = "$redirect?code=code-xyz&state=$state"
                    senderJob += launch(Dispatchers.IO) {
                        http.newCall(Request.Builder().url(callbackUrl).build()).execute().use { resp ->
                            assertEquals(200, resp.code)
                        }
                    }
                },
                pollDelayMs = 0L,
            )
            senderJob.forEach { it.join() }

            assertEquals(AntigravityAuth.PROVIDER, session.provider)
            assertEquals("uid-42", session.accountId)
            assertEquals("pilot@mika.dev", session.label)
            assertEquals("at-full", session.accessToken)
            assertEquals("rt-full", session.refreshToken)
            assertEquals("proj-full-9", session.projectId)
            assertEquals(server.url("/").toString().trimEnd('/'), session.apiBase)
            assertEquals(server.url("/token").toString(), session.tokenUrl)
        }
    }

    @Test
    fun collapseModelIdAndIsSupportedGeminiModelFilterCorrectly() {
        assertEquals("gemini-4-hydrogen", AntigravityAuth.collapseModelId("gemini-4-hydrogen-medium"))
        assertEquals("gemini-4-argon", AntigravityAuth.collapseModelId("models/gemini-4-argon-xhigh"))
        assertEquals("GEMINI-4-ARGON", AntigravityAuth.collapseModelId("GEMINI-4-ARGON-HIGH"))
        assertEquals("gemini-4-hydrogen", AntigravityAuth.collapseModelId("gemini-4-hydrogen-LOW"))
        assertEquals("gemini-3.8-flash", AntigravityAuth.collapseModelId("gemini-3.8-flash-tiered"))
        assertEquals("gemini-pro", AntigravityAuth.collapseModelId("models/gemini-pro-agent"))
        assertEquals("", AntigravityAuth.collapseModelId("   "))

        assertTrue(AntigravityAuth.isSupportedGeminiModel("gemini-4-hydrogen-medium"))
        assertTrue(AntigravityAuth.isSupportedGeminiModel("models/gemini-4-argon-xhigh"))
        assertTrue(AntigravityAuth.isSupportedGeminiModel("gemini-3.8-flash-tiered"))
        assertTrue(AntigravityAuth.isSupportedGeminiModel("gemini-3.1-pro"))

        val rejected = listOf(
            "",
            "claude-sonnet-4-6",
            "gpt-oss-120b",
            "gemini-4-carbon",
            "gemini-image-gen",
            "imagen-3.0-generate",
            "veo-2.0",
            "gemini-embedding-001",
            "aqa",
            "gemini-2.5-flash-tts",
            "gemini-2.5-flash-audio",
            "gemini-live-2.5-flash",
            "gemini-robotics-er",
            "gemini-computer-use",
        )
        for (id in rejected) {
            assertFalse("Expected rejected: $id", AntigravityAuth.isSupportedGeminiModel(id))
        }
    }
}
