package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.decision.DecisionChoice
import com.wangzan101.mika.core.decision.DecisionModel
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.placeOf
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.testing.BATCHABLE
import com.wangzan101.mika.core.testing.SANDBOX_TOOLS
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.testing.verb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class NamedModel(override val name: String, toolCalls: Boolean = true, vision: Boolean = false) : ChatModel {
    override val capabilities = Capabilities(toolCalls = toolCalls, vision = vision, contextTokens = 64_000, reasoning = false)
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("not called")
}

class ModelRouterTest {
    private val main = NamedModel("main")
    private val light = NamedModel("light")
    private fun clean(step: Int) = StepContext(step, lastOk = true, lastHadGuard = false, lastChanged = true, lastWasRecovery = false, sameAppAsLast = true)

    @Test fun firstStepUsesMain() {
        assertSame(main, ModelRouter(main, light).pick(clean(1)))
    }

    @Test fun lightAfterCleanStep() {
        val r = ModelRouter(main, light)
        assertSame(light, r.pick(clean(2)))
        assertSame(light, r.pick(clean(3)))
        // 读屏一类不报画面变化,不算没变
        assertSame(light, r.pick(clean(4).copy(lastChanged = null)))
    }

    @Test fun eachConditionKeepsMain() {
        val r = ModelRouter(main, light)
        assertSame(main, r.pick(clean(5).copy(lastWasRecovery = true)))
        assertSame(main, r.pick(clean(5).copy(sameAppAsLast = false)))
        // 不看图的轻量模型收不了截图
        assertSame(main, r.pick(clean(5).copy(hasImages = true)))
        val seeing = NamedModel("seeing", vision = true)
        assertSame(seeing, ModelRouter(main, seeing).pick(clean(5).copy(hasImages = true)))
    }

    @Test fun troubleLocksMainForRestOfPhase() {
        val triggers = listOf(
            clean(3).copy(lastOk = false),
            clean(3).copy(lastHadGuard = true),
            clean(3).copy(lastChanged = false),
            clean(3).copy(lastWasRecovery = true),
            clean(3).copy(decisionScore = 0.84),
            clean(3).copy(lastVerb = Verb.Describe, decisionAllowedLight = false),
            clean(3).copy(lastVerb = Verb.Swipe, decisionAllowedLight = false),
        )
        for (trouble in triggers) {
            val r = ModelRouter(main, light)
            assertSame(main, r.pick(trouble))
            // 同阶段内永不降回轻量模型
            assertSame(main, r.pick(clean(4)))
            assertSame(main, r.pick(clean(5)))
            assertSame(main, r.pick(clean(10)))
        }
        val r = ModelRouter(main, light)
        r.noteTrouble()
        assertSame(main, r.pick(clean(6)))
        assertSame(main, r.pick(clean(7)))
        assertSame(main, r.pick(clean(12)))
    }

    @Test fun phaseChangeAndStartNewPhaseResetEscalation() {
        val r = ModelRouter(main, light)
        assertSame(main, r.pick(clean(3).copy(lastOk = false)))
        assertSame(main, r.pick(clean(4)))
        // 切换 App: 进入新阶段并该步先由主模型接手, 下一步恢复轻量模型
        assertSame(main, r.pick(clean(5).copy(sameAppAsLast = false, phaseId = 1)))
        assertSame(light, r.pick(clean(6).copy(phaseId = 1)))

        // 仅 phaseId 推进也进入新阶段
        r.noteTrouble()
        assertSame(main, r.pick(clean(7).copy(phaseId = 1)))
        assertSame(light, r.pick(clean(8).copy(phaseId = 2)))

        // startNewPhase 显式解除阶段锁定
        r.noteTrouble()
        assertSame(main, r.pick(clean(9).copy(phaseId = 2)))
        r.startNewPhase()
        assertSame(light, r.pick(clean(10).copy(phaseId = 2)))
    }

