package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelErrorReasonTest {
    @Test fun rateLimitHasStableFailureCode() {
        val failure = StopRules.modelFailed(ModelError.RateLimited(null))
        assertEquals(FailKind.Model, failure.kind)
        assertEquals(FailureCode.ModelRateLimited, failure.code)
        assertEquals(null, StopRules.modelFailed(ModelError.Network()).code)
    }

    /** 找不到模型:知道模型名时说清是哪个、去哪改、模型名大概长什么样;不知道时退回一般的说法。 */
    @Test fun modelNotFoundNamesTheModel() {
        assertEquals(
            "Model \"gemini\" does not exist on this provider. Go to Settings › Models and check the model name (enter the full model ID, e.g. gemini-flash-latest for Gemini) or choose the matching preset.",
            StopRules.modelFailed(ModelError.ModelNotFound("gemini")).reason,
        )
        assertEquals("Model not found. Check Settings › Models to make sure the model name and Base URL are correct.", StopRules.modelFailed(ModelError.ModelNotFound()).reason)
        assertEquals("Model \"gemini\" not found", ModelError.ModelNotFound("gemini").message)
    }

    /** 404 也可能是网关、中转服务在说别的:有原话时照原话说、不断定模型不存在,句末的点不重复;没有原话时和上面一样。 */
    @Test fun modelNotFoundCarriesWhatTheServerSaid() {
        assertEquals(
            "While calling model \"group/rpa\", the server replied: none of group/rpa's models is ready. The model name may be wrong, or the service may be temporarily unavailable. " +
                "Go to Settings › Models and check the model name (enter the full model ID, e.g. gemini-flash-latest for Gemini) or choose the matching preset.",
            StopRules.modelFailed(ModelError.ModelNotFound("group/rpa", "none of group/rpa's models is ready")).reason,
        )
        assertEquals(
            "The server replied: The model `x` does not exist. The model name may be wrong, or the service may be temporarily unavailable. Check Settings › Models to make sure the model name and Base URL are correct.",
            StopRules.modelFailed(ModelError.ModelNotFound("", "The model `x` does not exist.")).reason,
        )
        val said = StopRules.modelFailed(ModelError.ModelNotFound("m", "gone")).reason
        assertFalse(said.contains("does not exist"))
    }

    /** 引在句中的服务端原话,三种带原话的失败都只去掉末尾一个句号;截短的省略号不动。 */
    @Test fun quotedServerTextDropsOneTrailingPeriod() {
        fun reasons(detail: String) = listOf(
            StopRules.modelFailed(ModelError.ModelNotFound("m", detail)).reason,
            StopRules.modelFailed(ModelError.ChatGptNotEligible(detail)).reason,
            StopRules.modelFailed(ModelError.AccountPlanNotAllowed("ChatGPT", detail)).reason,
        )
        reasons("Not allowed.").forEach { assertTrue(it, it.contains("Not allowed.") && !it.contains("Not allowed..")) }
        reasons("不允许。").forEach { assertTrue(it, it.contains("不允许.") && !it.contains("不允许。。") && !it.contains("不允许。")) }
        reasons("Not allowed").forEach { assertTrue(it, it.contains("Not allowed.")) }
        reasons("Too long…").forEach { assertTrue(it, it.contains("Too long….")) }
        reasons("Wait...").forEach { assertTrue(it, it.contains("Wait....")) }
    }

    /** 这个账号用不了的模型、不支持工具的模型:说清是哪个、去哪换,或到服务商后台开通。 */
    @Test fun modelNotAvailableNamesTheModel() {
        assertEquals(
            "This account cannot use model \"qwen-max\" (not activated or lacks permission). Choose another model in Settings › Models, or enable it in your provider dashboard.",
            StopRules.modelFailed(ModelError.ModelNotAvailable("qwen-max", "not activated")).reason,
        )
        assertEquals(
            "Model \"gemma\" does not support tool calling, which Mika relies on to work. Choose a model that supports tool calling in Settings › Models.",
            StopRules.modelFailed(ModelError.ToolsUnsupported("gemma")).reason,
        )
    }

    /** 思考强度被服务端拒绝时保留参数错误并指向档位设置,不再误报 Base URL 或接口类型。 */
    @Test fun rejectedReasoningEffortPointsToTheActualSetting() {
        val detail = "Invalid value: 'ultra'. Supported values are: 'none', 'minimal', 'low', 'medium', 'high', 'xhigh', and 'max'."
        val reason = StopRules.modelFailed(ModelError.ReasoningEffortRejected(detail)).reason
        assertTrue(reason.contains(detail.dropLast(1)))
        assertTrue(reason.contains("set reasoning effort to Default"))
        assertFalse(reason.contains("Base URL"))
        assertFalse(reason.contains("API format"))
    }

    /** 断流有自己的说法、不提 Base URL;真的解析不了(格式对不上)才提示检查 Base URL 和接口类型。 */
    @Test fun streamCutIsNotBlamedOnConfiguration() {
        val cut = StopRules.modelFailed(ModelError.StreamCut()).reason
        assertTrue(cut.contains("closed upstream"))
        assertTrue(cut.contains("after retrying"))
        assertFalse(cut.contains("Base URL"))
        assertFalse(cut.contains("API format"))
        assertTrue(StopRules.modelFailed(ModelError.BadResponse("Stream response is not valid JSON")).reason.contains("Check that the Base URL matches the API format"))
    }
}
