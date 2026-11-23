package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.general.SpawnAgent
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.MemberKey
import com.wangzan101.mika.core.model.ModelNeed
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.run.ChildPhase
import com.wangzan101.mika.core.run.ChildResult
import com.wangzan101.mika.core.run.ChildRunHost
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests covering the v59 -> v60 cost & cache fixes:
 * 1. Light routing on screenshot steps, 1-retry on model_error, 2-step cooldown (never permanent disable), and light_skipped logging.
 * 2. Frozen system prompt and tool definitions across the entire task.
 * 3. Per-role compressor & compaction state, sticky ModelPool selection, 15-step compaction cadence, and strict <= 100k input cap.
 * 4. Spawned agent concise goal truncation and step cap.
 * 5. 45-step phone task benchmark generating usage JSONL and verifying Light/Main ratio, cache rate, compressions, and <= 100k max input.
 */
class V59CostAndCacheFixTest {
    private val seq = AtomicInteger(0)
    private fun call(tool: String, args: String = "{}") = Part.ToolCall("c${seq.incrementAndGet()}", tool, args)
    private fun tools(vararg parts: Part) = ModelReply(parts.toList(), StopReason.ToolUse, null)
    private fun finish(text: String = "done") = ModelReply(listOf(Part.Text(text)), StopReason.EndTurn, null)

