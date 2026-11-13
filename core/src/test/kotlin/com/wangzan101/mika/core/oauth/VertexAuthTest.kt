package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class VertexAuthTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun generateRsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun toPkcs8Pem(keyPair: KeyPair): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----"
    }

    private fun serviceAccountJson(
        keyPair: KeyPair,
        projectId: String = "proj-123",
        clientEmail: String = "mika@proj-123.iam.gserviceaccount.com",
        tokenUri: String? = null,
    ): String {
        val escapedPem = toPkcs8Pem(keyPair).replace("\n", "\\n")
        val uriField = if (tokenUri != null) ""","token_uri":"$tokenUri"""" else ""
        return """{"type":"service_account","project_id":"$projectId","client_email":"$clientEmail","private_key":"$escapedPem"$uriField}"""
    }

    private fun decodeJwtPart(part: String): String {
        assertFalse("JWT part must not contain '=' padding", '=' in part)
        return String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)
    }

    private fun verifyJwtRs256(jwt: String, keyPair: KeyPair, expectedIss: String, expectedAud: String, expectedIat: Long) {
        val parts = jwt.split('.')
        assertEquals(3, parts.size)

        val header = Json.parseToJsonElement(decodeJwtPart(parts[0])).jsonObject
        assertEquals("RS256", header["alg"]!!.jsonPrimitive.content)
        assertEquals("JWT", header["typ"]!!.jsonPrimitive.content)

        val claims = Json.parseToJsonElement(decodeJwtPart(parts[1])).jsonObject
        assertEquals(expectedIss, claims["iss"]!!.jsonPrimitive.content)
        assertEquals(VertexServiceAccount.VERTEX_SCOPE, claims["scope"]!!.jsonPrimitive.content)
        assertEquals(expectedAud, claims["aud"]!!.jsonPrimitive.content)
        assertEquals(expectedIat, claims["iat"]!!.jsonPrimitive.long)
        assertEquals(expectedIat + 3600L, claims["exp"]!!.jsonPrimitive.long)

        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8))
        }
        assertFalse("Signature must not contain '=' padding", '=' in parts[2])
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])))
    }

    @Test
    fun createsAndCryptographicallyVerifiesRs256Jwt() {
        val keyPair = generateRsaKeyPair()
        val raw = serviceAccountJson(keyPair, clientEmail = "worker@proj-123.iam.gserviceaccount.com")
        val sa = VertexServiceAccount.parse(raw)
        val nowSec = 1_700_000_000L
        val jwt = VertexAuth.createJwtAssertion(sa, nowEpochSec = nowSec)

        verifyJwtRs256(
            jwt = jwt,
            keyPair = keyPair,
            expectedIss = "worker@proj-123.iam.gserviceaccount.com",
            expectedAud = VertexServiceAccount.DEFAULT_TOKEN_URI,
            expectedIat = nowSec,
        )
    }

    @Test
    fun singleFlightConcurrentGetHitsServerOnce() = runBlocking {
        val keyPair = generateRsaKeyPair()
        val raw = serviceAccountJson(keyPair)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBodyDelay(80, TimeUnit.MILLISECONDS)
                .setBody("""{"access_token":"tok-concurrent","expires_in":3600,"token_type":"Bearer"}"""),
        )
        val provider = VertexTokenProvider(
            http = http,
            loadServiceAccountJson = { raw },
            nowMs = { 1_700_000_000_000L },
            tokenUrlOverride = server.url("/token").toString(),
        )

        val results = (1..8).map {
            async { provider.get(false) }
        }.awaitAll()

        assertTrue(results.all { it == "tok-concurrent" })
        assertEquals(1, server.requestCount)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
        val form = req.body.readUtf8()
        assertTrue("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer" in form)
        val assertion = form.substringAfter("assertion=").substringBefore('&')
        verifyJwtRs256(
            jwt = java.net.URLDecoder.decode(assertion, Charsets.UTF_8),
            keyPair = keyPair,
            expectedIss = "mika@proj-123.iam.gserviceaccount.com",
            expectedAud = VertexServiceAccount.DEFAULT_TOKEN_URI,
            expectedIat = 1_700_000_000L,
        )
    }

    @Test
    fun refreshesBeforeExpiryOnFreshFlagAndOnSaRotation() = runBlocking {
        val keyPair1 = generateRsaKeyPair()
        val keyPair2 = generateRsaKeyPair()
        val rawRef = AtomicReference(serviceAccountJson(keyPair1))
        val clockMs = AtomicLong(1_700_000_000_000L)

        repeat(4) { i ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"access_token":"tok-${i + 1}","expires_in":3600}"""),
            )
        }
        val provider = VertexTokenProvider(
            http = http,
            loadServiceAccountJson = { rawRef.get() },
            nowMs = { clockMs.get() },
            tokenUrlOverride = server.url("/token").toString(),
        )

        assertEquals("tok-1", provider.get(false))
        assertEquals(1, server.requestCount)
        server.takeRequest()

        // 61s before expiry still reuses cached token
        clockMs.set(1_700_000_000_000L + 3_600_000L - 61_000L)
        assertEquals("tok-1", provider.get(false))
        assertEquals(1, server.requestCount)

        // Within 60s refresh window triggers proactive refresh
        clockMs.set(1_700_000_000_000L + 3_600_000L - 60_000L)
        assertEquals("tok-2", provider.get(false))
        assertEquals(2, server.requestCount)
        server.takeRequest()

        // Explicit fresh = true bypasses cache
        assertEquals("tok-3", provider.get(true))
        assertEquals(3, server.requestCount)
        server.takeRequest()

        // Rotating SA JSON immediately invalidates old fingerprint even without fresh = true
        val rotatedRaw = serviceAccountJson(keyPair2, clientEmail = "rotated@proj-123.iam.gserviceaccount.com")
        assertNotEquals(VertexServiceAccount.fingerprintSha256(rawRef.get()), VertexServiceAccount.fingerprintSha256(rotatedRaw))
        rawRef.set(rotatedRaw)
        assertEquals("tok-4", provider.get(false))
        assertEquals(4, server.requestCount)

        val rotatedForm = server.takeRequest().body.readUtf8()
        val rotatedAssertion = java.net.URLDecoder.decode(rotatedForm.substringAfter("assertion=").substringBefore('&'), Charsets.UTF_8)
        verifyJwtRs256(
            jwt = rotatedAssertion,
            keyPair = keyPair2,
            expectedIss = "rotated@proj-123.iam.gserviceaccount.com",
            expectedAud = VertexServiceAccount.DEFAULT_TOKEN_URI,
            expectedIat = clockMs.get() / 1000L,
        )
    }

    @Test
    fun secretMaterialNeverLeaksInToStringOrExceptions() {
        val keyPair = generateRsaKeyPair()
        val sa = VertexServiceAccount.parse(serviceAccountJson(keyPair))
        assertFalse(sa.toString().contains("PRIVATE KEY"))
        assertFalse(sa.toString().contains(sa.privateKeyPem.take(30)))

        val secretMarker = "PRIVATE_KEY_MATERIAL_SHOULD_NOT_LEAK_XYZ"
        val malformedJson = """{"type":"service_account","private_key":"$secretMarker", malformed"""
        try {
            VertexServiceAccount.parse(malformedJson)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message.orEmpty().contains(secretMarker))
            assertFalse((e.cause?.message ?: "").contains(secretMarker))
        }

        val badPemJson = """{"type":"service_account","client_email":"sa@p.iam.gserviceaccount.com","private_key":"not-pem-$secretMarker"}"""
        try {
            VertexServiceAccount.parse(badPemJson)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message.orEmpty().contains(secretMarker))
        }

        val invalidDerSa = VertexServiceAccount(
            projectId = "p",
            clientEmail = "sa@p.iam.gserviceaccount.com",
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\n$secretMarker\n-----END PRIVATE KEY-----",
        )
        try {
            VertexAuth.createJwtAssertion(invalidDerSa)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message.orEmpty().contains(secretMarker))
            assertFalse((e.cause?.message ?: "").contains(secretMarker))
        }

        runBlocking {
            val provider = VertexTokenProvider(http, invalidDerSa, tokenUrlOverride = server.url("/token").toString())
            try {
                provider.get(false)
                fail("Expected ModelError.Auth")
            } catch (e: ModelError.Auth) {
                assertFalse(e.message.orEmpty().contains(secretMarker))
            }
        }
    }

    @Test
    fun rejectsNonHttpsTokenUriAndUnsafeEndpointComponents() {
        val keyPair = generateRsaKeyPair()

        for (badUri in listOf(
            "http://oauth2.googleapis.com/token",
            "http://127.0.0.1:8080/token",
            "https://user:secret@oauth2.googleapis.com/token",
            "not-a-uri",
            "",
        )) {
            val raw = serviceAccountJson(keyPair, tokenUri = badUri)
            try {
                VertexServiceAccount.parse(raw)
                fail("Expected non-HTTPS or invalid token_uri to fail: $badUri")
            } catch (_: IllegalArgumentException) {
            }
        }

        assertEquals("global", VertexEndpoints.normalizeLocation(""))
        assertEquals("global", VertexEndpoints.normalizeLocation("   "))
        assertEquals("us-central1", VertexEndpoints.normalizeLocation(" US-CENTRAL1 "))
        assertEquals("https://aiplatform.googleapis.com", VertexEndpoints.host("global"))
        assertEquals("https://europe-west4-aiplatform.googleapis.com", VertexEndpoints.host("europe-west4"))
        assertEquals(
            "https://aiplatform.googleapis.com/v1/publishers/google/models?pageSize=100",
            VertexEndpoints.publisherModelsUrl("global"),
        )
        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1/publishers/google/models?pageSize=100",
            VertexEndpoints.publisherModelsUrl("us-central1"),
        )
        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1/projects/p1/locations/us-central1/publishers/google/models/gemini-4-argon:streamGenerateContent?alt=sse",
            VertexEndpoints.generateContentUrl("p1", "us-central1", "publishers/google/models/gemini-4-argon", stream = true),
        )

        for (badLoc in listOf("../bad", "us central1", "us-central1/extra", "us?x=1")) {
            try {
                VertexEndpoints.normalizeLocation(badLoc)
                fail("Expected invalid location to fail: $badLoc")
            } catch (_: IllegalArgumentException) {
            }
        }
        for (badProj in listOf("", "../proj", "proj/other", "proj id", "proj?a=1")) {
            try {
                VertexEndpoints.validateProjectId(badProj)
                fail("Expected invalid project ID to fail: $badProj")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
