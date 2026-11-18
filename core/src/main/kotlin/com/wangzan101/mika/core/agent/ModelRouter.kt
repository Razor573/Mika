package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.decision.DecisionPolicy
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.tool.Verb

/** 决定下一步用哪个模型时看的上一步情况。 */
data class StepContext(
    val step: Int,
    val lastOk: Boolean,
    val lastHadGuard: Boolean,
    /** 上一步之后画面变没变;读屏一类不报时为 null。 */
    val lastChanged: Boolean?,
    /** 回放交还、批量停止、核查退回、接管或追问恢复。 */
    val lastWasRecovery: Boolean,
    val sameAppAsLast: Boolean,
    /** 要发出的上下文里有截图;不看图的轻量模型不能接。 */
    val hasImages: Boolean = false,
    val phaseId: Int = 0,
    val lastVerb: Verb? = null,
    val decisionScore: Double? = null,
    val decisionAllowedLight: Boolean = false,
)

/**
 * 实验开关 lightSteps 的分工:简单步给轻量模型,其余给主模型。
 * 轻量模型没配、不支持工具调用时恒用主模型。一旦在当前阶段遇到错误、守卫提示、画面没变、恢复步、决策低置信度或需主模型动作,
 * 本阶段余下步全部锁定使用主模型,直到切换前台 App/页面或显式开启新阶段([startNewPhase])。
 */
class ModelRouter(private val main: ChatModel, private val light: ChatModel?) {
    private var currentPhaseId = 0
    private var escalatedInPhase = false
    private var disabled = false

    /** 轻量模型能不能接手机步;不能时 lightSteps 不生效。 */
    val active: Boolean get() = !disabled && light != null && light.capabilities.toolCalls

    /** 轻量模型出了重试也没用的错(Key、额度、模型名、请求不对):余下都用主模型。一个 router 只服务一次任务。 */
    fun disable() { disabled = true }

    /** 显式进入新阶段:解除当前阶段的主模型锁定。 */
    fun startNewPhase() { escalatedInPhase = false }

    fun pick(ctx: StepContext): ChatModel {
        if (ctx.phaseId != currentPhaseId || (ctx.phaseId == 0 && !ctx.sameAppAsLast)) {
            currentPhaseId = ctx.phaseId
            escalatedInPhase = false
        }
        if (!ctx.lastOk || ctx.lastHadGuard || ctx.lastChanged == false || ctx.lastWasRecovery ||
            (ctx.decisionScore != null && ctx.decisionScore < DecisionPolicy.MIN_PROBABILITY) ||
            (ctx.lastVerb in DECISION_GATED_MAIN_VERBS && !ctx.decisionAllowedLight)
        ) {
            escalatedInPhase = true
        }
        if ((ctx.phaseId == 0 && !ctx.sameAppAsLast) || escalatedInPhase) return main
        val l = light?.takeIf { active } ?: return main
        val simple = ctx.step > 1 && (!ctx.hasImages || l.capabilities.vision)
        return if (simple) l else main
    }

    /** 出了状况(含轻量模型调用失败、回复被改派给主模型):当前阶段余下都用主模型。 */
    fun noteTrouble() { escalatedInPhase = true }

    fun isLight(m: ChatModel): Boolean = light != null && m === light

    companion object {
        /** 轻量模型给出这些调用(还有 do_steps)时不执行,这一步改问主模型:放弃、确认、接管、追问、沙箱命令都由主模型决定。 */
        private val MAIN_ONLY_VERBS = setOf(Verb.EndNotDone, Verb.RequestConfirmation, Verb.HandToUser, Verb.AskUser, Verb.HandleObstacle,
            Verb.PhoneRun, Verb.Collect, Verb.PhoneFind, Verb.PhoneDialog, Verb.PhoneGesture, Verb.PhoneCommand) +
            // 沙箱里跑的是模型写的命令:只让主模型写
            setOf(Verb.RunCommand, Verb.RunBundledScript, Verb.ReadFile, Verb.WriteFile, Verb.SearchFiles, Verb.ShareFile, Verb.ViewImage, Verb.ListFolderFiles)

        /** 需决策模型把关的动作:默认改派主模型,仅当决策模型高置信放行时才允许轻量模型继续。 */
        val DECISION_GATED_MAIN_VERBS = setOf(Verb.Describe, Verb.Swipe)

        /** 只能由主模型给出的调用([name] 是工具名,[verb] 是它做的动作)。 */
        @JvmOverloads
        fun mainOnly(name: String, verb: Verb?, decisionAllowedLight: Boolean = false): Boolean =
            name == DO_STEPS_TOOL || verb in MAIN_ONLY_VERBS || (verb in DECISION_GATED_MAIN_VERBS && !decisionAllowedLight)

        /** 这些动作之后的一步算恢复(等过用户)。 */
        internal val RECOVERY_VERBS = setOf(Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation)
        /** 这些动作会换前台 App 或网页(打开网址、换标签);之后的一步不算同一个地方。 */
        internal val APP_SWITCH_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.ShowAlarms, Verb.AddCalendarEvent, Verb.Navigate, Verb.Tabs)
    }
}

/** AgentLoop 用:记下每一步的情况,拼出下一步的 [StepContext]。 */
internal class StepTrack {
    private var ok = true
    private var guard = false
    private var changed: Boolean? = null
    private var recovery = false
    private var app: String? = null
    private var appBefore: String? = null
    private var switched = false
    private var phaseId = 0
    private var lastVerb: Verb? = null
    private var decisionScore: Double? = null
    private var decisionAllowedLight = false
    private var pendingDecisionScore: Double? = null
    private var pendingDecisionAllowedLight = false

    fun context(step: Int, hasImages: Boolean) = StepContext(
        step = step,
        lastOk = ok,
        lastHadGuard = guard,
        lastChanged = changed,
        lastWasRecovery = recovery,
        sameAppAsLast = !switched && app != null && app == appBefore,
        hasImages = hasImages,
        phaseId = phaseId,
        lastVerb = lastVerb,
        decisionScore = decisionScore,
        decisionAllowedLight = decisionAllowedLight,
    )

    /** 记下本步路由决策模型的打分和是否放行轻量模型,供下一步 [context] 读取。 */
    fun noteDecision(score: Double?, allowedLight: Boolean) {
        pendingDecisionScore = score
        pendingDecisionAllowedLight = allowedLight
    }

    /** [app] 为 null 表示这一步的结果没报前台 App,沿用之前的。 */
    /** 刚从回放交还、批量停止这类状况里恢复:下一步按恢复步算。在 [after] 之后调用。 */
    fun markRecovery() { recovery = true }

    fun after(verb: Verb?, ok: Boolean, guard: Boolean, changed: Boolean?, recovery: Boolean, app: String?) {
        this.ok = ok
        this.guard = guard
        this.changed = changed
        this.recovery = recovery || verb in ModelRouter.RECOVERY_VERBS
        appBefore = this.app
        if (app != null) this.app = app
        switched = verb in ModelRouter.APP_SWITCH_VERBS
        if (verb in ModelRouter.APP_SWITCH_VERBS || (app != null && appBefore != null && app != appBefore)) {
            phaseId++
        }
        lastVerb = verb
        decisionScore = pendingDecisionScore
        decisionAllowedLight = pendingDecisionAllowedLight
        pendingDecisionScore = null
        pendingDecisionAllowedLight = false
    }
}
