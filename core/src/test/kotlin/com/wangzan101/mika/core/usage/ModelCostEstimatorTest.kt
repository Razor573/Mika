package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.StopRules
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.record.RunStats
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelCostEstimatorTest {
    private val estimator = ModelCostEstimator.DEFAULT

    @Test fun estimatesBundledGeminiOpenAiAndDeepSeekRates() {
        val usage = Usage(inputTokens = 1_000_000, outputTokens = 500_000, cachedInputTokens = 0)
        // gemini-3.1-flash-lite-preview: $0.25 in / $1.50 out per 1M -> 0.25 + 0.75 = 1.00
        val gemini = checkNotNull(estimator.estimateCall("gemini-3.1-flash-lite-preview", usage))
        assertFalse(gemini.exact)
        assertFalse(gemini.isFree)
        assertEquals(1.00, gemini.usd, 1e-6)

        // Vertex AI & Google API prefixes normalize to the same bundled rate
        val vertex = checkNotNull(estimator.estimateCall("publishers/google/models/gemini-3.8-flash", Usage(1_000_000, 1_000_000)))
        val google = checkNotNull(estimator.estimateCall("models/gemini-3.8-flash", Usage(1_000_000, 1_000_000)))
        assertEquals(vertex.usd, google.usd, 1e-9)

        // gpt-6-mini: $0.30 in / $1.20 out
        val gpt6 = checkNotNull(estimator.estimateCall("openai/gpt-6-mini", Usage(1_000_000, 1_000_000)))
        assertEquals(1.50, gpt6.usd, 1e-6)

        // deepseek-v4-flash: $0.30 in / $0.50 out
        val ds = checkNotNull(estimator.estimateCall("deepseek/deepseek-v4-flash", Usage(1_000_000, 1_000_000)))
        assertEquals(0.80, ds.usd, 1e-6)
    }

    @Test fun claude55AppliesFivePercentCacheReadDiscount() {
        // claude-sonnet-5.5: $3.00 in / $15.00 out / cacheReadMultiplier = 0.05 ($0.15 per 1M cached)
        // 1,000,000 input of which 800,000 cached + 100,000 output:
        // non-cached input = 200,000 * 3.0 / 1M = $0.60
        // cached input = 800,000 * 3.0 * 0.05 / 1M = $0.12
        // output = 100,000 * 15.0 / 1M = $1.50 -> total = $2.22
        val est = checkNotNull(
            estimator.estimateCall(
                "anthropic/claude-sonnet-5.5",
                Usage(inputTokens = 1_000_000, outputTokens = 100_000, cachedInputTokens = 800_000),
            )
        )
        assertFalse(est.exact)
        assertEquals(2.22, est.usd, 1e-6)
    }

    @Test fun providerReportedCostTakesPrecedenceOverFreeAndOverridesAndTable() {
        val reported = checkNotNull(
            estimator.estimateCall(
                model = "qwen/qwen3-next:free",
                usage = Usage(10_000, 5_000, costUsd = 0.042),
                override = ModelRateOverride(99.0, 99.0),
            )
        )
        assertTrue(reported.exact)
        assertFalse(reported.isFree)
        assertEquals(0.042, reported.usd, 1e-9)
    }

    @Test fun freeTierSuffixReturnsZeroUsd() {
        val est = checkNotNull(estimator.estimateCall("qwen/qwen3-next:free", Usage(12_000, 3_000)))
        assertTrue(est.exact)
        assertTrue(est.isFree)
        assertEquals(0.0, est.usd, 1e-9)
    }

    @Test fun unknownOrLocalModelReturnsNullUnlessCustomRateProvided() {
        val usage = Usage(1_000, 500)
        assertNull(estimator.estimateCall("llama3.2:latest", usage))
        assertNull(estimator.estimateCall("my-custom-gateway-v1", usage))

        val custom = checkNotNull(
            estimator.estimateCall(
                "llama3.2:latest",
                usage,
                override = ModelRateOverride(inputPerMUsd = 2.0, outputPerMUsd = 4.0),
            )
        )
        assertFalse(custom.exact)
        assertEquals(0.004, custom.usd, 1e-9)
    }

    @Test fun runUsdCostTracksExactPartialAndPersistsThroughRunEventsAndMetrics() {
        val runUsd = RunUsdCost(estimator)
        val openRouter = UsageSource("p1", "OpenRouter", "openai/gpt-6-mini", UsagePurpose.Main)
        val local = UsageSource("p2", "Ollama", "llama3:8b", UsagePurpose.Light)

        assertNull(runUsd.snapshot())
        runUsd.add(openRouter, Usage(1_000, 200, costUsd = 0.05), httpAttempts = 1)
        assertEquals(UsdCost(usd = 0.05, exact = true, partial = false), runUsd.snapshot())

        // Unpriced call marks partial = true without discarding known spend
        runUsd.add(local, Usage(500, 100), httpAttempts = 1)
        assertEquals(UsdCost(usd = 0.05, exact = false, partial = true), runUsd.snapshot())

        val tally = RunUsageTally(usdBudget = 0.10, usdCost = runUsd)
        tally.checkBudget()
        tally.add(Usage(1_000, 200, costUsd = 0.06), httpAttempts = 1)
        runUsd.add(openRouter, Usage(1_000, 200, costUsd = 0.06), httpAttempts = 1)
        try {
            tally.checkBudget()
            fail("Expected UsdBudgetExceeded once spend >= cap")
        } catch (e: ModelError.UsdBudgetExceeded) {
            assertEquals(0.10, e.limitUsd, 1e-9)
            assertEquals(0.11, e.spentUsd, 1e-9)
            assertEquals(FailKind.TokenLimit, StopRules.modelFailed(e).kind)
        }

        val event = tally.event(atMs = 100L)
        val decoded = RunEvent.decode(RunEvent.encode(event)) as RunEvent.ModelCalls
        assertEquals(UsdCost(usd = 0.11, exact = false, partial = true), decoded.usdCost)

        val metrics = checkNotNull(
            RunMetrics.of(
                listOf(
                    RunEvent.TaskStarted(0L, "test", "openai/gpt-6-mini"),
                    decoded,
                )
            )
        )
        assertEquals(UsdCost(usd = 0.11, exact = false, partial = true), metrics.usdCost)
        val stats = RunStats.of(listOf(metrics))
        assertEquals(0.11, stats.totalUsdCost!!, 1e-9)
        assertEquals(0.11, stats.avgUsdCost!!, 1e-9)
    }

    @Test fun agentLoopPromptsInteractiveUserOnUsdCapAndResumesAfterExtension() = runTest {
        val source = UsageSource("p1", "OpenRouter", "openai/gpt-6-mini", UsagePurpose.Main)
        val tally = RunUsageTally(usdBudget = 0.10, usdCost = RunUsdCost(estimator))
        val replies = ArrayDeque(
            listOf(
                ModelReply(listOf(Part.ToolCall("c1", "phone_observe", "{}")), StopReason.ToolUse, Usage(100, 50, costUsd = 0.12)),
                ModelReply(listOf(Part.Text("All done")), StopReason.EndTurn, Usage(100, 50, costUsd = 0.02)),
            )
        )
        val scripted = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) = replies.removeFirst()
        }
        val metered = scripted.metered(source, UsageSink { _, _ -> }, beforeCall = { tally.checkBudget() })
        val loop = AgentLoop(metered, toolDispatcher(FakeScreen()), "BASE")
        val inbox = RunInbox()
        val spec = RunSpec(
            id = RunId("r-usd"),
            goal = "do something",
            threadId = "t-usd",
            provenance = RunProvenance(origin = RunOrigin.Ui, attendance = Attendance.Present),
        )

        val job = async {
            withContext(tally) { loop.run(spec, inbox) }
        }
        runCurrent()
        // First call succeeds ($0.12 >= $0.10 cap), next step triggers UsdBudgetExceeded -> WaitingInput prompt
        val state = loop.state.value as TaskState.WaitingInput
        assertEquals(listOf("+$0.15", "+$0.50", "Stop"), state.options)
        inbox.send(RunInput.Answer("+$0.15", loop.activeAsk?.askId))
        advanceUntilIdle()

        val end = job.await()
        assertTrue("Expected Succeeded after extending USD cap, got $end", end is TaskState.Succeeded)
        assertEquals(0.14, tally.usdCost!!.currentUsd(), 1e-9)
    }

    @Test fun agentLoopFailsUnattendedRunWhenUsdCapExceeded() = runTest {
        val source = UsageSource("p1", "OpenRouter", "openai/gpt-6-mini", UsagePurpose.Main)
        val tally = RunUsageTally(usdBudget = 0.05, usdCost = RunUsdCost(estimator))
        val replies = ArrayDeque(
            listOf(
                ModelReply(listOf(Part.ToolCall("c1", "phone_observe", "{}")), StopReason.ToolUse, Usage(100, 50, costUsd = 0.06)),
                ModelReply(listOf(Part.Text("Should not run")), StopReason.EndTurn, Usage(100, 50, costUsd = 0.01)),
            )
        )
        val scripted = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) = replies.removeFirst()
        }
        val metered = scripted.metered(source, UsageSink { _, _ -> }, beforeCall = { tally.checkBudget() })
        val loop = AgentLoop(metered, toolDispatcher(FakeScreen()), "BASE")
        val inbox = RunInbox()
        val spec = RunSpec(
            id = RunId("r-usd-auto"),
            goal = "scheduled work",
            threadId = "t-usd-auto",
            provenance = RunProvenance(origin = RunOrigin.Trigger, attendance = Attendance.Unattended),
        )

        val end = withContext(tally) { loop.run(spec, inbox) }
        val failed = end as TaskState.Failed
        assertEquals(FailKind.TokenLimit, failed.kind)
    }
}
