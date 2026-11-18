package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.decision.DecisionChoice
import com.wangzan101.mika.core.decision.DecisionModel
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.RefStabilizer
import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.uistate.ScreenJson
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSession
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.Surface
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiTreeJson
import com.wangzan101.mika.core.uistate.UiTreeNode
import com.wangzan101.mika.core.uistate.UiWindowTree
import com.wangzan101.mika.core.visual.Shots
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset
import java.util.Locale

class CacheCostLeakRegressionTest {

    @Test
    fun sameScreenTwiceProducesByteIdenticalPrunedJsonAndReusesTree() = runTest {
        fun buildSnap(
            snapshotId: String,
            refs: List<String>,
            reverseChildren: Boolean,
            reverseWindows: Boolean,
        ): ScreenSnapshot {
            val rowNodes = listOf(
                UiTreeNode(
                    "FrameLayout",
                    listOf(20, 120, 960, 240),
                    children = listOf(
                        UiTreeNode(
                            "Button",
                            listOf(20, 120, 960, 240),
                            ref = refs[0],
                            text = "Wireless Speaker Alpha",
                            resourceId = "com.shop.app:id/item_alpha",
                            clickable = true,
                        ),
                    ),
                ),
                UiTreeNode(
                    "LinearLayout",
                    listOf(20, 260, 960, 380),
                    children = listOf(
                        UiTreeNode(
                            "Button",
                            listOf(20, 260, 960, 380),
                            ref = refs[1],
                            text = "Portable Monitor Beta",
                            resourceId = "com.shop.app:id/item_beta",
                            clickable = true,
                        ),
                    ),
                ),
                UiTreeNode(
                    "Button",
                    listOf(20, 400, 960, 520),
                    ref = refs[2],
                    text = "Mechanical Keyboard Gamma",
                    resourceId = "com.shop.app:id/item_gamma",
                    clickable = true,
                ),
                UiTreeNode(
                    "Button",
                    listOf(20, 540, 960, 660),
                    ref = refs[3],
                    text = "Desk Lamp Delta",
                    resourceId = "com.shop.app:id/item_delta",
                    clickable = true,
                ),
                // Zero-area and offscreen junk pruned out in compact mode.
                UiTreeNode("View", listOf(10, 10, 10, 50), text = "ZeroAreaJunk"),
                UiTreeNode("TextView", listOf(1050, 100, 1200, 160), text = "OffscreenJunk"),
            )
            val orderedRows = if (reverseChildren) rowNodes.reversed() else rowNodes
            val mainRoot = UiTreeNode(
                "RecyclerView",
                listOf(0, 100, 999, 900),
                scrollable = true,
                resourceId = "com.shop.app:id/list",
                children = orderedRows,
            )
            val mainWin = UiWindowTree(
                id = 1,
                type = "application",
                layer = 0,
                title = "Catalog",
                packageName = "com.shop.app",
                active = true,
                focused = true,
                nodes = listOf(mainRoot),
            )
            val overlayChildren = listOf(
                UiTreeNode(
                    "Button",
                    listOf(40, 910, 480, 980),
                    ref = refs[4],
                    text = "Filter Options",
                    resourceId = "com.shop.app:id/filter",
                    clickable = true,
                ),
                UiTreeNode(
                    "Button",
                    listOf(520, 910, 960, 980),
                    ref = refs[5],
                    text = "Category Menu",
                    resourceId = "com.shop.app:id/category",
                    clickable = true,
                ),
            )
            val overlayWin = UiWindowTree(
                id = 2,
                type = "system",
                layer = 1,
                title = "Overlay",
                packageName = "com.shop.app",
                active = false,
                focused = false,
                nodes = if (reverseChildren) overlayChildren.reversed() else overlayChildren,
            )
            val rawElements = listOf(
                UiElement(refs[0], "Button", "Wireless Speaker Alpha", 490, 180, listOf(20, 120, 960, 240), "com.shop.app:id/item_alpha", true, false, false),
                UiElement(refs[1], "Button", "Portable Monitor Beta", 490, 320, listOf(20, 260, 960, 380), "com.shop.app:id/item_beta", true, false, false),
                UiElement(refs[2], "Button", "Mechanical Keyboard Gamma", 490, 460, listOf(20, 400, 960, 520), "com.shop.app:id/item_gamma", true, false, false),
                UiElement(refs[3], "Button", "Desk Lamp Delta", 490, 600, listOf(20, 540, 960, 660), "com.shop.app:id/item_delta", true, false, false),
                UiElement(refs[4], "Button", "Filter Options", 260, 945, listOf(40, 910, 480, 980), "com.shop.app:id/filter", true, false, false),
                UiElement(refs[5], "Button", "Category Menu", 740, 945, listOf(520, 910, 960, 980), "com.shop.app:id/category", true, false, false),
            )
            return ScreenSnapshot(
                snapshotId = snapshotId,
                packageName = "com.shop.app",
                elements = if (reverseChildren) rawElements.reversed() else rawElements,
                width = 1080,
                height = 2400,
                windows = if (reverseWindows) listOf(overlayWin, mainWin) else listOf(mainWin, overlayWin),
            )
        }

        val refsA = listOf("@raw1", "@raw2", "@raw3", "@raw4", "@raw5", "@raw6")
        val refsB = listOf("@alt91", "@alt92", "@alt93", "@alt94", "@alt95", "@alt96")
        val snapA = buildSnap("raw-snap-100", refsA, reverseChildren = false, reverseWindows = false)
        val snapBReordered = buildSnap("raw-snap-200", refsB, reverseChildren = true, reverseWindows = true)

        assertEquals(UiTreeJson.canonicalHash(snapA), UiTreeJson.canonicalHash(snapBReordered))

        // Direct prunedWindows / ScreenJson determinism with stabilized refs.
        val stabilizer = RefStabilizer("p")
        val stabilizedA = stabilizer.next(snapA)
        val stabilizedB = stabilizer.reuse(snapBReordered)
        assertEquals(
            stabilizedA.elements.sortedBy { it.name }.map { it.ref },
            stabilizedB.elements.sortedBy { it.name }.map { it.ref },
        )
        val prunedJsonA = UiTreeJson.prunedWindows(stabilizedA.screen).windows.toString()
        val prunedJsonB = UiTreeJson.prunedWindows(stabilizedB.screen).windows.toString()
        assertEquals(prunedJsonA, prunedJsonB)
        assertEquals(
            ScreenJson.elements(stabilizedA.screen, 250).first.toString(),
            ScreenJson.elements(stabilizedB.screen, 250).first.toString(),
        )
        assertFalse(prunedJsonA.contains("timestamp"))
        assertFalse(prunedJsonA.contains("step"))
        assertFalse(prunedJsonA.contains("raw-snap"))
        assertFalse(prunedJsonA.contains("FrameLayout"))
        assertFalse(prunedJsonA.contains("ZeroAreaJunk"))
        assertFalse(prunedJsonA.contains("OffscreenJunk"))

        // ScreenSession backed by FakeScreen returning identical UI (same order then reordered, differing raw refs and snapshotIds).
        val snapASameOrderDiffRefs = buildSnap("raw-snap-150", refsB, reverseChildren = false, reverseWindows = false)
        val scriptedSnapshots = ArrayDeque(listOf(snapA, snapASameOrderDiffRefs, snapBReordered))
        val baseFake = FakeScreen()
        val port = object : ScreenPort by baseFake {
            override suspend fun snapshot(): ScreenSnapshot = scriptedSnapshots.removeFirst()
            override suspend fun peek(): ScreenSnapshot = snapA
        }
        val session = ScreenSession(
            surface = Surface(ScreenFamily("phone", "p"), port, declaredTarget = { it }, targetNote = "package"),
            stableRefs = true,
            visionEnabled = false,
            recordFrames = false,
            lastRead = { null },
            shots = Shots(),
            compactUiTree = true,
        )

        val obs1 = session.observe(withImage = false)
        val obs2 = session.observe(withImage = false)
        val obs3 = session.observe(withImage = false)

        assertEquals("p1", obs1.content["snapshotId"]!!.jsonPrimitive.content)
        assertEquals("p2", obs2.content["snapshotId"]!!.jsonPrimitive.content)
        assertEquals("p3", obs3.content["snapshotId"]!!.jsonPrimitive.content)

        // Exact cached object reuse when ScreenSession encounters the same canonical UI again.
        assertSame(obs1.content["tree"], obs2.content["tree"])
        assertSame(obs1.content["elements"], obs2.content["elements"])

        // Byte-identical JSON even when windows, children, raw refs, and snapshotId differ.
        assertEquals(obs1.content["tree"].toString(), obs3.content["tree"].toString())
        assertEquals(obs1.content["elements"].toString(), obs3.content["elements"].toString())

        val refsByName1 = obs1.content["elements"]!!.jsonArray.associate {
            val o = it.jsonObject
            o["name"]!!.jsonPrimitive.content to o["ref"]!!.jsonPrimitive.content
        }
        val refsByName2 = obs2.content["elements"]!!.jsonArray.associate {
            val o = it.jsonObject
            o["name"]!!.jsonPrimitive.content to o["ref"]!!.jsonPrimitive.content
        }
        val refsByName3 = obs3.content["elements"]!!.jsonArray.associate {
            val o = it.jsonObject
            o["name"]!!.jsonPrimitive.content to o["ref"]!!.jsonPrimitive.content
        }
        assertEquals(refsByName1, refsByName2)
        assertEquals(refsByName1, refsByName3)
    }

