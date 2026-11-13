package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.net.ProxyFailure

sealed class ModelError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth : ModelError("API key is invalid or lacks permission")
    class QuotaExceeded : ModelError("Account quota exceeded")
    /** 本任务的已报告用量达到预算；不再发起任何收尾或重试请求。 */
    class TaskBudgetExceeded(val limit: Long, val used: Long) : ModelError("Token budget for this task exceeded")
    /** 这家服务商今天在本地已报告的用量达到预算；[providerName] 只用于给用户说明是哪一家。 */
    class DailyBudgetExceeded(val providerName: String, val limit: Long, val used: Long) : ModelError("Daily token budget for this provider exceeded")
    /**
     * 主动能力今天(本地日历日,各服务商合计)的已报告用量达到它自己的预算;不发请求。
     * [byCalls] 为真时到的是请求次数的上限,[limit]、[used] 是次数;否则是 Token。
     * 只会出现在主动能力的后台调用里,调用方应安静停下,不当作用户任务的失败去提示。见 [com.wangzan101.mika.core.usage.ProactiveBudget]。
     */
    class ProactiveBudgetExceeded(val limit: Long, val used: Long, val byCalls: Boolean = false) : ModelError("Daily budget for proactive capability exceeded")
    class RateLimited(val retryAfterMs: Long?) : ModelError("Too many requests; please try again later")
    class ContextTooLong : ModelError("Conversation exceeds the model's context length")
    /**
     * 服务商那里没有这个模型(或地址不对,请求的接口不存在);[model] 是请求的模型名,不知道时为空。
     * [detail] 是服务端 JSON 报错里的原话(截短),没有为空:网关、中转服务还会用 404 说别的情况(比如它的上游模型这会儿都不可用),
     * 看原话才分得出是哪一种。
     */
    class ModelNotFound(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "Model not found" else "Model \"$model\" not found")
    /**
     * 服务商有这个模型,这个账号却用不了(没开通、没权限、没买);[detail] 是服务端的原话(截短),没有为空。
     * 换一个模型或到服务商后台开通才行,重试没用。见 [ModelAccessErrors]。
     */
    class ModelNotAvailable(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "This account cannot use that model (not activated or lacks permission)" else "This account cannot use model \"$model\" (not activated or lacks permission)")
    /** 服务端明说这个模型不支持调用工具(带工具的请求被拒)。Mika 干活靠工具,它当不了主模型。 */
    class ToolsUnsupported(val model: String = "") :
        ModelError(if (model.isEmpty()) "This model does not support tool calling" else "Model \"$model\" does not support tool calling")
    class Network(cause: Throwable? = null) : ModelError("Network connection failed", cause)
    /**
     * 走网络代理时代理那一段出的错(连不上代理,或代理拒了账号密码),不是服务商的问题。见 [com.wangzan101.mika.core.net.NetProxy]。
     */
    class Proxy(val failure: ProxyFailure, cause: Throwable? = null) : ModelError(
        when (failure) {
            ProxyFailure.Unreachable -> "Cannot connect to the network proxy"
            ProxyFailure.AuthFailed -> "Network proxy rejected the username or password"
        },
        cause,
    )
    /**
     * 流式回复还没收尾(没有结束标记,也没有结束原因)连接就关了:多半是途中的网关、代理空闲超时或网络切换把连接掐断。
     * 回复不完整、不能用,重发同一个请求通常就好;和接口配置无关。
     */
    class StreamCut : ModelError("Connection closed before the reply finished")
    class Server(val status: Int) : ModelError("Model service error (HTTP $status)")
    class ContentRefused : ModelError("The model refused to process this content")
    /** 服务端明确拒绝思考强度参数:保留服务端原话,引导改档位,不能误报成地址或接口错误。 */
    class ReasoningEffortRejected(val detail: String) : ModelError("The model rejected the selected reasoning effort")
    /** ChatGPT 套餐给其他 App 用的额度到了上限:不重试,等额度恢复或换模型。 */
    class ChatGptLimitReached : ModelError("ChatGPT usage limit reached")
    /** 这个 ChatGPT 账号不能在其他 App 里用套餐额度(套餐不符、地区或账号策略不允许):不重试。 */
    class ChatGptNotEligible(val detail: String = "") :
        ModelError(if (detail.isEmpty()) "This account cannot use ChatGPT allowance in other apps" else "This account cannot use ChatGPT allowance in other apps: $detail")
    /** 登录已失效(换新令牌被拒,或用户在账号里断开了这个 App),要重新登录。[account] 是哪一家的登录(界面上的名字)。 */
    class SignInExpired(val account: String = "ChatGPT") : ModelError("Sign-in expired; please sign in again")
    /**
     * 用账号登录的(如 xAI)接口回 HTTP 403:登录是成功的,这个订阅档位却不能调这个接口。不重试;
     * 改用这家的 API Key 才行。[account] 是哪一家,[detail] 是服务端的原话(截短),没有为空。
     */
    class AccountPlanNotAllowed(val account: String, val detail: String = "") :
        ModelError("Your $account plan does not support this endpoint")
    class BadResponse(detail: String = "", cause: Throwable? = null) :
        ModelError(if (detail.isEmpty()) "The model returned an unreadable response" else "The model returned an unreadable response: $detail", cause)
}