    @Test fun describeAndSwipeAreMainOnlyUnlessDecisionAllowsLight() {
        assertEquals(setOf(Verb.Describe, Verb.Swipe), ModelRouter.DECISION_GATED_MAIN_VERBS)
        assertTrue(ModelRouter.mainOnly("phone_describe", Verb.Describe))
        assertTrue(ModelRouter.mainOnly("phone_describe", Verb.Describe, decisionAllowedLight = false))
        assertFalse(ModelRouter.mainOnly("phone_describe", Verb.Describe, decisionAllowedLight = true))

        assertTrue(ModelRouter.mainOnly("phone_swipe", Verb.Swipe))
        assertTrue(ModelRouter.mainOnly("phone_swipe", Verb.Swipe, decisionAllowedLight = false))
        assertFalse(ModelRouter.mainOnly("phone_swipe", Verb.Swipe, decisionAllowedLight = true))

        // 决策放行不能绕过无条件主模型动作
        assertTrue(ModelRouter.mainOnly("run_command", Verb.RunCommand, decisionAllowedLight = true))

        val r = ModelRouter(main, light)
        assertSame(light, r.pick(clean(3).copy(lastVerb = Verb.Describe, decisionScore = 0.90, decisionAllowedLight = true)))
        assertSame(light, r.pick(clean(4).copy(lastVerb = Verb.Swipe, decisionScore = 0.85, decisionAllowedLight = true)))
    }

    @Test fun stepTrackTracksPhaseIdAndLastVerb() {
        val t = StepTrack()
        t.after(Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.a")
        val c1 = t.context(2, false)
        assertEquals(0, c1.phaseId)
        assertEquals(Verb.Observe, c1.lastVerb)
        assertFalse(c1.sameAppAsLast)

        t.after(Verb.Tap, ok = true, guard = false, changed = true, recovery = false, app = "com.a")
        val c2 = t.context(3, false)
        assertEquals(0, c2.phaseId)
        assertEquals(Verb.Tap, c2.lastVerb)
        assertTrue(c2.sameAppAsLast)

        // 前台 App 变更推动 phaseId
        t.after(Verb.Tap, ok = true, guard = false, changed = true, recovery = false, app = "com.b")
        val c3 = t.context(4, false)
        assertEquals(1, c3.phaseId)
        assertFalse(c3.sameAppAsLast)

        // APP_SWITCH_VERBS 即使在同一 App 字符串下也推动 phaseId
        t.after(Verb.LaunchApp, ok = true, guard = false, changed = true, recovery = false, app = "com.b")
        val c4 = t.context(5, false)
        assertEquals(2, c4.phaseId)
        assertEquals(Verb.LaunchApp, c4.lastVerb)
        assertFalse(c4.sameAppAsLast)
    }

    @Test fun disabledRouterAlwaysMain() {
        val r = ModelRouter(main, light)
        r.disable()
        assertSame(main, r.pick(clean(5)))
        assertFalse(r.active)
    }

    @Test fun markRecoveryMakesNextStepRecovery() {
        val t = StepTrack()
        t.after(verb("phone_observe"), ok = true, guard = false, changed = null, recovery = false, app = "com.x")
        t.after(verb("phone_observe"), ok = true, guard = false, changed = null, recovery = false, app = "com.x")
        assertSame(light, ModelRouter(main, light).pick(t.context(3, hasImages = false)))
        t.markRecovery()
        assertSame(main, ModelRouter(main, light).pick(t.context(3, hasImages = false)))
    }

    @Test fun noToolCapableLightMeansMain() {
        val r = ModelRouter(main, NamedModel("plain", toolCalls = false))
        assertSame(main, r.pick(clean(2)))
        assertSame(main, ModelRouter(main, null).pick(clean(2)))
    }

    @Test fun sandboxToolsAreMainOnlyAndNotBatchable() {
        assertTrue(SANDBOX_TOOLS.all { ModelRouter.mainOnly(it, verb(it)) })
        assertTrue(SANDBOX_TOOLS.none { it in BATCHABLE })
    }
}

class ModelCallerDecisionRoutingTest {
    private val history = listOf(Message(Role.User, listOf(Part.Text("操作屏幕")), Author.User))

