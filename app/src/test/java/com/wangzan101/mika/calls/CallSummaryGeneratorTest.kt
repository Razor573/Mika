package com.wangzan101.mika.calls

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CallSummaryGeneratorTest {
    private class FakeModel(private val response: String) : ChatModel {
        override val capabilities = Capabilities(false, false, 4096, false)
        var received = ""
        var systemPrompt = ""
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            systemPrompt = system
            received = messages.flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString { it.text }
            assertTrue(system.contains("Never write a request as a fulfilled commitment"))
            assertTrue(tools.isEmpty())
            return ModelReply(listOf(Part.Text(response)), StopReason.EndTurn, null)
        }
    }

    @Test fun parsesStructuredSummaryAndLimitsUntrustedOutput() = runBlocking {
        val model = FakeModel("""Summary: {"purpose":"Confirm repair time","highlights":["Visit on Friday"],"conclusion":"Not scheduled yet","uncertainties":["Exact time TBC"],"actions":[{"text":"Reply to confirm time","status":"PENDING"}]}""")
        val result = CallSummaryGenerator.generate(model, listOf(CallTranscriptLine("caller", "Please contact me on Friday; we'll confirm the exact time later.")))
        assertEquals("Confirm repair time", result?.purpose)
        assertEquals(CallFollowUp.Status.PENDING, result?.actions?.single()?.status)
        assertTrue(model.received.contains("Caller: Please contact me on Friday"))
        assertTrue(model.systemPrompt.contains("write all natural-language values in English"))
    }

    @Test fun summaryPromptFollowsTargetLanguageAndDefaultsToEnglish() = runBlocking {
        val payload = """{"purpose":"Ok","highlights":[],"conclusion":null,"uncertainties":[],"actions":[]}"""
        val line = listOf(CallTranscriptLine("caller", "Hello"))

        val defaultModel = FakeModel(payload)
        CallSummaryGenerator.generate(defaultModel, line)
        assertTrue(defaultModel.systemPrompt.contains("write all natural-language values in English"))

        val systemModel = FakeModel(payload)
        CallSummaryGenerator.generate(systemModel, line, AppLanguage.System)
        assertTrue(systemModel.systemPrompt.contains("write all natural-language values in English"))

        val zhModel = FakeModel(payload)
        CallSummaryGenerator.generate(zhModel, line, AppLanguage.SimplifiedChinese)
        assertTrue(zhModel.systemPrompt.contains("write all natural-language values in Simplified Chinese"))
    }

    @Test fun generatesSummaryAndDraftInOneRequestAndBoundsDraft() = runBlocking {
        var calls = 0
        val model = object : ChatModel {
            override val capabilities = Capabilities(false, false, 4096, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                calls++
                assertTrue(tools.isEmpty())
                assertTrue(system.contains("Never write a request as a fulfilled commitment"))
                return ModelReply(listOf(Part.Text("""{"purpose":"确认报价需求","followUpDraft":"  ${"字".repeat(800)}  "}""")), StopReason.EndTurn, null)
            }
        }
        val result = CallSummaryGenerator.generate(model, listOf(CallTranscriptLine("caller", "请发报价，时间再确认")))!!
        assertEquals(1, calls)
        assertEquals("确认报价需求", result.purpose)
        assertEquals(CallHistory.MAX_DRAFT_LENGTH, result.followUpDraft!!.length)
    }

    @Test fun absentBlankOrMalformedDraftKeepsSummaryWithoutInventingMessage() = runBlocking {
        for (field in listOf("", ",\"followUpDraft\":null", ",\"followUpDraft\":\"  \"", ",\"followUpDraft\":{}")) {
            val result = CallSummaryGenerator.generate(FakeModel("""{"purpose":"寒暄"$field}"""), listOf(CallTranscriptLine("caller", "你好")))!!
            assertEquals("寒暄", result.purpose)
            assertNull(result.followUpDraft)
        }
    }

    @Test fun outgoingSummaryNamesTheOtherPartyAsRecipient() = runBlocking {
        val model = FakeModel("{}")
        CallSummaryGenerator.generate(model, listOf(CallTranscriptLine("caller", "Hello")), direction = CallDirection.OUTGOING)
        assertTrue(model.received.startsWith("Callee:"))
    }

    @Test fun malformedReplyFailsWithoutInventingFields() = runBlocking {
        assertNull(CallSummaryGenerator.generate(FakeModel("The call went well."), listOf(CallTranscriptLine("caller", "Hello"))))
    }

    @Test fun deviceKeySubmissionIsNotAttributedToEitherParty() = runBlocking {
        val model = FakeModel("{}")
        CallSummaryGenerator.generate(model, listOf(
            CallTranscriptLine("system", "已提交 3 个按键，等待对方响应"),
            CallTranscriptLine("caller", "请选择业务"),
        ))
        assertEquals("Caller: 请选择业务", model.received)
        val onlyStatus = FakeModel("{}")
        assertNull(CallSummaryGenerator.generate(onlyStatus, listOf(CallTranscriptLine("system", "已提交按键"))))
        assertTrue(onlyStatus.received.isEmpty())
    }

    @Test fun emptyTranscriptDoesNotCallModel() = runBlocking {
        val model = FakeModel("{}")
        assertNull(CallSummaryGenerator.generate(model, emptyList()))
        assertTrue(model.received.isEmpty())
    }

    @Test fun currentConsentIsRequiredBeforeSubmittingTranscript() = runBlocking {
        val model = FakeModel("""{"purpose":"维修"}""")
        val transcript = listOf(CallTranscriptLine("caller", "请安排维修"))
        var enabled = false
        assertNull(CallSummaryGenerator.generate(model, transcript, canSend = { enabled }))
        assertTrue(model.received.isEmpty())

        enabled = true
        assertEquals("维修", CallSummaryGenerator.generate(model, transcript, canSend = { enabled })?.purpose)
        assertTrue(model.received.contains("请安排维修"))
    }
}
