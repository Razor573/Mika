package com.wangzan101.mika.core.model

/** 单次模型调用的附加约束(如轻量模型强制只调工具、限制输出 token 上限)。 */
data class CallOptions(
    val forceToolCall: Boolean = false,
    val maxOutputTokens: Int? = null,
) {
    companion object {
        val DEFAULT = CallOptions()
        const val LIGHT_MAX_OUTPUT_TOKENS = 200
        val LIGHT_TOOL_ONLY = CallOptions(
            forceToolCall = true,
            maxOutputTokens = LIGHT_MAX_OUTPUT_TOKENS,
        )
    }
}

/** 轻量模型专用提示词约束:只允许返回工具调用,不允许输出解释或总结文字。 */
object LightPrompt {
    const val INSTRUCTION =
        "Tool call only. Respond with a tool call only, no prose, no explanation, no summary, no thinking out loud."
    const val RETRY_REMINDER =
        "Tool call only. Call one of the available tools now, with no prose, no explanation, no summary, no thinking out loud."

    fun ensure(system: String): String {
        val trimmed = system.trimEnd()
        if (trimmed.contains("no prose, no explanation, no summary, no thinking out loud", ignoreCase = true)) {
            return system
        }
        return if (trimmed.isEmpty()) INSTRUCTION else "$trimmed\n\n$INSTRUCTION"
    }
}

interface ChatModel {
    val capabilities: Capabilities

    /** 模型名,只用于记录。 */
    val name: String get() = ""

    /** 失败时抛 [ModelError]。 */
    suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply

    suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        options: CallOptions,
    ): ModelReply = complete(system, messages, tools)
}

/** 支持逐段文字的模型；不支持的实现继续走 [ChatModel.complete]。 */
interface StreamingChatModel : ChatModel {
    /** [onText] 收到的是本次请求已生成的可见文字全文；不包含模型私有思考或工具参数。 */
    suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply

    suspend fun completeStreaming(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        options: CallOptions,
        onText: (String) -> Unit,
    ): ModelReply = completeStreaming(system, messages, tools, onText)
}