    private fun primedTrack(): StepTrack = StepTrack().apply {
        after(Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.x")
        after(Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.x")
    }

    @Test fun decisionAllowsHighConfidenceDescribeAndSwipeWithoutEscalating() = runTest {
        val main = fakeModel("main") {}
        val light = fakeModel("light") {
            call("phone_describe", "task" to "看弹窗", "reason" to "元素少")
            call("phone_swipe", "startX" to 500, "startY" to 800, "endX" to 500, "endY" to 200)
        }
        val events = mutableListOf<RunEvent>()
        val decision = DecisionModel { _, _, options, _ ->
            assertEquals(setOf(ModelCaller.ALLOW_LIGHT, ModelCaller.USE_MAIN), options.keys)
            DecisionChoice(ModelCaller.ALLOW_LIGHT, 0.91)
        }
        val router = ModelRouter(main, light)
        val caller = ModelCaller(
            main, toolDispatcher(FakeScreen()), router, SpeedFlags(lightSteps = true),
            RetryPolicy.NONE, events::add, { 1_000L }, decision = decision,
        ).also { it.reset("") }
        val track = primedTrack()

        val (r1, m1) = caller.routed(history, emptyList(), track, step = 3, restarted = {})
        assertSame(light, m1)
        assertEquals("phone_describe", r1.parts.filterIsInstance<Part.ToolCall>().single().name)
        track.after(Verb.Describe, ok = true, guard = false, changed = null, recovery = false, app = "com.x")

        val (r2, m2) = caller.routed(history, emptyList(), track, step = 4, restarted = {})
        assertSame(light, m2)
        assertEquals("phone_swipe", r2.parts.filterIsInstance<Part.ToolCall>().single().name)
        assertTrue(events.isEmpty())
    }

    @Test fun rejectedGatedCallEmitsScoreDiffAndStateAndLocksPhase() = runTest {
        var now = 1_000L
        val main = fakeModel("main") {
            call("phone_observe")
            answer("done")
        }
        val light = fakeModel("light") {
            reply {
                call("phone_swipe", "startX" to 500, "startY" to 800, "endX" to 500, "endY" to 200)
                usage(120, 20, 80)
            }
        }
        val events = mutableListOf<RunEvent>()
        val decision = DecisionModel { _, _, _, _ ->
            now += 50
            DecisionChoice(ModelCaller.ALLOW_LIGHT, 0.72)
        }
        val router = ModelRouter(main, light)
        val caller = ModelCaller(
            main, toolDispatcher(FakeScreen()), router, SpeedFlags(lightSteps = true),
            RetryPolicy.NONE, events::add, { now }, decision = decision,
        ).also { it.reset("") }
        val track = primedTrack()
        var restartedAt = -1L

        val (reply, used) = caller.routed(history, emptyList(), track, step = 3, restarted = { restartedAt = it })
        assertSame(main, used)
        assertEquals("phone_observe", reply.parts.filterIsInstance<Part.ToolCall>().single().name)
        assertTrue(restartedAt >= 1_000L)

        val rejected = events.filterIsInstance<RunEvent.ModelReply>().single()
        assertEquals(ModelCaller.ROLE_LIGHT_REJECTED, rejected.role)
        assertEquals(0.72, rejected.decisionScore!!, 1e-9)
        assertEquals("tool:phone_swipe->phone_observe", rejected.mainDiff)

        val legacyState = events.filterIsInstance<RunEvent.State>().single { it.name == "light_to_main" }
        assertEquals("phone_swipe", legacyState.detail)
        val detailState = events.filterIsInstance<RunEvent.State>().single { it.name == "light_rejection_detail" }
        assertTrue(detailState.detail, "step=3" in detailState.detail)
        assertTrue(detailState.detail, "tool=phone_swipe" in detailState.detail)
        assertTrue(detailState.detail, "model=light" in detailState.detail)
        assertTrue(detailState.detail, "score=0.72" in detailState.detail)
        assertTrue(detailState.detail, "diff=tool:phone_swipe->phone_observe" in detailState.detail)

        // 阶段锁定: 后续即使一切正常也只走主模型
        track.after(Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.x")
        val (_, used4) = caller.routed(history, emptyList(), track, step = 4, restarted = {})
        assertSame(main, used4)
    }

    @Test fun decisionTimeoutRejectsSwipeAndCancellationPropagates() = runTest {
        val main = fakeModel("main") { call("phone_observe") }
        val light = fakeModel("light") {
            call("phone_swipe", "startX" to 500, "startY" to 800, "endX" to 500, "endY" to 200)
            call("phone_swipe", "startX" to 500, "startY" to 800, "endX" to 500, "endY" to 200)
        }
        val events = mutableListOf<RunEvent>()
        val hanging = DecisionModel { _, _, _, _ -> awaitCancellation() }
        val caller = ModelCaller(
            main, toolDispatcher(FakeScreen()), ModelRouter(main, light), SpeedFlags(lightSteps = true),
            RetryPolicy.NONE, events::add, { 0L }, decision = hanging,
        ).also { it.reset("") }

        val (_, used) = caller.routed(history, emptyList(), primedTrack(), step = 3, restarted = {})
        assertSame(main, used)
        assertNull(events.filterIsInstance<RunEvent.ModelReply>().single().decisionScore)

        val untouchedMain = fakeModel("main") {}
        val cancellingCaller = ModelCaller(
            untouchedMain, toolDispatcher(FakeScreen()), ModelRouter(untouchedMain, light), SpeedFlags(lightSteps = true),
            RetryPolicy.NONE, {}, { 0L }, decision = DecisionModel { _, _, _, _ -> throw CancellationException("stop") },
        ).also { it.reset("") }
        try {
            cancellingCaller.routed(history, emptyList(), primedTrack(), step = 3, restarted = {})
            fail("cancellation was swallowed")
        } catch (_: CancellationException) {
            assertTrue(untouchedMain.requests.isEmpty())
        }
    }

    @Test fun mainDiffCoversErrorNoToolArgsAndSameCall() = runTest {
        suspend fun runCase(lightScript: com.wangzan101.mika.core.testing.FakeScript.() -> Unit,
                            mainScript: com.wangzan101.mika.core.testing.FakeScript.() -> Unit): String {
            val main = fakeModel("main", script = mainScript)
            val light = fakeModel("light", script = lightScript)
            val events = mutableListOf<RunEvent>()
            val caller = ModelCaller(
                main, toolDispatcher(FakeScreen()), ModelRouter(main, light), SpeedFlags(lightSteps = true),
                RetryPolicy.NONE, events::add, { 0L }, decision = null,
            ).also { it.reset("") }
            caller.routed(history, emptyList(), primedTrack(), step = 3, restarted = {})
            return events.filterIsInstance<RunEvent.ModelReply>().single().mainDiff!!
        }

        assertEquals("light_error", runCase({ networkError() }, { call("phone_observe") }))
        assertEquals("no_tool", runCase({ answer("hi") }, { answer("hello") }))
        assertEquals(
            "args:phone_swipe",
            runCase(
                { call("phone_swipe", "startX" to 100, "startY" to 500, "endX" to 900, "endY" to 500) },
                { call("phone_swipe", "startX" to 200, "startY" to 500, "endX" to 800, "endY" to 500) },
            ),
        )
        assertEquals(
            "same_call:phone_swipe",
            runCase(
                { reply { callJson("phone_swipe", """{"startX":100,"startY":500,"endX":900,"endY":500}""") } },
                { reply { callJson("phone_swipe", """{"endY":500,"endX":900,"startY":500,"startX":100}""") } },
            ),
        )
        assertEquals(
            "tool:phone_swipe->no_tool",
            runCase(
                { call("phone_swipe", "startX" to 100, "startY" to 500, "endX" to 900, "endY" to 500) },
                { answer("done") },
            ),
        )
    }
}

/** 「在哪」分手机 App 与网站:换到网页、打开网址都算换了地方。 */
class PlaceTrackingTest {
    @Test fun placeOfSeparatesAppsAndSites() {
        fun o(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject
        org.junit.Assert.assertEquals(Place.app("com.x"), placeOf(o("""{"currentApp":"com.x"}""")))
        org.junit.Assert.assertEquals(Place.site("a.com"), placeOf(o("""{"currentSite":"a.com"}""")))
        org.junit.Assert.assertEquals(Place.site("a.com"), placeOf(o("""{"ok":true,"screen":{"currentSite":"a.com"}}""")))
        org.junit.Assert.assertNull(placeOf(o("""{"ok":true}""")))
    }

    @Test fun navigatingIsASwitch() {
        val t = StepTrack()
        t.after(verb("phone_tap"), true, false, true, false, "网站 a.com")
        t.after(verb("phone_tap"), true, false, true, false, "网站 a.com")
        org.junit.Assert.assertTrue(t.context(3, false).sameAppAsLast)
        t.after(verb("web_navigate"), true, false, true, false, "网站 a.com")
        org.junit.Assert.assertFalse(t.context(4, false).sameAppAsLast)
    }
}
