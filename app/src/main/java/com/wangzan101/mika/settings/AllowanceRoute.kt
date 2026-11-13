package com.wangzan101.mika.settings

import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.core.usage.AllowanceSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class AllowanceRoute(
    val source: AllowanceSource? = null,
    val dashboard: String? = null,
    val selfHosted: Boolean = false,
) {
    companion object {
        fun forProvider(provider: Provider): AllowanceRoute {
            when (provider.kind) {
                ProviderConfig.Kind.ChatGpt -> return AllowanceRoute(dashboard = ChatGptAuth.USAGE_URL)
                ProviderConfig.Kind.Xai -> return AllowanceRoute(dashboard = "https://grok.com/?_s=usage")
                else -> Unit
            }
            val url = provider.baseUrl.trim().toHttpUrlOrNull() ?: return AllowanceRoute()
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
                return AllowanceRoute()
            }
            val preset = ProviderPresets.forBaseUrl(url.toString())
            if (LocalHosts.bypassesProxy(url.host) && (preset?.selfHosted == true || provider.name in selfHostedNames)) {
                return AllowanceRoute(selfHosted = true)
            }
            // DeepSeek 同时把不带 /v1 和带 /v1 的地址列为官方接入地址。
            val label = if (url.toString().trimEnd('/') == "https://api.deepseek.com/v1") "DeepSeek" else preset?.label
            return when (label) {
                "DeepSeek" -> AllowanceRoute(AllowanceSource.DeepSeek, "https://platform.deepseek.com/usage")
                "Kimi" -> AllowanceRoute(AllowanceSource.Kimi, "https://platform.kimi.com/console/account")
                "SiliconFlow" -> AllowanceRoute(AllowanceSource.SiliconFlow, "https://cloud.siliconflow.cn/expensebill")
                "OpenRouter" -> AllowanceRoute(AllowanceSource.OpenRouter, "https://openrouter.ai/settings/credits")
                "Qwen" -> AllowanceRoute(dashboard = "https://bailian.console.aliyun.com/")
                "Zhipu" -> AllowanceRoute(dashboard = "https://bigmodel.cn/finance/overview")
                "Zhipu Coding Plan" -> AllowanceRoute(dashboard = "https://www.bigmodel.cn/usercenter/glm-coding/usage")
                "Volcengine Ark" -> AllowanceRoute(dashboard = "https://console.volcengine.com/finance/overview")
                "MiniMax" -> AllowanceRoute(dashboard = "https://platform.minimax.cn/user-center/payment/balance")
                "StepFun" -> AllowanceRoute(dashboard = "https://platform.stepfun.com/")
                "Tencent Hunyuan" -> AllowanceRoute(dashboard = "https://console.cloud.tencent.com/tokenhub")
                "Baidu Qianfan" -> AllowanceRoute(dashboard = "https://console.bce.baidu.com/finance/")
                "Requesty" -> AllowanceRoute(dashboard = "https://app.requesty.ai/")
                "OpenCode Go" -> AllowanceRoute(dashboard = "https://opencode.ai/console")
                "Anthropic" -> AllowanceRoute(dashboard = "https://platform.claude.com/cost")
                "Gemini" -> AllowanceRoute(dashboard = "https://aistudio.google.com/usage")
                "OpenAI" -> AllowanceRoute(dashboard = "https://platform.openai.com/settings/organization/billing/overview")
                "xAI" -> AllowanceRoute(dashboard = "https://console.x.ai/")
                "Mistral" -> AllowanceRoute(dashboard = "https://admin.mistral.ai/organization/usage")
                "Groq" -> AllowanceRoute(dashboard = "https://console.groq.com/settings/billing")
                else -> AllowanceRoute()
            }
        }

        private val selfHostedNames = ProviderPresets.all.filter { it.selfHosted }.map { it.label }.toSet()
    }
}
