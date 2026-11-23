package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.tool.placeOf
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GroupMember
import com.wangzan101.mika.core.model.MemberKey
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.BATCHABLE
import com.wangzan101.mika.core.testing.verb
import com.wangzan101.mika.core.testing.SANDBOX_TOOLS

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

    @Test fun lightGroupPoolIsRecognizedAndActive() {
        val tool = NamedModel("tool")
        val plain = NamedModel("plain", toolCalls = false)
        val g = ModelGroup("g", "轻量组", members = listOf(GroupMember("a"), GroupMember("b")))
        val pool = GroupPool(
            g,
            listOf(PoolMember(MemberKey("p", "tool"), tool), PoolMember(MemberKey("p", "plain"), plain, priority = 1)),
            ModelHealthRegistry(), StickyTable(), null,
        )
        val r = ModelRouter(main, pool)
        // 只要有一个成员支持工具就启用 lightSteps;具体到需要工具的步再按成员过滤
        assertTrue(r.active)
        assertSame(pool, r.pick(clean(2)))
        assertTrue(r.isLight(pool))
        assertEquals("轻量组", r.groupOf(pool))
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
        // 带截图的步骤同样可以走轻量模型(不看图时由 ModelCaller.viewFor 剥离图片、按 UI 树文本执行)
        assertSame(light, r.pick(clean(5).copy(hasImages = true)))
        val seeing = NamedModel("seeing", vision = true)
        assertSame(seeing, ModelRouter(main, seeing).pick(clean(5).copy(hasImages = true)))
    }

    @Test fun troubleForcesMainForTwoSteps() {
        for (trouble in listOf(clean(3).copy(lastOk = false), clean(3).copy(lastHadGuard = true), clean(3).copy(lastChanged = false))) {
            val r = ModelRouter(main, light)
            assertSame(main, r.pick(trouble))
            assertSame(main, r.pick(clean(4)))
            assertSame(light, r.pick(clean(5)))
        }
        val r = ModelRouter(main, light)
        r.noteTrouble()
        assertSame(main, r.pick(clean(6)))
        assertSame(main, r.pick(clean(7)))
        assertSame(light, r.pick(clean(8)))
    }

    @Test fun disabledRouterCoolsDownForTwoStepsThenResumes() {
        val r = ModelRouter(main, light)
        r.disable()
        assertTrue(r.active)
        assertSame(main, r.pick(clean(5)))
        assertSame(main, r.pick(clean(6)))
        assertSame(light, r.pick(clean(7)))
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

    @Test fun allTwelveLightAppropriateToolsAreEligibleForLight() {
        val lightTools = listOf(
            "phone_tap",
            "phone_scroll",
            "phone_swipe",
            "phone_screenshot",
            "phone_type_text",
            "phone_launch_app",
            "phone_observe",
            "phone_describe",
            "view_past_screens",
            "history_read",
            "task_memory_step",
            "web_read_page",
        )
        for (name in lightTools) {
            org.junit.Assert.assertFalse("Expected $name to be Light-eligible", ModelRouter.mainOnly(name, verb(name)))
        }
    }

    @Test fun upfrontRoutingKeepsMainForAskUserSystemCommandRunCommandAndFinalSummary() {
        val r = ModelRouter(main, light)
        assertSame(main, r.pick(clean(3).copy(needsMainUpfront = true)))
        assertSame(main, r.pick(clean(3).copy(finalSummary = true)))
        assertSame(main, r.pick(clean(3).copy(lastVerb = com.wangzan101.mika.core.tool.Verb.AskUser)))
        assertSame(main, r.pick(clean(3).copy(lastVerb = com.wangzan101.mika.core.tool.Verb.PhoneCommand)))
        assertSame(main, r.pick(clean(3).copy(lastVerb = com.wangzan101.mika.core.tool.Verb.RunCommand)))
        assertSame(main, r.pick(clean(3).copy(requestedTool = "ask_user")))
        assertSame(main, r.pick(clean(3).copy(requestedTool = "phone_system_command")))
        assertSame(main, r.pick(clean(3).copy(requestedTool = "system_command")))
        assertSame(main, r.pick(clean(3).copy(requestedTool = "run_command")))

        val observeSpec = ToolSpec("phone_observe", "obs", "{}")
        val endSpec = ToolSpec("end_not_done", "end", "{}")
        val userMsg = Message(
            com.wangzan101.mika.core.model.Role.User,
            listOf(com.wangzan101.mika.core.model.Part.Text("go")),
            com.wangzan101.mika.core.model.Author.User,
        )
        assertTrue(r.shouldRouteToMainUpfront(listOf(userMsg), listOf(observeSpec), ::verb, finalSummary = true))
        assertTrue(r.shouldRouteToMainUpfront(listOf(userMsg), emptyList(), ::verb))
        assertTrue(r.shouldRouteToMainUpfront(listOf(userMsg), listOf(endSpec), ::verb))

        for (privileged in listOf("ask_user", "phone_system_command", "system_command", "run_command")) {
            val historyAfterPrivileged = listOf(
                userMsg,
                Message(
                    com.wangzan101.mika.core.model.Role.Assistant,
                    listOf(com.wangzan101.mika.core.model.Part.ToolCall("c1", privileged, "{}")),
                    com.wangzan101.mika.core.model.Author.Mika,
                ),
                Message(
                    com.wangzan101.mika.core.model.Role.Tool,
                    listOf(com.wangzan101.mika.core.model.Part.ToolResult("c1", """{"ok":true}""", isError = false)),
                    com.wangzan101.mika.core.model.Author.Mika,
                ),
            )
            assertTrue("Expected upfront Main after $privileged", r.shouldRouteToMainUpfront(historyAfterPrivileged, listOf(observeSpec), ::verb))
        }
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
