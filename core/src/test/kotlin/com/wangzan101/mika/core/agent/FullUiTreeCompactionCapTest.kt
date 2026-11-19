package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiTreeJson
import com.wangzan101.mika.core.uistate.UiTreeNode
import com.wangzan101.mika.core.uistate.UiWindowTree
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullUiTreeCompactionCapTest {
    private var seq = 0
    private val task = Message(Role.User, listOf(Part.Text("Inspect settings and report")), Author.User)

    private fun obsRound(step: Int, treePad: String = "item-$step"): List<Message> {
        val id = "o${++seq}"
        val tree = """[{"id":1,"type":"application","layer":1,"nodes":[{"role":"FrameLayout","bounds":[0,0,1080,2400],"children":[{"role":"TextView","text":"$treePad","bounds":[24,100,900,180]}]}]}]"""
        val json = """{"ok":true,"currentApp":"com.example","snapshotId":"s$step","elements":[{"ref":"@e$step","role":"Button","name":"$treePad"}],"tree":$tree}"""
        return listOf(
            Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_observe", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult(id, json, false)), Author.Mika),
        )
    }

    private fun tapRound(step: Int): List<Message> {
        val id = "t${++seq}"
        return listOf(
            Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_tap", """{"target":"btn-$step"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult(id, """{"ok":true,"changed":true}""", false)), Author.Mika),
        )
    }

    @Test fun fullUiTreePreservesAllStructuralAndNonInteractiveNodesAndBounds() {
        val labelNode = UiTreeNode(
            role = "TextView",
            bounds = listOf(40, 120, 400, 180),
            text = "Static section header",
            resourceId = "com.example:id/header",
        )
        val decorativeIcon = UiTreeNode(
            role = "ImageView",
            bounds = listOf(420, 120, 480, 180),
            description = "Info badge",
        )
        val interactiveButton = UiTreeNode(
            role = "Button",
            bounds = listOf(40, 200, 1040, 320),
            ref = "@e1",
            text = "Continue",
            clickable = true,
        )
        val structuralInner = UiTreeNode(
            role = "LinearLayout",
            bounds = listOf(20, 100, 1060, 340),
            resourceId = "com.example:id/card",
            children = listOf(labelNode, decorativeIcon, interactiveButton),
        )
        val structuralRoot = UiTreeNode(
            role = "FrameLayout",
            bounds = listOf(0, 0, 1080, 2400),
            children = listOf(structuralInner),
        )
        val window = UiWindowTree(1, "application", 1, "Settings", "com.example", active = true, focused = true, nodes = listOf(structuralRoot))
        val snapshot = ScreenSnapshot(
            snapshotId = "snap-full",
            packageName = "com.example",
            elements = listOf(UiElement("@e1", "Button", "Continue", 540, 260, listOf(40, 200, 1040, 320), null, true, false, false)),
            width = 1080,
            height = 2400,
            windows = listOf(window),
        )

        val windows = UiTreeJson.windows(snapshot)
        assertEquals(1, windows.size)
        val rootJson = windows[0].jsonObject["nodes"]!!.jsonArray.single().jsonObject
        assertEquals("FrameLayout", rootJson["role"]!!.jsonPrimitive.content)
        assertEquals(listOf(0, 0, 1080, 2400), rootJson["bounds"]!!.jsonArray.map { it.jsonPrimitive.int })

        val innerJson = rootJson["children"]!!.jsonArray.single().jsonObject
        assertEquals("LinearLayout", innerJson["role"]!!.jsonPrimitive.content)
        assertEquals("com.example:id/card", innerJson["resourceId"]!!.jsonPrimitive.content)
        assertEquals(listOf(20, 100, 1060, 340), innerJson["bounds"]!!.jsonArray.map { it.jsonPrimitive.int })

        val leaves = innerJson["children"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, leaves.size)
        assertEquals("TextView", leaves[0]["role"]!!.jsonPrimitive.content)
        assertEquals("Static section header", leaves[0]["text"]!!.jsonPrimitive.content)
        assertEquals(listOf(40, 120, 400, 180), leaves[0]["bounds"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertFalse(leaves[0].containsKey("ref"))

        assertEquals("ImageView", leaves[1]["role"]!!.jsonPrimitive.content)
        assertEquals("Info badge", leaves[1]["description"]!!.jsonPrimitive.content)
        assertEquals(listOf(420, 120, 480, 180), leaves[1]["bounds"]!!.jsonArray.map { it.jsonPrimitive.int })

        assertEquals("Button", leaves[2]["role"]!!.jsonPrimitive.content)
        assertEquals("@e1", leaves[2]["ref"]!!.jsonPrimitive.content)
        assertEquals(listOf(40, 200, 1040, 320), leaves[2]["bounds"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test fun compactionTriggersEvery15StepsSummarisesCompletedStepsAndKeepsLast3Observations() {
        val compressor = ContextCompressor(TOOLS)
        val allObs = (1..30).map { obsRound(it, treePad = "screen-tree-$it") }
        fun historyAt(steps: Int) = listOf(task) + allObs.take(steps).flatten()

        for (step in 1..14) {
            val out = compressor.compress(historyAt(step), contextTokens = 1_000_000)
            assertFalse("step $step should not compact", out.rewritten)
            assertEquals(0, out.foldedSteps)
        }

        val at15 = compressor.compress(historyAt(15), contextTokens = 1_000_000)
        assertTrue(at15.rewritten)
        assertEquals(12, at15.foldedSteps)
        val summary15 = at15.messages.firstNotNullOf { m -> m.plainText().takeIf { it.startsWith(ContextCompressor.HEADER) } }
        assertTrue(summary15, "Step 1 phone_observe" in summary15 && "Step 12 phone_observe" in summary15 && "Step 13" !in summary15)
        val trees15 = at15.messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
        assertEquals(3, trees15.size)
        assertTrue((13..15).all { n -> trees15.any { "\"screen-tree-$n\"" in it } })
        assertTrue((1..12).all { n -> trees15.none { "\"screen-tree-$n\"" in it } })

        for (step in 16..29) {
            val out = compressor.compress(historyAt(step), contextTokens = 1_000_000)
            assertFalse("step $step should stay append-only", out.rewritten)
        }

        val at30 = compressor.compress(historyAt(30), contextTokens = 1_000_000)
        assertTrue(at30.rewritten)
        assertEquals(27, at30.foldedSteps)
        val trees30 = at30.messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
        assertEquals(3, trees30.size)
        assertTrue((28..30).all { n -> trees30.any { "\"screen-tree-$n\"" in it } })
        assertTrue((1..27).all { n -> trees30.none { "\"screen-tree-$n\"" in it } })
    }

    @Test fun agentLoopPeriodicCompactionKeepsLast3ObservationsAndScreenshots() = runTest {
        var reads = 0
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                reads++
                val node = UiTreeNode("TextView", listOf(0, 0, 100, 50), text = "full-node-$reads")
                val win = UiWindowTree(1, "application", 1, "App", "com.x", true, true, listOf(node))
                val el = UiElement("@e1", "button", "btn-$reads", 50, 25, listOf(0, 0, 100, 50), null, true, false, false)
                return ScreenSnapshot("snap-$reads", "com.x", listOf(el), 1080, 2400, windows = listOf(win))
            }
            override suspend fun screenshotPng() = byteArrayOf(1, 2, 3)
        }
        var callSeq = 0
        val replies = (1..30).map { step ->
            val name = if (step >= 28 || step % 2 == 1) "phone_observe" else "phone_list_elements"
            ModelReply(listOf(Part.ToolCall("c${++callSeq}", name, "{}")), StopReason.ToolUse, null)
        } + answerReply("finished")
        val model = FakeModel(*replies.toTypedArray(), contextTokens = 1_000_000)
        val end = AgentLoop(model, toolDispatcher(screen, visionEnabled = true), "BASE").run("check 30 screens")
        assertTrue(end is TaskState.Succeeded)

        val finalReq = model.requests.last()
        assertTrue(finalReq.any { it.plainText().startsWith(ContextCompressor.HEADER) })
        val fullNodesKept = finalReq.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().count { "full-node-" in it.content }
        assertEquals(3, fullNodesKept)
        val imagesKept = finalReq.flatMap { it.parts }.filterIsInstance<Part.Image>()
        assertEquals(3, imagesKept.size)
        assertTrue(TokenEstimate.of(finalReq) <= ContextLimits.MAX_INPUT_TOKENS)
    }

    @Test fun inputTokenCap100kEnforcedInLimitsCompressorAndModelCaller() = runTest {
        assertEquals(100_000, ContextLimits.MAX_INPUT_TOKENS)
        val limits128 = ContextLimits.of(128_000)
        val limits1m = ContextLimits.of(1_000_000)
        for (l in listOf(limits128, limits1m)) {
            assertEquals(100_000, l.usable)
            assertEquals(87_000, l.trigger)
            assertEquals(50_000, l.target)
            assertEquals(50_000, l.minTrigger)
        }
        assertEquals(100_000, ContextLimits.of(1_000_000, triggerOverride = 600_000).trigger)

        val compressor = ContextCompressor(TOOLS)
        val bigTree = "node-text-".repeat(12_000)
        val over100kBefore15 = listOf(task) + (1..6).flatMap { obsRound(it, treePad = bigTree) }
        assertTrue(TokenEstimate.of(over100kBefore15) > 100_000)
        val compressed = compressor.compress(over100kBefore15, contextTokens = 1_000_000, fixedOverhead = 5_000)
        assertTrue(compressed.rewritten)
        assertTrue(TokenEstimate.of(compressed.messages) + 5_000 <= ContextLimits.MAX_INPUT_TOKENS)
        assertTrue(UsageMeter.estimate(TokenEstimate.of(compressed.messages) + 5_000, compressor.scale) <= ContextLimits.MAX_INPUT_TOKENS)

        val fake = FakeModel(answerReply("ok"), contextTokens = 1_000_000)
        val caller = ModelCaller(fake, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
        caller.reset("SYSTEM")

        val hugePayload180k = listOf(
            task,
            Message(Role.Assistant, listOf(Part.ToolCall("big1", "run_command", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("big1", """{"ok":false,"error":"too_big","message":"${"a".repeat(720_000)}"}""", true)), Author.Mika),
        )
        assertTrue(TokenEstimate.of(hugePayload180k) > 175_000)
        caller.ask(hugePayload180k, emptyList(), step = 1)
        val sentToolCap = fake.requests.single()
        assertTrue("${TokenEstimate.of(sentToolCap)}", TokenEstimate.of(sentToolCap) <= ContextLimits.MAX_INPUT_TOKENS)
        assertTrue(UsageMeter.estimate(TokenEstimate.of(sentToolCap) + TokenEstimate.of("SYSTEM"), ContextCompressor.UNCALIBRATED_SCALE) <= ContextLimits.MAX_INPUT_TOKENS)

        val streamedRequests = mutableListOf<List<Message>>()
        val streaming = object : StreamingChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 1_000_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("unused")
            override suspend fun completeStreaming(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply {
                streamedRequests += messages
                return answerReply("ok")
            }
        }
        val streamCaller = ModelCaller(streaming, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
        streamCaller.reset("SYSTEM")
        val hugeUser180k = listOf(Message(Role.User, listOf(Part.Text("u".repeat(720_000))), Author.User))
        assertTrue(TokenEstimate.of(hugeUser180k) > 175_000)
        streamCaller.ask(hugeUser180k, emptyList(), step = 1) {}
        val sentStreamCap = streamedRequests.single()
        assertTrue("${TokenEstimate.of(sentStreamCap)}", TokenEstimate.of(sentStreamCap) <= ContextLimits.MAX_INPUT_TOKENS)
        assertTrue(UsageMeter.estimate(TokenEstimate.of(sentStreamCap) + TokenEstimate.of("SYSTEM"), ContextCompressor.UNCALIBRATED_SCALE) <= ContextLimits.MAX_INPUT_TOKENS)
    }

    @Test fun periodicCompactionWithMixedTapsAndObservationsPreservesLast3Obs() {
        val compressor = ContextCompressor(TOOLS)
        val rounds = (1..15).flatMap { step ->
            if (step in listOf(1, 4, 7, 10, 12, 14, 15)) obsRound(step, treePad = "mixed-tree-$step")
            else tapRound(step)
        }
        val h = listOf(task) + rounds
        val c = compressor.compress(h, contextTokens = 1_000_000)
        assertTrue(c.rewritten)
        assertEquals(11, c.foldedSteps)
        val keptObs = c.messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }.filter { "mixed-tree-" in it }
        assertEquals(3, keptObs.size)
        assertTrue(listOf(12, 14, 15).all { n -> keptObs.any { "mixed-tree-$n" in it } })
    }

    @Test fun speedFlagsTablesAtBirthKeepsFullStructuralAndNonInteractiveTreeWhilePackingElementTables() = runTest {
        val labelNode = UiTreeNode(
            role = "TextView",
            bounds = listOf(40, 120, 400, 180),
            text = "Static section header",
            resourceId = "com.example:id/header",
        )
        val decorativeIcon = UiTreeNode(
            role = "ImageView",
            bounds = listOf(420, 120, 480, 180),
            description = "Info badge",
        )
        val elements = (1..6).map { i ->
            UiElement(
                ref = "@e$i",
                role = "Button",
                name = "Action Option $i",
                centerX = 540,
                centerY = 200 + i * 120,
                bounds = listOf(40, 150 + i * 120, 1040, 250 + i * 120),
                resourceId = "com.example:id/opt_$i",
                clickable = true,
                editable = false,
                scrollable = false,
            )
        }
        val interactiveNodes = elements.map { el ->
            UiTreeNode(role = el.role, bounds = el.bounds, ref = el.ref, text = el.name, resourceId = el.resourceId, clickable = true)
        }
        val cardContainer = UiTreeNode(
            role = "LinearLayout",
            bounds = listOf(20, 100, 1060, 1100),
            resourceId = "com.example:id/card",
            children = listOf(labelNode, decorativeIcon) + interactiveNodes,
        )
        val rootContainer = UiTreeNode(
            role = "FrameLayout",
            bounds = listOf(0, 0, 1080, 2400),
            children = listOf(cardContainer),
        )
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot(
                snapshotId = "snap-packed",
                packageName = "com.example",
                elements = elements,
                width = 1080,
                height = 2400,
                windows = listOf(UiWindowTree(1, "application", 1, "Settings", "com.example", active = true, focused = true, nodes = listOf(rootContainer))),
            )
        }
        val model = FakeModel(
            ModelReply(listOf(Part.ToolCall("o1", "phone_observe", "{}")), StopReason.ToolUse, null),
            answerReply("done"),
            contextTokens = 1_000_000,
        )
        val end = AgentLoop(model, toolDispatcher(screen), "BASE", flags = SpeedFlags(tablesAtBirth = true)).run("inspect screen")
        assertTrue(end is TaskState.Succeeded)

        val toolContent = model.requests[1].flatMap { it.parts }.filterIsInstance<Part.ToolResult>().single().content
        val root = Json.parseToJsonElement(toolContent).jsonObject
        val screenObj = (root["screen"] as? kotlinx.serialization.json.JsonObject) ?: root
        assertTrue(toolContent, "elementTables" in screenObj)
        assertFalse(toolContent, "elements" in screenObj)
        val treeArr = screenObj["tree"]!!.jsonArray
        assertEquals(1, treeArr.size)
        val frameJson = treeArr[0].jsonObject["nodes"]!!.jsonArray.single().jsonObject
        assertEquals("FrameLayout", frameJson["role"]!!.jsonPrimitive.content)
        val linearJson = frameJson["children"]!!.jsonArray.single().jsonObject
        assertEquals("LinearLayout", linearJson["role"]!!.jsonPrimitive.content)
        assertEquals("com.example:id/card", linearJson["resourceId"]!!.jsonPrimitive.content)
        val children = linearJson["children"]!!.jsonArray.map { it.jsonObject }
        assertEquals("TextView", children[0]["role"]!!.jsonPrimitive.content)
        assertEquals("Static section header", children[0]["text"]!!.jsonPrimitive.content)
        assertEquals(listOf(40, 120, 400, 180), children[0]["bounds"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals("ImageView", children[1]["role"]!!.jsonPrimitive.content)
        assertEquals("Info badge", children[1]["description"]!!.jsonPrimitive.content)
        assertEquals(8, children.size)
    }
}
