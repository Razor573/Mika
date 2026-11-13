package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.oauth.AntigravityAuth
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/**
 * Antigravity Google PKCE loopback sign-in flow: opens authorize URL, completes loopback callback,
 * discovers Cloud Code Assist projectId, collapses listed Gemini models to bare families, and signs out cleanly.
 */
class AntigravitySignInTest {
    @get:Rule val texts = ChineseTexts()
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val listCalls = mutableListOf<Pair<String, String?>>()

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun store(): ProviderStore {
        val base = server.url("/").toString().trimEnd('/')
        return ProviderStore(
            tmp.root,
            AesGcmKeyCipher { key },
            antigravityAuth = AntigravityAuth(
                http = http,
                authorizeUrl = "$base/auth",
                tokenUrl = "$base/token",
                userinfoUrl = "$base/userinfo",
                prodEndpoint = base,
                dailyEndpoint = base,
            ),
            modelLister = { _, url, token, _ ->
                synchronized(listCalls) { listCalls += url to token }
                ModelListResult.Ok(
                    listOf(
                        ListedModel("gemini-4-hydrogen-medium"),
                        ListedModel("gemini-4-argon-high"),
                        ListedModel("gemini-3.8-flash-tiered"),
                        ListedModel("claude-sonnet-4-6"),
                        ListedModel("gemini-4-carbon"),
                    ),
                )
            },
        )
    }

    private inner class Browser(private val approve: Boolean = true) : SignInBrowser {
        val opened = mutableListOf<String>()
        val pages = mutableListOf<String?>()

        override fun open(url: String) {
            opened += url
            val u = url.toHttpUrl()
            val callback = u.queryParameter("redirect_uri")!!.toHttpUrl().newBuilder().host("127.0.0.1")
            if (approve) {
                callback.addQueryParameter("code", "ag-code").addQueryParameter("state", u.queryParameter("state"))
            } else {
                callback.addQueryParameter("error", "access_denied").addQueryParameter("state", u.queryParameter("state"))
            }
            Thread { http.newCall(Request.Builder().url(callback.build()).build()).execute().close() }.start()
        }

        override fun returnToApp() {}

        override fun page(failure: String?): String {
            pages += failure
            return "<p>ok</p>"
        }
    }

    private fun ModelSetupViewModel.signInAndWait(provider: AccountProvider) = runBlocking {
        signInWithAccount(provider)
        withTimeout(15_000) { state.first { !it.accountSignIn.running && (it.signedInProvider != null || it.accountSignIn.failure != null) } }
    }

    @Test fun signInStoresEncryptedSessionProjectIdAndBareFamilyModels() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/token" -> MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"access_token":"ag-access-SECRET","refresh_token":"ag-refresh-SECRET","expires_in":3600,"token_type":"Bearer"}""",
                )
                "/userinfo" -> MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"sub":"sub-ag-1","email":"ag@example.com"}""",
                )
                "/v1internal:loadCodeAssist" -> MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"cloudaicompanionProject":"proj-ag-99"}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val s = store()
        val browser = Browser(approve = true)
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = browser, signIns = ChatGptSignIns(scope))
        val ui = vm.signInAndWait(AccountProvider.Antigravity)

        val p = s.antigravityProvider()!!
        assertEquals(p.id, ui.signedInProvider)
        assertEquals(ProviderConfig.Kind.Antigravity, p.kind)
        assertEquals(AntigravityAuth.ACCOUNT_NAME, p.name)
        assertEquals(
            listOf("gemini-4-hydrogen", "gemini-4-argon", "gemini-3.8-flash"),
            p.models!!.map { it.id },
        )
        assertEquals("gemini-4-argon", s.configFor(ModelRole.Main)!!.model)
        val session = s.accountSession(p.id)!!
        assertEquals("ag@example.com", session.label)
        assertEquals("proj-ag-99", session.projectId)
        assertEquals("ag-access-SECRET", session.accessToken)
        assertTrue(ui.antigravityAccount!!.signedIn)
        assertEquals(listOf<String?>(null), browser.pages)
        assertTrue(browser.opened.single().contains("code_challenge_method=S256"))
        tmp.root.walk().filter(File::isFile).forEach { f -> assertFalse(f.name, f.readText().contains("SECRET")) }

        val reloaded = store()
        assertEquals(p.id, reloaded.antigravityProvider()?.id)
        assertEquals("proj-ag-99", reloaded.accountSession(p.id)?.projectId)

        runBlocking {
            vm.signOutAccount(p.id)
            withTimeout(5_000) { vm.state.first { it.antigravityAccount?.signedIn == false } }
        }
        assertFalse(s.accountSession(p.id)!!.signedIn)
        assertNotNull(s.provider(p.id)?.models)
        assertEquals("", s.accountSession(p.id)?.projectId)
        assertEquals("ag@example.com", s.accountSession(p.id)?.label)
        assertTrue(vm.state.value.signedOut(s.provider(p.id)!!))
    }

    @Test fun deniedSignInSavesNothingAndReportsFailure() {
        val s = store()
        val browser = Browser(approve = false)
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = browser, signIns = ChatGptSignIns(scope))
        val ui = vm.signInAndWait(AccountProvider.Antigravity)
        assertEquals(AccountProvider.Antigravity, ui.accountSignIn.provider)
        assertEquals("已取消登录。", ui.accountSignIn.failure)
        assertEquals(listOf<String?>("已取消登录。"), browser.pages)
        assertTrue(s.providers.value.isEmpty())
        assertEquals(0, server.requestCount)
    }
}