    private class NamedFake(
        override val name: String,
        private val inner: FakeModel,
        vision: Boolean = false,
        contextTokens: Int = 200_000,
    ) : ChatModel by inner {
        override val capabilities = inner.capabilities.copy(vision = vision, contextTokens = contextTokens)
        val requests get() = inner.requests
        val systems = mutableListOf<String>()
        val offeredToolNames = mutableListOf<List<String>>()
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            systems += system
            offeredToolNames += tools.map { it.name }
            return inner.complete(system, messages, tools)
        }
    }

    @Test
    fun lightRetriesOnceAndSucceedsWithoutFallingBackToMain() = runTest {
        val main = NamedFake("gemini-4-hydrogen", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()), vision = true)
        val light = NamedFake(
            "gemini-3.8-flash",
            FakeModel(
                ModelError.Server(500), // first attempt fails
                tools(call("phone_observe")), // immediate retry succeeds!
                finish(),
            ),
            vision = true,
        )
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val r = AgentLoop(
            main,
            toolDispatcher(FakeScreen(), visionEnabled = true),
            "BASE",
            flags = SpeedFlags(lightSteps = true),
            router = ModelRouter(main, light),
            sink = sink,
        ).run("t")
        assertTrue("$r", r is TaskState.Succeeded)
        // Step 1: Main; Step 2: Light (1 error + 1 retry success); Step 3: Light returns finish -> rejected -> Main finishes
        assertEquals(4, light.requests.size)
        assertTrue(events.any { it is RunEvent.State && it.name == "light_retry" && it.detail == "model_error" })
        assertTrue(events.any { it is RunEvent.State && it.name == "light_skipped" && "step=1" in it.detail })
    }

    @Test
    fun systemPromptAndToolDefinitionsStayFrozenAcrossEntireTask() = runTest {
        val main = NamedFake(
            "gemini-4-hydrogen",
            FakeModel(
                tools(call("phone_observe")),
                tools(call("phone_tap", """{"x":10,"y":20}""")),
                tools(call("phone_scroll", """{"direction":"down"}""")),
                finish("done"),
            ),
            vision = true,
        )
        val r = AgentLoop(
            main,
            toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = true) }),
            SystemPrompt.identity(),
        ).run("test frozen prefix")
        assertTrue("$r", r is TaskState.Succeeded)
        assertEquals(4, main.systems.size)
        assertEquals(1, main.systems.toSet().size)
        assertEquals(1, main.offeredToolNames.toSet().size)
    }

    @Test
    fun modelPoolStaysStickyOnWorkingMember() = runTest {
        val a = NamedFake("member-a", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val b = NamedFake("member-b", FakeModel(tools(call("phone_observe")), finish()))
        val ma = PoolMember(MemberKey("p1", "a"), a)
        val mb = PoolMember(MemberKey("p2", "b"), b)
        var calls = 0
        val pool = object : ModelPool, ChatModel by a {
            override val groupName = "main-group"
            override val members = listOf(ma, mb)
            override fun ordered(need: ModelNeed): List<PoolMember> = if (calls++ % 2 == 0) listOf(ma, mb) else listOf(mb, ma)
            override fun noteResult(member: PoolMember, latencyMs: Long?, error: Throwable?) {}
            override fun owns(model: ChatModel): Boolean = model === a || model === b
        }
        val r = AgentLoop(pool, toolDispatcher(FakeScreen()), "BASE").run("sticky pool")
        assertTrue("$r", r is TaskState.Succeeded)
        assertEquals(3, a.requests.size)
        assertEquals(0, b.requests.size)
    }

    @Test
    fun spawnAgentCapsStepsAndGoalSummaryLength() = runTest {
        var captured: RunSpec? = null
        val host = ChildRunHost { spec, _ ->
            captured = spec
            ChildResult(spec.id, ChildPhase.Completed, "ok")
        }
        val dispatcher = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "g", "t"), childHost = host)
        val hugeGoal = "a".repeat(3_000)
        val rejected = dispatcher.execute(
            Part.ToolCall("s0", "spawn_agent", """{"goal":"$hugeGoal","maxSteps":130}"""),
            ActionPass(),
        )
        assertEquals("false", rejected.content["ok"].toString())
        val res = dispatcher.execute(
            Part.ToolCall("s1", "spawn_agent", """{"goal":"Concise research summary task","maxSteps":130}"""),
            ActionPass(),
        )
        assertEquals("true", res.content["ok"].toString())
        assertEquals(SpawnAgent.MAX_STEPS, captured!!.stepBudget)
        assertTrue(captured!!.goal.length <= SpawnAgent.MAX_GOAL_CHARS)
    }

    /**
     * Realistic 45-step phone task benchmark with screenshots + rich UI trees.
     * Simulates provider prefix-caching by hashing/matching exact character prefix across consecutive calls per model,
     * emits a real usage JSONL file, and asserts all v60 cost/cache/cap targets.
     */
    @Test
    fun fortyFiveStepPhoneTaskBenchmarkMeetsCostAndCacheTargets() = runTest {
        val totalSteps = 45
        val stepCounter = AtomicInteger(0)
        val baseScreen = FakeScreen().apply { result = ActionResult.Ok(changed = true) }
        val screen = object : ScreenPort by baseScreen {
            override suspend fun snapshot(): ScreenSnapshot {
                val n = stepCounter.incrementAndGet()
                val els = (1..25).map { idx ->
                    UiElement("@e$idx", "button", "Screen $n item $idx " + "content ".repeat(14), 100 + idx * 10, 200 + idx * 20, listOf(0, 0, 100, 50), null, true, false, false)
                }
                return ScreenSnapshot("snap$n", "com.demo.shop", els, 1080, 2400)
            }
        }

        // Prefix-cache-simulating model wrapper that tracks exact wire-prefix overlap across calls.
        class CachingModel(
            override val name: String,
            private val isLight: Boolean,
            private val targetSteps: Int,
            private val sharedStep: AtomicInteger,
        ) : ChatModel {
            override val capabilities = Capabilities(
                toolCalls = true,
                vision = true,
                contextTokens = 200_000,
                reasoning = false,
            )
            private var prevWire: String = ""
            var calls = 0
            val systems = mutableSetOf<String>()
            val toolLists = mutableSetOf<List<String>>()
            val inputTokensList = mutableListOf<Int>()
            val cachedTokensList = mutableListOf<Int>()

            private fun serialize(system: String, messages: List<Message>, tools: List<ToolSpec>): String = buildString {
                append("SYS:").append(system).append("\nTOOLS:")
                tools.forEach { append(it.name).append(':').append(it.description).append(it.parametersJsonSchema).append(';') }
                append("\nMSGS:")
                messages.forEach { m ->
                    append(m.role).append(':')
                    m.parts.forEach { p ->
                        when (p) {
                            is Part.Text -> append("T[").append(p.text).append("]")
                            is Part.Image -> append("IMG[").append(p.width).append("]")
                            is Part.ToolCall -> append("TC[").append(p.id).append(',').append(p.name).append(',').append(p.argsJson).append("]")
                            is Part.ToolResult -> append("TR[").append(p.callId).append(',').append(p.content).append("]")
                            is Part.Thinking -> append("TH[").append(p.text).append("]")
                        }
                    }
                    append('\n')
                }
            }

            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                calls++
                systems += system
                toolLists += tools.map { it.name }
                val wire = serialize(system, messages, tools)
                val totalTokens = TokenEstimate.of(system) +
                    tools.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) } +
                    TokenEstimate.of(messages)
                val commonChars = if (prevWire.isEmpty()) 0 else prevWire.commonPrefixWith(wire).length
                val cached = if (wire.isEmpty()) 0 else ((commonChars.toDouble() / wire.length) * totalTokens).toInt().coerceAtMost(totalTokens)
                prevWire = wire
                inputTokensList += totalTokens
                cachedTokensList += cached

                val cur = sharedStep.incrementAndGet()
                val usage = Usage(inputTokens = totalTokens, outputTokens = 45, cachedInputTokens = cached)
                if (cur > targetSteps) {
                    return ModelReply(listOf(Part.Text("Completed all $targetSteps phone steps.")), StopReason.EndTurn, usage)
                }
                val toolCall = when (cur % 3) {
                    1 -> call("phone_observe")
                    2 -> call("phone_tap", """{"ref":"@e${(cur % 20) + 1}","snapshotId":"snap${stepCounter.get()}"}""")
                    else -> call("phone_scroll", """{"direction":"down"}""")
                }
                return ModelReply(listOf(toolCall), StopReason.ToolUse, usage)
            }
        }

        val sharedStep = AtomicInteger(0)
        val main = CachingModel("gemini-4-hydrogen", isLight = false, targetSteps = totalSteps, sharedStep = sharedStep)
        val light = CachingModel("gemini-3.8-flash", isLight = true, targetSteps = totalSteps, sharedStep = sharedStep)
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }

        val loop = AgentLoop(
            model = main,
            tools = toolDispatcher(screen, visionEnabled = true),
            basePrompt = SystemPrompt.identity(),
            maxSteps = 50,
            flags = SpeedFlags(lightSteps = true),
            router = ModelRouter(main, light),
            sink = sink,
        )
        val outcome = loop.run("Browse 45 screens in the shopping app and summarize")
        assertTrue("Outcome was $outcome", outcome is TaskState.Succeeded)

        val replies = events.filterIsInstance<RunEvent.ModelReply>()
        val compressions = events.filterIsInstance<RunEvent.Compressed>()
        val lightCalls = replies.count { it.role == ModelCaller.ROLE_LIGHT }
        val mainCalls = replies.count { it.role == ModelCaller.ROLE_MAIN }
        val rejectedCalls = replies.count { it.role == ModelCaller.ROLE_LIGHT_REJECTED }
        val totalCalls = replies.size
        val totalIn = replies.sumOf { (it.inputTokens ?: 0).toLong() }
        val totalCached = replies.sumOf { (it.cachedTokens ?: 0).toLong() }
        val totalOut = replies.sumOf { (it.outputTokens ?: 0).toLong() }
        val maxIn = replies.maxOf { it.inputTokens ?: 0 }
        val cacheRate = if (totalIn > 0) totalCached.toDouble() / totalIn else 0.0
        val lightRate = lightCalls.toDouble() / totalCalls
        val mainRate = mainCalls.toDouble() / totalCalls

        // Write usage JSONL for inspection
        val jsonlFile = File("/tmp/mika-v60-benchmark-45steps.jsonl")
        jsonlFile.writeText(
            replies.joinToString("\n") { r ->
                """{"step":${r.step},"role":"${r.role}","model":"${r.model}","tool":"${r.tool}","inputTokens":${r.inputTokens ?: 0},"cachedTokens":${r.cachedTokens ?: 0},"outputTokens":${r.outputTokens ?: 0}}"""
            } + "\n"
        )

        // Estimated USD cost (Hydrogen: $2.00/M uncached in, $0.20/M cached in, $12.00/M out; Flash: $0.15/M uncached in, $0.015/M cached in, $0.60/M out)
        var estCostUsd = 0.0
        for (r in replies) {
            val inp = (r.inputTokens ?: 0).toDouble()
            val cac = (r.cachedTokens ?: 0).toDouble()
            val unc = (inp - cac).coerceAtLeast(0.0)
            val out = (r.outputTokens ?: 0).toDouble()
            val isMain = r.role == ModelCaller.ROLE_MAIN
            estCostUsd += if (isMain) {
                (unc * 2.00 + cac * 0.20 + out * 12.00) / 1_000_000.0
            } else {
                (unc * 0.15 + cac * 0.015 + out * 0.60) / 1_000_000.0
            }
        }

        println(
            "V60_BENCHMARK_METRICS: steps=$totalSteps totalCalls=$totalCalls lightCalls=$lightCalls " +
                "mainCalls=$mainCalls rejectedCalls=$rejectedCalls " +
                "lightRate=${"%.1f".format(lightRate * 100)}% mainRate=${"%.1f".format(mainRate * 100)}% " +
                "totalInput=$totalIn totalCached=$totalCached totalOut=$totalOut " +
                "cacheRate=${"%.1f".format(cacheRate * 100)}% compressions=${compressions.size} " +
                "foldedSteps=${compressions.map { it.foldedSteps }} maxInput=$maxIn " +
                "estCostUsd=${"%.4f".format(estCostUsd)} jsonl=${jsonlFile.absolutePath}"
        )

        assertEquals("System prompt must stay frozen on main", 1, main.systems.size)
        assertEquals("System prompt must stay frozen on light", 1, light.systems.size)
        assertEquals("Tool definitions must stay frozen on light", 1, light.toolLists.size)
        assertTrue("Light call rate should be >= 80%, was ${lightRate * 100}%", lightRate >= 0.80)
        assertTrue("Cache hit rate should be >= 70%, was ${cacheRate * 100}%", cacheRate >= 0.70)
        assertTrue("Compressed events should be <= 4, was ${compressions.size}", compressions.size <= 4)
        assertTrue("Max input tokens must be <= 100000, was $maxIn", maxIn <= ContextLimits.MAX_INPUT_TOKENS)
    }
}