    @Test
    fun cachePrefixHashStaysUnchangedWhenOnlyLastObservationChanges() {
        val builder = ContextBuilder(zone = ZoneOffset.UTC)
        val systemPrompt = SystemPrompt.identity()
        val specs = toolDispatcher(FakeScreen()).offeredNow()

        val prefix = listOf(
            Message(Role.User, listOf(Part.Text("Inspect the catalog screen and report changes")), Author.User),
            Message(Role.Assistant, listOf(Part.ToolCall("c1", "phone_observe", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c1", """{"ok":true,"snapshotId":"p1","elements":[]}""", false)), Author.Mika),
            Message(Role.Assistant, listOf(Part.ToolCall("c2", "phone_observe", "{}")), Author.Mika),
        )

        val obsA = Message(
            Role.Tool,
            listOf(
                Part.ToolResult("c2", """{"ok":true,"snapshotId":"p2","elements":[{"ref":"@e1","name":"Alpha"}]}""", false),
                builder.runtimeState(Place.app("com.shop.app"), "Shop", null, step = 3, maxSteps = 60, nowMs = 1_700_000_000_000L),
            ),
            Author.Mika,
        )
        val obsB = Message(
            Role.Tool,
            listOf(
                Part.ToolResult("c2", """{"ok":true,"snapshotId":"p3","elements":[{"ref":"@e1","name":"Beta"},{"ref":"@e2","name":"Gamma"}]}""", false),
                builder.runtimeState(Place.app("com.shop.app"), "Shop", null, step = 4, maxSteps = 60, nowMs = 1_700_000_120_000L),
            ),
            Author.Mika,
        )

        val msgsA = prefix + obsA
        val msgsB = prefix + obsB
        val hashA = ContextBuilder.cachePrefixHash(systemPrompt, specs, msgsA)
        val hashB = ContextBuilder.cachePrefixHash(systemPrompt, specs, msgsB)
        assertEquals(hashA, hashB)

        val hashAWithNote = ContextBuilder.cachePrefixHash(
            systemPrompt,
            specs,
            msgsA + Message(Role.User, listOf(Part.Text("trailing follow-up note")), Author.Mika),
        )
        assertEquals(hashA, hashAWithNote)

        // Mutating system prompt, tools, or earlier prefix must invalidate the prefix hash.
        assertNotEquals(hashA, ContextBuilder.cachePrefixHash(systemPrompt + "\nextra", specs, msgsA))
        assertNotEquals(hashA, ContextBuilder.cachePrefixHash(systemPrompt, specs.drop(1), msgsA))
        val mutatedPrefix = prefix.mapIndexed { idx, m ->
            if (idx == 2) Message(Role.Tool, listOf(Part.ToolResult("c1", """{"ok":true,"snapshotId":"p99","elements":[]}""", false)), Author.Mika) else m
        }
        assertNotEquals(hashA, ContextBuilder.cachePrefixHash(systemPrompt, specs, mutatedPrefix + obsA))
    }

    @Test
    fun midTaskRepliesAreToolOnlyWithoutProseAndCappedAt200Tokens() = runTest {
        val prose4910Tokens = "abcd".repeat(4_910)
        assertEquals(4_910, TokenEstimate.of(prose4910Tokens))
        val oversizedArgs = """{"reason":"${"verbose diagnostic reasoning ".repeat(80)}"}"""
        assertTrue(TokenEstimate.of(oversizedArgs) > AgentLoop.MAX_TOOL_CALL_OUTPUT_TOKENS)

        val longSummary = "Checked the current screen and confirmed that the primary navigation controls are visible and ready for user action. ".repeat(14)
        assertTrue(TokenEstimate.of(longSummary) > 300)

        val model = FakeModel(
            ModelReply(listOf(Part.Text(prose4910Tokens), Part.ToolCall("c1", "phone_observe", oversizedArgs)), StopReason.ToolUse, null),
            ModelReply(listOf(Part.Text(longSummary)), StopReason.EndTurn, null),
        )
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }

        val result = AgentLoop(
            model = model,
            tools = toolDispatcher(FakeScreen()),
            basePrompt = "BASE",
            sink = sink,
            zone = ZoneOffset.UTC,
        ).run("Inspect the current screen and provide a detailed summary.")

        assertTrue("$result", result is TaskState.Succeeded)
        result as TaskState.Succeeded
        assertEquals(longSummary, result.answer)
        assertTrue(TokenEstimate.of(result.answer) > 300)

        assertEquals(2, model.requests.size)
        val secondReq = model.requests[1]
        val midAssistant = secondReq.single { it.role == Role.Assistant }
        assertTrue("Expected zero Part.Text in mid-task assistant message", midAssistant.parts.none { it is Part.Text })
        assertTrue("Expected ToolCall in mid-task assistant message", midAssistant.parts.any { it is Part.ToolCall })
        val assistantTokens = TokenEstimate.of(listOf(midAssistant))
        assertTrue("Expected <= ${AgentLoop.MAX_TOOL_CALL_OUTPUT_TOKENS} tokens, got $assistantTokens", assistantTokens <= AgentLoop.MAX_TOOL_CALL_OUTPUT_TOKENS)

        assertTrue(events.any { it is RunEvent.State && it.name == "tool_output_capped" })
        assertTrue(
            "Expected tool_only_cap notice in next request",
            secondReq.any { ContextBuilder.kindOf(it) == ContextKind.Notice && ContextBuilder.sourceOf(it) == "tool_only_cap" },
        )

        // Verify oversized non-verbose JSON args are also clipped to valid JSON within 200 tokens.
        val cappedNonVerbose = AgentLoop.capToolCallParts(
            listOf(
                Part.Text("extra prose"),
                Part.ToolCall("c2", "phone_type_text", """{"x":500,"y":500,"text":"${"payload ".repeat(200)}"}"""),
            ),
            text = "extra prose",
        )
        assertTrue(cappedNonVerbose.none { it is Part.Text })
        val trimmedCall = cappedNonVerbose.filterIsInstance<Part.ToolCall>().single()
        assertTrue(Json.parseToJsonElement(trimmedCall.argsJson) is JsonObject)
        assertTrue(TokenEstimate.of(listOf(Message(Role.Assistant, cappedNonVerbose, Author.Mika))) <= AgentLoop.MAX_TOOL_CALL_OUTPUT_TOKENS)
    }

    @Test
    fun fortyStepSyntheticPhoneRunStaysUnder80kAndMeetsAllRegressionTargets() = runTest {
        val screen = MultiAppDenseScreen()
        val callLogs = mutableListOf<CallUsageLog>()

        val mainModel = BenchmarkChatModel("main", callLogs)
        val lightModel = BenchmarkChatModel("light", callLogs)
        val router = ModelRouter(mainModel, lightModel)
        var decisionCalls = 0
        val intentDecision = DecisionModel { _, _, _, _ ->
            decisionCalls++
            DecisionChoice(ModelCaller.ALLOW_LIGHT, 0.93)
        }

        val events = mutableListOf<RunEvent>()
        val metricsBuilder = RunMetrics.Builder("bench-v54")
        val sink = object : RunSink {
            override fun event(e: RunEvent) {
                events += e
                metricsBuilder.add(e)
            }
        }

        var nowMs = 1_700_000_000_000L
        val priorSummary = "Reviewed earlier items and navigation layout across mobile screens. ".repeat(140)
        val priorTurns = listOf(Turn("Check earlier mobile screens", priorSummary, "succeeded", groups = listOf("phone")))
        val observedContext = "Recent notification digest across shopping, inbox, and notes channels for background awareness. ".repeat(110)

        val result = AgentLoop(
            model = mainModel,
            tools = toolDispatcher(screen, compactUiTree = true),
            basePrompt = SystemPrompt.identity(),
            maxSteps = 50,
            sink = sink,
            flags = SpeedFlags(lightSteps = true, structuredCompaction = true),
            clock = { nowMs += 100L; nowMs },
            router = router,
            intentDecision = intentDecision,
            skills = GuideCatalog.loadBundled(),
            zone = ZoneOffset.UTC,
            retry = RetryPolicy.NONE,
            observed = observedContext,
        ).run(
            "Review the catalog, inbox, and notes apps across screens and summarize the workflow.",
            priorTurns = priorTurns,
        )

        assertTrue("$result", result is TaskState.Succeeded)
        assertTrue("Expected intentDecision to gate phone_swipe calls", decisionCalls > 0)

        val metrics = metricsBuilder.build()!!
        assertEquals(callLogs.size, metrics.modelCalls)

        val totalCalls = callLogs.size
        val totalInput = callLogs.sumOf { it.inputTokens.toLong() }
        val totalCached = callLogs.sumOf { it.cachedTokens.toLong() }
        val totalUncached = totalInput - totalCached
        val avgInput = totalInput.toDouble() / totalCalls
        val cacheHitRate = totalCached.toDouble() / totalInput
        val avgUncached = totalUncached.toDouble() / totalCalls
        val maxContext = callLogs.maxOf { it.inputTokens }

        assertTrue("Expected avg inputTokens < 70_000, was $avgInput", avgInput < 70_000.0)
        assertTrue("Expected cache hit rate > 0.90, was $cacheHitRate", cacheHitRate > 0.90)
        assertTrue("Expected avg uncached input < 8_500, was $avgUncached", avgUncached < 8_500.0)
        assertTrue("Expected maxContext < 80_000 on every request, was $maxContext", maxContext < 80_000)

        val midTaskLogs = callLogs.filter { it.tool != null }
        assertTrue("Expected >= 42 mid-task tool steps, got ${midTaskLogs.size}", midTaskLogs.size >= 42)
        assertTrue(
            "Expected all mid-task outputTokens <= 200, max was ${midTaskLogs.maxOf { it.outputTokens }}",
            midTaskLogs.all { it.outputTokens <= AgentLoop.MAX_TOOL_CALL_OUTPUT_TOKENS },
        )
        assertEquals(1, callLogs.count { it.tool == null && !it.rejected })
        assertTrue("Expected lightRejectionRate < 0.10, was ${metrics.lightRejectionRate}", metrics.lightRejectionRate < 0.10)

        val compactedLogs = callLogs.filter { it.compacted }
        assertTrue("Expected compaction to trigger during the 42-step run", compactedLogs.isNotEmpty())
        for (log in compactedLogs) {
            assertTrue("Compacted request at step ${log.step} (${log.model}) missing step summary", log.hasSummary)
            assertTrue("Compacted request at step ${log.step} (${log.model}) kept ${log.fullUiTrees} full UI trees (> 3)", log.fullUiTrees <= 3)
        }
        assertTrue(
            "Expected older UI trees to be dropped (folded into summary or replaced with omitted marker)",
            compactedLogs.any { it.omittedUiTrees > 0 } || compactedLogs.last().fullUiTrees <= 3,
        )

        // Verify RunEvent.encode works across all emitted events and write JSONL usage report.
        assertTrue(events.all { RunEvent.encode(it).isNotEmpty() })
        val reportFile = File("build/reports/usage-40step-benchmark-v54.jsonl")
        reportFile.parentFile?.mkdirs()
        val lines = callLogs.mapIndexed { idx, log ->
            val hit = if (log.inputTokens == 0) 0.0 else log.cachedTokens.toDouble() / log.inputTokens
            buildJsonObject {
                put("call", idx + 1)
                put("step", log.step)
                put("model", log.model)
                put("tool", log.tool)
                put("rejected", log.rejected)
                put("inputTokens", log.inputTokens)
                put("outputTokens", log.outputTokens)
                put("cachedTokens", log.cachedTokens)
                put("uncachedTokens", log.inputTokens - log.cachedTokens)
                put("cacheHitRate", String.format(Locale.ROOT, "%.4f", hit).toDouble())
                put("compacted", log.compacted)
                put("fullUiTrees", log.fullUiTrees)
                put("omittedUiTrees", log.omittedUiTrees)
            }.toString()
        }
        reportFile.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        assertTrue(reportFile.exists())
        val written = reportFile.readLines().filter { it.isNotBlank() }
        assertEquals(callLogs.size, written.size)
        written.forEach { line -> assertTrue(Json.parseToJsonElement(line) is JsonObject) }
    }

    private data class CallUsageLog(
        val step: Int,
        val model: String,
        val tool: String?,
        val rejected: Boolean,
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedTokens: Int,
        val compacted: Boolean,
        val hasSummary: Boolean,
        val fullUiTrees: Int,
        val omittedUiTrees: Int,
    )

    private class BenchmarkChatModel(
        override val name: String,
        private val logs: MutableList<CallUsageLog>,
    ) : ChatModel {
        override val capabilities = Capabilities(
            toolCalls = true,
            vision = false,
            contextTokens = 128_000,
            reasoning = false,
        )
        private var prevWire: String? = null
        private var seq = 0

        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            val step = STEP_RE.findAll(messages.last().toString()).last().groupValues[1].toInt()
            val currWire = serializeWire(system, tools, messages)
            val inputTokens = TokenEstimate.of(currWire)
            val commonChars = prevWire?.commonPrefixWith(currWire)?.length ?: 0
            val cachedTokens = if (currWire.isEmpty()) 0 else ((commonChars.toDouble() / currWire.length) * inputTokens).toInt().coerceIn(0, inputTokens)
            prevWire = currWire

            val hasSummary = messages.any { m ->
                ContextBuilder.kindOf(m) == ContextKind.Notice &&
                    ContextBuilder.bodyOf(m)?.startsWith(ContextCompressor.HEADER) == true
            }
            val fullUiTrees = messages.sumOf { m -> m.parts.count { p -> p is Part.ToolResult && "\"tree\":[" in p.content } }
            val omittedUiTrees = messages.sumOf { m ->
                m.parts.count { p -> p is Part.ToolResult && "\"tree\":\"${HistoryPruner.OMITTED}\"" in p.content }
            }

            if (step > 42) {
                val summaryText = if (name == "light") {
                    "Completed all workflow steps across the catalog, inbox, and notes apps."
                } else {
                    "Completed the multi-app workflow across the catalog, inbox, and notes sections. All observed pages and scrolled feeds were reviewed without errors, and the navigation controls remained responsive across each app transition. ".repeat(8)
                }
                val outputTokens = TokenEstimate.of(summaryText)
                logs += CallUsageLog(
                    step = step,
                    model = name,
                    tool = null,
                    rejected = name == "light",
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    cachedTokens = cachedTokens,
                    compacted = hasSummary,
                    hasSummary = hasSummary,
                    fullUiTrees = fullUiTrees,
                    omittedUiTrees = omittedUiTrees,
                )
                return ModelReply(listOf(Part.Text(summaryText)), StopReason.EndTurn, Usage(inputTokens, outputTokens, cachedTokens))
            }

            val (toolName, argsJson) = when (step) {
                1 -> "phone_launch_app" to """{"package":"com.shop.catalog"}"""
                9 -> "phone_launch_app" to """{"package":"com.mail.inbox"}"""
                19 -> "phone_launch_app" to """{"package":"com.notes.editor"}"""
                2, 5, 10, 14, 17, 20, 23, 27, 31, 35, 39 -> "phone_observe" to "{}"
                13, 24, 38 -> "phone_swipe" to """{"startX":500,"startY":750,"endX":500,"endY":250}"""
                else -> if (step % 2 == 1) {
                    val snapId = latestSnapshotId(messages)
                    val ref = if (step % 4 == 3) "@e1" else "@e2"
                    "phone_tap" to """{"ref":"$ref","snapshotId":"$snapId"}"""
                } else {
                    "phone_scroll" to """{"direction":"down"}"""
                }
            }
            val call = Part.ToolCall("bm_${++seq}", toolName, argsJson)
            val outputTokens = TokenEstimate.of(call.name + call.argsJson)
            logs += CallUsageLog(
                step = step,
                model = name,
                tool = toolName,
                rejected = false,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedTokens = cachedTokens,
                compacted = hasSummary,
                hasSummary = hasSummary,
                fullUiTrees = fullUiTrees,
                omittedUiTrees = omittedUiTrees,
            )
            return ModelReply(listOf(call), StopReason.ToolUse, Usage(inputTokens, outputTokens, cachedTokens))
        }

        private fun latestSnapshotId(messages: List<Message>): String {
            for (m in messages.asReversed()) {
                for (p in m.parts.asReversed()) {
                    if (p is Part.ToolResult) {
                        SNAPSHOT_ID_RE.find(p.content)?.groupValues?.get(1)?.let { return it }
                    }
                }
            }
            error("No snapshotId found in messages")
        }

        private fun serializeWire(system: String, tools: List<ToolSpec>, messages: List<Message>): String = buildString {
            append("SYS:").append(system).append('\n')
            append("TOOLS:")
            tools.forEach { t ->
                append(t.name).append('|').append(t.description).append('|').append(t.parametersJsonSchema).append('\n')
            }
            messages.forEach { m ->
                append("MSG:").append(m.role.name).append('|').append(m.author.name).append('|')
                m.parts.forEach { p ->
                    when (p) {
                        is Part.Text -> append("T:").append(p.text).append('\n')
                        is Part.Thinking -> append("K:").append(p.text).append('|').append(p.opaque.orEmpty()).append('\n')
                        is Part.ToolCall -> append("C:").append(p.id).append('|').append(p.name).append('|').append(p.argsJson).append('|').append(p.opaque.orEmpty()).append('\n')
                        is Part.ToolResult -> append("R:").append(p.callId).append('|').append(p.isError).append('|').append(p.content).append('\n')
                        is Part.Image -> append("I:").append(p.mime).append('|').append(p.width).append('x').append(p.height).append('|').append(p.pngBase64).append('\n')
                    }
                }
            }
        }

        private companion object {
            val STEP_RE = Regex("Step: (\\d+) of at most")
            val SNAPSHOT_ID_RE = Regex(""""snapshotId"\s*:\s*"([^"]+)"""")
        }
    }

    /** Multi-app synthetic screen producing dense 16-node UiWindowTree snapshots per observe. */
    private class MultiAppDenseScreen : ScreenPort {
        private var currentApp = "com.shop.catalog"
        private var snapSeq = 0
        private var version = 0

        private fun makeSnapshot(id: String): ScreenSnapshot {
            val v = version
            val rows = (1..5).map { r ->
                val top = 120 + (r - 1) * 160
                val title = "Entry row $r on $currentApp screen state $v showing catalog section details and availability overview for browsing workflows across mobile tabs " +
                    "with comprehensive specification summary and extended descriptive metadata segment number $r in active viewport region ".repeat(12)
                val subtitle = "Secondary description for item $r in $currentApp view $v covering inventory status, delivery window notes, and layout navigation index " +
                    "plus structured category path attributes for verification across consecutive reads in test run ".repeat(12)
                val titleNode = UiTreeNode(
                    role = "TextView",
                    bounds = listOf(40, top + 10, 920, top + 65),
                    text = title,
                    resourceId = "$currentApp:id/row_${r}_title",
                )
                val subtitleNode = UiTreeNode(
                    role = "TextView",
                    bounds = listOf(40, top + 70, 920, top + 135),
                    text = subtitle,
                    resourceId = "$currentApp:id/row_${r}_subtitle",
                )
                UiTreeNode(
                    role = "LinearLayout",
                    bounds = listOf(20, top, 960, top + 145),
                    ref = "@e$r",
                    text = "Browse entry $r on view $v",
                    resourceId = "$currentApp:id/row_${r}_card",
                    clickable = true,
                    children = listOf(titleNode, subtitleNode),
                )
            }
            // 1 RecyclerView root + 5 row containers * 3 nodes (row + title + subtitle) = 16 nodes.
            val root = UiTreeNode(
                role = "RecyclerView",
                bounds = listOf(0, 100, 999, 960),
                resourceId = "$currentApp:id/feed_list",
                scrollable = true,
                children = rows,
            )
            val window = UiWindowTree(
                id = 1,
                type = "application",
                layer = 0,
                title = "App $currentApp",
                packageName = currentApp,
                active = true,
                focused = true,
                nodes = listOf(root),
            )
            val elements = (1..5).flatMap { r ->
                val top = 120 + (r - 1) * 160
                val cardText = "Browse entry $r on view $v"
                val detailText = "Detail label $r for $currentApp state $v with extended descriptive text block for screen summary layout verification " +
                    "and catalog metadata verification across multi app mobile steps ".repeat(14)
                listOf(
                    UiElement("@e$r", "LinearLayout", cardText, 490, top + 72, listOf(20, top, 960, top + 145), "$currentApp:id/row_${r}_card", true, false, false),
                    UiElement("@e${r + 5}", "TextView", detailText, 480, top + 38, listOf(40, top + 10, 920, top + 65), "$currentApp:id/row_${r}_title", false, false, false),
                )
            }
            return ScreenSnapshot(id, currentApp, elements, 1080, 2400, windows = listOf(window))
        }

        override suspend fun snapshot(): ScreenSnapshot = makeSnapshot("snap-${++snapSeq}")
        override suspend fun peek(): ScreenSnapshot = makeSnapshot("peek-$snapSeq")
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            version++
            return ActionResult.Ok(changed = true)
        }
        override suspend fun tapXY(x: Int, y: Int): ActionResult {
            version++
            return ActionResult.Ok(changed = true)
        }
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
            version++
            return ActionResult.Ok(changed = true)
        }
        override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult {
            version++
            return ActionResult.Ok(changed = true)
        }
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
            version++
            return ActionResult.Ok(changed = true)
        }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult {
            version++
            return ActionResult.Ok(changed = true)
        }
        override suspend fun back(): ActionResult {
            version++
            return ActionResult.Ok(changed = true)
        }
        override suspend fun pressKey(keyCode: Int): ActionResult {
            version++
            return ActionResult.Ok(changed = true)
        }
        override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
            currentApp = packageName
            version++
            return ActionResult.Ok(changed = true)
        }
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps() = listOf(
            "com.shop.catalog" to "Catalog",
            "com.mail.inbox" to "Inbox",
            "com.notes.editor" to "Notes",
        )
    }
}
