package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.AppAccessScreenPort
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppAccessHardLockTest {
    @Test
    fun hardLockedDeniesProtectedPackagesAndBlankUnknowns() {
        val state = AppAccessState(hardLocked = DEFAULT_HARD_LOCKED)
        assertTrue("com.google.android.apps.authenticator2" in state.effectiveBlocked)
        assertEquals("app_blocked", state.denial("com.google.android.apps.authenticator2")?.code)
        assertEquals("com.google.android.apps.authenticator2", state.denial("com.google.android.apps.authenticator2")?.packageName)
        assertEquals("app_blocked", state.denial("io.metamask")?.code)
        assertEquals("app_access_unverified", state.denial(null)?.code)
        assertEquals("app_access_unverified", state.denial("  ")?.code)
        assertNull(state.denial("com.example.notes"))
        assertEquals("app_access_unavailable", state.copy(available = false).denial("com.example.notes")?.code)
    }

    @Test
    fun isKnownBankingOrAuthPackageMatchesDefaultsFinanceAndIndicators() {
        assertTrue(isKnownBankingOrAuthPackage("io.metamask", null))
        assertTrue(isKnownBankingOrAuthPackage("com.custom.app", AppClassification(AppCategory.Finance)))
        assertTrue(isKnownBankingOrAuthPackage("uk.co.HSBC.mobile", null))
        assertTrue(isKnownBankingOrAuthPackage("com.binance.dev", null))
        assertTrue(isKnownBankingOrAuthPackage("com.example.mywallet", null))
        assertFalse(isKnownBankingOrAuthPackage("com.example.notes", AppClassification(AppCategory.Productivity)))
        assertFalse(isKnownBankingOrAuthPackage("com.spotify.music", null))
    }

    @Test
    fun screenPortEnforcesHardLockedWhenBlockedIsEmpty() = runTest {
        var reads = 0
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                reads++
                return FakeScreen().snapshot().copy(packageName = "com.google.android.apps.authenticator2")
            }
            override suspend fun screenshotPng(): ByteArray {
                reads++
                return byteArrayOf(1)
            }
        }
        val port = AppAccessScreenPort(
            inner,
            AppAccessPolicy { AppAccessState(hardLocked = DEFAULT_HARD_LOCKED) },
            packages = { listOf("com.google.android.apps.authenticator2") },
        )
        try {
            port.screenshotPng()
            fail("expected AppAccessDenied")
        } catch (e: AppAccessDenied) {
            assertEquals("app_blocked", e.code)
            assertEquals("com.google.android.apps.authenticator2", e.packageName)
        }
        assertEquals(0, reads)
        try {
            port.snapshot()
            fail("expected AppAccessDenied")
        } catch (e: AppAccessDenied) {
            assertEquals("app_blocked", e.code)
            assertEquals("com.google.android.apps.authenticator2", e.packageName)
        }
        try {
            port.openApp("io.metamask")
            fail("expected AppAccessDenied")
        } catch (e: AppAccessDenied) {
            assertEquals("app_blocked", e.code)
            assertEquals("io.metamask", e.packageName)
        }
    }

    @Test
    fun actionExecutorEmitsAppBlockedPackageStateEvent() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }
        val screen = object : ScreenPort by FakeScreen() {
            override val appAccess = AppAccessPolicy { AppAccessState(hardLocked = DEFAULT_HARD_LOCKED) }
            override suspend fun checkAppAccess() {
                throw AppAccessDenied("app_blocked", "io.metamask")
            }
        }
        val executor = ActionExecutor(
            toolDispatcher(screen),
            OperationGuards(),
            confirmations = null,
            gate = {},
            recorder = ActionRecorder(sink),
            autonomous = true,
        )
        val outcome = executor.run(Part.ToolCall("c1", "phone_observe", "{}"), Origin.Model(1))
        assertTrue(outcome is ActionOutcome.Refused)
        assertTrue(events.any { it is RunEvent.State && it.name == "app_blocked_package" && it.detail == "io.metamask" })
    }

    @Test
    fun agentLoopEmitsScreenProtectedPackageStateEvent() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot =
                ScreenSnapshot("s1", "uk.co.hsbc.hsbcukmobilebanking", emptyList(), 1080, 1920, protected = true)
        }
        var calls = 0
        val model = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                calls++
                if (calls == 1) {
                    return ModelReply(listOf(Part.ToolCall("c1", "phone_observe", "{}")), StopReason.ToolUse, null)
                }
                return answerReply("该页面受保护。")
            }
        }
        val end = AgentLoop(model, toolDispatcher(screen), "BASE", 5, sink = sink).run("查看银行页面")
        assertTrue(end is TaskState.Succeeded)
        assertTrue(
            events.any {
                it is RunEvent.State && it.name == "screen_protected_package" &&
                    it.detail == "uk.co.hsbc.hsbcukmobilebanking"
            },
        )
    }
}
