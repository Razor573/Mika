package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.tool.Outcome
import kotlinx.serialization.json.JsonPrimitive

/**
 * 停止规则:任务什么时候、以什么结局结束(放弃、守卫叫停、模型出错、没给出回答、步数用完)。办完不在这里:
 * 不带工具调用的回复就是回答,由循环核对后交出。纯函数;结局里给用户看的说明不发给模型。
 */
internal object StopRules {
    /** 一个动作执行完之后:放弃了就按它结束;否则守卫([stop],屏幕守卫或不动屏幕的工具的守卫)叫停了就停。都没有为 null,接着做。 */
    fun afterAction(outcome: Outcome?, stop: GuardVerdict?): TaskState? = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stop?.let(::guard)
    }

    /** 步数用完后的最后一轮调了工具(只给了 end_not_done):放弃按它结束,否则算步数用完。 */
    fun afterFinalTurn(outcome: Outcome?, maxSteps: Int): TaskState = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stepLimit(maxSteps)
    }

    fun stepLimit(maxSteps: Int) = TaskState.Failed("Reached the $maxSteps-step limit; the task is not finished.", FailKind.StepLimit)

    /** 收尾请求本身失败或仍没交出结果时，按触发收尾的原因落结局，不谎报步数用完。 */
    fun closeoutFailed(reason: Closeout, maxSteps: Int): TaskState.Failed = when (reason) {
        Closeout.StepLimit -> stepLimit(maxSteps)
        Closeout.Completed -> TaskState.Failed("The task reached completion, but the model did not return a result.", FailKind.Model)
        Closeout.NoNewData -> TaskState.Failed("Stopped after ${NewDataTracker.LIMIT} consecutive steps with no new data.", FailKind.Guard)
    }

    /** 提醒过一次,模型仍然回了空话(或又被输出上限截断)。 */
    fun noAnswer() = TaskState.Failed("The model stopped without giving an answer.", FailKind.Model)

    /** 退回过一次,模型的工具调用又以文字形式回来了(见 [LeakedToolCall]):不把原样的标记当回答交出。 */
    fun toolCallAsText() = TaskState.Failed("The model's tool call was not recognised by the server and was returned as plain text, so it could not run.", FailKind.Model)

    /** 模型调用失败(重试过仍不行,或重试也没用)。 */
    fun modelFailed(e: Throwable): TaskState.Failed = when (e) {
        is ModelError.TaskBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.DailyBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError -> TaskState.Failed(userReason(e), FailKind.Model)
        else -> TaskState.Failed("Model call error: ${e.message ?: e::class.simpleName}", FailKind.Model)
    }

    fun guard(v: GuardVerdict): TaskState.Failed {
        val treeBlind = v.evidence["treeBlind"] == JsonPrimitive(true)
        val why = when (v.code) {
            "repeating_action" -> "the same action kept repeating with no effect"
            "no_progress" -> if (treeBlind) "the view hierarchy could not read the target position, and the screen showed no visible change after repeated actions, so it is unclear whether they took effect; stopped retrying"
                else "the screen showed no change after multiple actions"
            "read_loop" -> "the page did not change and repeated reads did not find what was needed"
            "too_many_failures" -> "actions failed repeatedly"
            "sandbox_loop" -> "the same command failed repeatedly without change"
            else -> "an action guard was triggered"
        }
        return TaskState.Failed("Stopped safely (${v.code}): $why.", FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth -> "The model service rejected the request: invalid API key or missing permission. Check in Settings that your API key is correct and authorised for this model."
    is ModelError.QuotaExceeded -> "Model account quota exhausted. Check your provider account balance or plan."
    is ModelError.TaskBudgetExceeded -> "Reached this task's budget limit of ${e.limit} tokens; the task is not finished."
    is ModelError.DailyBudgetExceeded ->
        "Provider \"${e.providerName}\" has reached today's budget limit of ${e.limit} tokens; the task is not finished. It will reset when the local calendar date changes."
    is ModelError.ModelNotFound -> {
        // 404 不一定是模型名不对(地址不对,或网关、中转服务说的别的情况):有原话时照原话说,不断定模型不存在
        val check = if (e.model.isEmpty()) "Check Settings › Models to make sure the model name and Base URL are correct."
            else "Go to Settings › Models and check the model name (enter the full model ID, e.g. gemini-flash-latest for Gemini) or choose the matching preset."
        when {
            e.detail.isNotEmpty() ->
                (if (e.model.isEmpty()) "The server replied: ${quoted(e.detail)}. " else "While calling model \"${e.model}\", the server replied: ${quoted(e.detail)}. ") +
                    "The model name may be wrong, or the service may be temporarily unavailable. $check"
            e.model.isEmpty() -> "Model not found. $check"
            else -> "Model \"${e.model}\" does not exist on this provider. $check"
        }
    }
    is ModelError.ModelNotAvailable ->
        "This account cannot use model \"${e.model.ifEmpty { "selected model" }}\" (not activated or lacks permission). Choose another model in Settings › Models, or enable it in your provider dashboard."
    is ModelError.ToolsUnsupported ->
        "Model \"${e.model.ifEmpty { "selected model" }}\" does not support tool calling, which Mika relies on to work. Choose a model that supports tool calling in Settings › Models."
    is ModelError.ContextTooLong -> "The conversation exceeds the model's context length. Switch to a model with a larger context window or break the task into smaller parts."
    is ModelError.ContentRefused -> "The model refused to process this content. Rephrase the request or try a different model."
    is ModelError.ReasoningEffortRejected ->
        "The model rejected the selected reasoning effort. Server reply: ${quoted(e.detail)}. Go to Settings › Models and set reasoning effort to Default or a supported level."
    is ModelError.RateLimited -> "Rate limited by the model service; still too many requests after retrying. Please try again later or check your account rate limits."
    is ModelError.Network -> "Failed to connect to the model service after retrying. Check your network connection and Base URL."
    is ModelError.Proxy -> when (e.failure) {
        ProxyFailure.Unreachable ->
            "Could not reach the network proxy, or the proxy did not forward the request after retrying. Check that the proxy is running and that the host and port in Settings › Network proxy are right; clear it if you do not need a proxy."
        ProxyFailure.AuthFailed -> "The network proxy rejected the username or password (HTTP 407). Check the username and password in the proxy URL under Settings › Network proxy."
    }
    is ModelError.Server -> "Model service error (HTTP ${e.status}). Please try again later."
    is ModelError.StreamCut ->
        "The connection was closed upstream before the model finished replying, even after retrying. This is usually caused by an unstable network or a short idle timeout on a relay or gateway (common during long model reasoning). Please try again later."
    is ModelError.BadResponse -> "${quoted(e.message.orEmpty())}. Check that the Base URL matches the API format."
    is ModelError.ChatGptLimitReached ->
        "ChatGPT usage limit reached. Try again later, check your usage in ChatGPT Settings › Usage (chatgpt.com/settings/usage) — on Plus, the 5-hour limit is shared with other apps signed in with ChatGPT — or switch to another model."
    is ModelError.ChatGptNotEligible ->
        "This account cannot use ChatGPT allowance in other apps (requires Plus or Pro, and must be allowed by your region and account settings).${if (e.detail.isEmpty()) "" else " Server message: ${quoted(e.detail)}."} You can switch to another model."
    is ModelError.SignInExpired -> "${e.account} sign-in expired. Please sign in with ${e.account} again in Settings › Models."
    is ModelError.AccountPlanNotAllowed ->
        "Your ${e.account} plan cannot use this endpoint (HTTP 403).${if (e.detail.isEmpty()) "" else " Server message: ${quoted(e.detail)}."} Use an API key for ${e.account} instead, or switch to another model in Settings › Models."
}

/** 引在句中的服务端原话:去掉末尾一个句号(原话自带的),由引它的句子补上自己的「。」;省略号(截短的「…」、「...」)不动。 */
private fun quoted(detail: String): String =
    when {
        detail.endsWith("..") -> detail
        detail.endsWith(".") || detail.endsWith("。") -> detail.dropLast(1)
        else -> detail
    }
