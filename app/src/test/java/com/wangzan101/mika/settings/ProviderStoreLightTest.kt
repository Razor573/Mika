package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.CallOptions
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class ProviderStoreLightTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val setups = mutableListOf<ClientSetup>()
    private val none = Capabilities(toolCalls = false, vision = false, contextTokens = 64_000, reasoning = false)

    private fun store() = ProviderStore(
        tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3",
    ) { cfg, _, _, setup ->
        setups += setup
        object : ChatModel {
            override val capabilities = cfg.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(emptyList(), StopReason.EndTurn, null)
        }
    }

    @Test
    fun modelForLightLeavesClientUnconstrainedForNonToolCallersWhileLightToolOnlyEnforcesToolAndTokenCap() {
        val s = store()
        val p = s.connect("P", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", "k",
            listOf(ListedModel("m1"), ListedModel("m2")))
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        s.use(ModelRole.Vision, p.id, "m1", none)

        setups.clear()
        s.modelFor(ModelRole.Light)
        val lightSetup = setups.single()
        assertNull(lightSetup.maxOutputTokens)

        assertTrue(CallOptions.LIGHT_TOOL_ONLY.forceToolCall)
        assertEquals(200, CallOptions.LIGHT_TOOL_ONLY.maxOutputTokens)

        setups.clear()
        s.modelFor(ModelRole.Main)
        val mainSetup = setups.single()
        assertNull(mainSetup.maxOutputTokens)

        setups.clear()
        s.modelFor(ModelRole.Vision)
        val visionSetup = setups.single()
        assertNull(visionSetup.maxOutputTokens)
    }
}
