package com.wangzan101.mika.core.model

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCodecTest {
    @Test fun dropsTrailingAssistantTurn() {
        val contents = GeminiCodec.buildContents(
            listOf(
                Message(Role.User, listOf(Part.Text("hi")), Author.User),
                Message(Role.Assistant, listOf(Part.Text("hello")), Author.Mika),
            ),
            vision = false,
        )
        assertEquals(1, contents.size)
        assertEquals("user", contents[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test fun keepsFunctionResponsePairing() {
        val contents = GeminiCodec.buildContents(
            listOf(
                Message(Role.User, listOf(Part.Text("run tool")), Author.User),
                Message(Role.Assistant, listOf(Part.ToolCall("c1", "read_file", """{"path":"a"}""")), Author.Mika),
                Message(Role.Tool, listOf(Part.ToolResult("c1", "ok", isError = false)), Author.User),
            ),
            vision = false,
        )
        assertEquals(listOf("user", "model", "user"), contents.map { it.jsonObject["role"]?.jsonPrimitive?.content })
        assertTrue(contents[1].jsonObject["parts"].toString().contains("functionCall"))
        assertTrue(contents[2].jsonObject["parts"].toString().contains("functionResponse"))
    }

    @Test fun dropsTrailingUnansweredToolCall() {
        val contents = GeminiCodec.buildContents(
            listOf(
                Message(Role.User, listOf(Part.Text("run tool")), Author.User),
                Message(Role.Assistant, listOf(Part.ToolCall("c1", "read_file", """{"path":"a"}""")), Author.Mika),
            ),
            vision = false,
        )
        assertEquals(1, contents.size)
        assertEquals("user", contents[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test fun dropsTrailingThinkingOnlyModelTurn() {
        val contents = GeminiCodec.buildContents(
            listOf(
                Message(Role.User, listOf(Part.Text("think")), Author.User),
                Message(Role.Assistant, listOf(Part.Thinking("hmm")), Author.Mika),
            ),
            vision = false,
        )
        assertEquals(1, contents.size)
        assertEquals("user", contents[0].jsonObject["role"]?.jsonPrimitive?.content)
    }
}
