package com.wangzan101.mika.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // Vertex AI rejects a user content that mixes a functionResponse with
    // text (400 "Requests ending with a model turn are not supported"), so
    // the response and the following text must be separate user contents.
    // This is the shape AgentLoop sends on the step right after a tool call:
    // the tool result, then the next user turn (notice / runtime state).
    @Test fun functionResponseAndFollowingTextAreSeparateContents() {
        val contents = GeminiCodec.buildContents(
            listOf(
                Message(Role.User, listOf(Part.Text("run tool")), Author.User),
                Message(Role.Assistant, listOf(Part.ToolCall("c1", "read_file", """{"path":"a"}""")), Author.Mika),
                Message(Role.Tool, listOf(Part.ToolResult("c1", "ok", isError = false)), Author.User),
                Message(Role.User, listOf(Part.Text("next")), Author.User),
            ),
            vision = false,
        )
        assertEquals(listOf("user", "model", "user", "user"), contents.map { it.jsonObject["role"]?.jsonPrimitive?.content })
        val responseParts = contents[2].jsonObject["parts"]!!.jsonArray
        assertEquals(1, responseParts.size)
        assertTrue(responseParts[0].jsonObject.containsKey("functionResponse"))
        val textParts = contents[3].jsonObject["parts"]!!.jsonArray
        assertEquals(1, textParts.size)
        assertEquals("next", textParts[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    // AgentLoop.appendRuntimeState appends a runtime-state text part to the
    // last message, which after a tool call is the Tool message itself, so
    // one message can carry both a ToolResult and text. They must still be
    // split into a functionResponse-only content and a text content.
    @Test fun runtimeStateTextOnToolMessageIsSplitFromFunctionResponse() {
        val contents = GeminiCodec.buildContents(
            listOf(
                Message(Role.User, listOf(Part.Text("run tool")), Author.User),
                Message(Role.Assistant, listOf(Part.ToolCall("c1", "read_file", """{"path":"a"}""")), Author.Mika),
                Message(Role.Tool, listOf(Part.ToolResult("c1", "ok", isError = false), Part.Text("Current place: no screen")), Author.User),
            ),
            vision = false,
        )
        assertEquals(listOf("user", "model", "user", "user"), contents.map { it.jsonObject["role"]?.jsonPrimitive?.content })
        assertEquals(1, contents[2].jsonObject["parts"]!!.jsonArray.size)
        assertTrue(contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject.containsKey("functionResponse"))
        assertEquals("Current place: no screen", contents[3].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    // Parallel calls return several results in one Tool message: they stay
    // together in a single functionResponse-only content.
    @Test fun parallelToolResultsStayInOneContent() {
        val contents = GeminiCodec.buildContents(
            listOf(
                Message(Role.User, listOf(Part.Text("run tools")), Author.User),
                Message(Role.Assistant, listOf(Part.ToolCall("c1", "a", "{}"), Part.ToolCall("c2", "b", "{}")), Author.Mika),
                Message(Role.Tool, listOf(Part.ToolResult("c1", "one", isError = false), Part.ToolResult("c2", "two", isError = false)), Author.User),
            ),
            vision = false,
        )
        assertEquals(listOf("user", "model", "user"), contents.map { it.jsonObject["role"]?.jsonPrimitive?.content })
        assertEquals(2, contents[2].jsonObject["parts"]!!.jsonArray.size)
        assertTrue(contents[2].jsonObject["parts"]!!.jsonArray.all { it.jsonObject.containsKey("functionResponse") })
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

    @Test fun buildPayloadEmitsGoogleSearchToolWhenRequested() {
        val messages = listOf(Message(Role.User, listOf(Part.Text("latest kotlin release")), Author.User))
        val grounded = GeminiCodec.buildPayload(
            system = "Search carefully.",
            messages = messages,
            tools = emptyList(),
            vision = false,
            googleSearch = true,
        )
        val tools = grounded["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals(JsonObject(emptyMap()), tools[0].jsonObject["googleSearch"]!!.jsonObject)
        assertFalse(tools[0].jsonObject.containsKey("functionDeclarations"))

        val plain = GeminiCodec.buildPayload(
            system = "Search carefully.",
            messages = messages,
            tools = emptyList(),
            vision = false,
            googleSearch = false,
        )
        assertFalse(plain.containsKey("tools"))

        val both = GeminiCodec.buildPayload(
            system = "Search carefully.",
            messages = messages,
            tools = listOf(ToolSpec("wait", "wait briefly", """{"type":"object"}""")),
            vision = false,
            googleSearch = true,
        )
        val bothTools = both["tools"]!!.jsonArray
        assertEquals(2, bothTools.size)
        assertTrue(bothTools[0].jsonObject.containsKey("functionDeclarations"))
        assertEquals(JsonObject(emptyMap()), bothTools[1].jsonObject["googleSearch"]!!.jsonObject)
    }

    @Test fun forcedToolCallStripsArrayBoundsAndDisablesThinkingOnGemini3Flash() {
        val phoneRunSchema = """{"type":"object","properties":{"commands":{"type":"array","minItems":1,"maxItems":30,"items":{"type":"object","properties":{"op":{"type":"string"},"x":{"type":"integer"}},"required":["op"]}},"reason":{"type":"string"}},"required":["commands"],"additionalProperties":false}"""
        val specs = listOf(ToolSpec("phone_run", "Run commands", phoneRunSchema))
        val user = listOf(Message(Role.User, listOf(Part.Text("tap 100,200")), Author.User))

        val unforced = GeminiCodec.buildPayload("BASE", user, specs, vision = false, forceToolCall = false, model = "gemini-3.8-flash")
        val unforcedCommands = unforced["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0]
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject["commands"]!!.jsonObject
        assertEquals("1", unforcedCommands["minItems"]!!.jsonPrimitive.content)
        assertEquals("30", unforcedCommands["maxItems"]!!.jsonPrimitive.content)

        val forced = GeminiCodec.buildPayload(
            system = "BASE",
            messages = user,
            tools = specs,
            vision = false,
            maxOutputTokens = CallOptions.LIGHT_MAX_OUTPUT_TOKENS,
            reasoningEffort = null,
            forceToolCall = true,
            model = "gemini-3.8-flash",
        )
        assertEquals("ANY", forced["tool_config"]!!.jsonObject["function_calling_config"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        val forcedCommands = forced["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0]
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject["commands"]!!.jsonObject
        assertFalse(forcedCommands.containsKey("minItems"))
        assertFalse(forcedCommands.containsKey("maxItems"))
        assertEquals(200, forced["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, forced["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun resolvesMinimalAndModelFamilyThinkingSafely() {
        val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))
        val specs = listOf(ToolSpec("phone_tap", "Tap", """{"type":"object","properties":{"x":{"type":"integer"}}}"""))

        val flashMinimal = GeminiCodec.buildPayload("BASE", user, specs, vision = false, reasoningEffort = "minimal", model = "gemini-3.8-flash")
        assertEquals("LOW", flashMinimal["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)

        val hydrogenForced = GeminiCodec.buildPayload("BASE", user, specs, vision = false, maxOutputTokens = 200, forceToolCall = true, model = "gemini-4-hydrogen")
        val hThink = hydrogenForced["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("LOW", hThink["thinkingLevel"]!!.jsonPrimitive.content)
        assertFalse(hThink.containsKey("thinkingBudget"))
    }
}
