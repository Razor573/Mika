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

    @Test fun malformedReplyFailsWithoutInventingFields() = runBlocking {
        assertNull(CallSummaryGenerator.generate(FakeModel("The call went well."), listOf(CallTranscriptLine("caller", "Hello"))))
    }

    @Test fun emptyTranscriptDoesNotCallModel() = runBlocking {
        val model = FakeModel("{}")
        assertNull(CallSummaryGenerator.generate(model, emptyList()))
        assertTrue(model.received.isEmpty())
    }
}
