package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.action.ConfirmDecision
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.LOCATED_REF
import com.wangzan101.mika.core.tool.PAGE_REF_RE
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.uistate.DESCRIBED_ELEMENT
import com.wangzan101.mika.core.uistate.DESCRIBED_ROLE
import com.wangzan101.mika.core.uistate.DescribedMatch
import com.wangzan101.mika.core.uistate.DescribedTarget
import com.wangzan101.mika.core.uistate.ElementQuery
import com.wangzan101.mika.core.uistate.interfaces
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal sealed interface LightValidation {
    data class Accepted(val call: Part.ToolCall, val reply: ModelReply) : LightValidation
    data class Rejected(
        val reason: String,
        val validatorReason: String,
        val tool: String?,
        val decisionScore: Double? = null,
    ) : LightValidation
}

/**
 * 轻量模型回复的宽松校验器:
 * - 接受任何合法、已提供、非特权的工具调用(即使与主模型可能选择的工具或元素略有不同,也不因输出 token 超过 200 而拒绝);
 * - 仅在无工具调用、工具未提供/参数不合法、特权或不可逆操作、目标元素不存在时拒绝并升级到主模型。
 */
internal object LightCallValidator {
    private val DESCRIBED_CONFLICT_KEYS = listOf("ref", "snapshotId", "x", "y")

    suspend fun validate(
        reply: ModelReply?,
        specs: List<ToolSpec>,
        tools: ToolDispatcher,
        autonomous: Boolean,
        retried: Boolean = false,
    ): LightValidation {
        if (reply == null) {
            return LightValidation.Rejected("light_error", "model_error", null)
        }
        val actionCalls = reply.parts.filterIsInstance<Part.ToolCall>().filter { it.name != PROGRESS_CALL }
        val call = actionCalls.firstOrNull()
            ?: return LightValidation.Rejected(
                reason = "no_tool",
                validatorReason = if (retried) "no_tool_after_retry" else "no_tool",
                tool = null,
            )

        actionCalls.firstOrNull { ac ->
            val d = tools.declared(ac.name)
            val v = d?.verb ?: tools.verb(ac.name)
            ModelRouter.mainOnly(ac.name, v)
        }?.let { unsafe ->
            return LightValidation.Rejected("unsafe_tool", "main_only_tool:${unsafe.name}", unsafe.name)
        }

        val declared = tools.declared(call.name)
        val verb = declared?.verb ?: tools.verb(call.name)

        val offeredNames = specs.map { it.name }.toSet()
        if ((declared == null && call.name !in offeredNames) || (specs.isNotEmpty() && call.name !in offeredNames)) {
            return LightValidation.Rejected("invalid_tool", "tool_not_offered:${call.name}", call.name)
        }

        val rawArgsObj = try {
            val raw = call.argsJson.trim()
            if (raw.isEmpty()) JsonObject(emptyMap())
            else Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return LightValidation.Rejected("invalid_tool", "invalid_args_json:${call.name}", call.name)

        val session = declared?.capability?.let { tools.interfaces.sessions[it] }
            ?: runCatching { tools.interfaces.session }.getOrNull()
        val lastSnap = session?.lastSnapshot
        val refArg = rawArgsObj.argStr("ref")
        val argsObj = if (
            refArg != null &&
            !LOCATED_REF.matches(refArg) &&
            rawArgsObj["snapshotId"] == null &&
            lastSnap != null &&
            lastSnap.elements.any { it.ref == refArg }
        ) {
            JsonObject(rawArgsObj + ("snapshotId" to JsonPrimitive(lastSnap.snapshotId)))
        } else {
            rawArgsObj
        }

        val parsedArgs = if (declared != null) {
            try {
                Args(declared.def(tools.ctx), argsObj)
            } catch (_: ArgError) {
                return LightValidation.Rejected("invalid_tool", "invalid_args:${call.name}", call.name)
            }
        } else null

        if (refArg != null) {
            if (LOCATED_REF.matches(refArg)) {
                return LightValidation.Rejected("unsafe_tool", "located_ref:$refArg", call.name)
            }
            if (!PAGE_REF_RE.matches(refArg)) {
                return LightValidation.Rejected("invalid_tool", "invalid_ref:$refArg", call.name)
            }
            if (argsObj["snapshotId"] == null) {
                // Only reject missing snapshotId if we also could not find the ref in the current screen snapshot
                val currentSnap = lastSnap ?: runCatching { session?.peek() }.getOrNull()
                if (currentSnap == null) {
                    return LightValidation.Rejected("invalid_tool", "missing_snapshot_id:${call.name}", call.name)
                }
                if (currentSnap.elements.none { it.ref == refArg }) {
                    return LightValidation.Rejected("missing_target", "target_not_found:$refArg", call.name)
                }
            } else {
                val givenSid = argsObj.argStr("snapshotId")
                val snapForSid = tools.interfaces.snapshotFor(declared, argsObj)
                val snap = snapForSid ?: lastSnap ?: runCatching { session?.peek() }.getOrNull()
                if (snap != null) {
                    if (givenSid != null && snapForSid == null && snap.snapshotId != givenSid) {
                        return LightValidation.Rejected("missing_target", "snapshot_mismatch:$givenSid", call.name)
                    }
                    if (snap.elements.none { it.ref == refArg }) {
                        return LightValidation.Rejected("missing_target", "target_not_found:$refArg", call.name)
                    }
                }
            }
        }

        val elementText = argsObj.argStr(DESCRIBED_ELEMENT)
        if (elementText != null) {
            if ((verb != Verb.Tap && verb != Verb.TypeText) ||
                elementText.isBlank() ||
                DESCRIBED_CONFLICT_KEYS.any { it in argsObj }
            ) {
                return LightValidation.Rejected("invalid_tool", "invalid_element_args:${call.name}", call.name)
            }
            val snap = lastSnap ?: runCatching { session?.peek() }.getOrNull()
            if (snap != null) {
                val match = DescribedTarget.match(
                    snap.elements,
                    ElementQuery(elementText, argsObj.argStr(DESCRIBED_ROLE), field = verb == Verb.TypeText),
                )
                if (match !is DescribedMatch.Found) {
                    return LightValidation.Rejected("missing_target", "target_element_not_found:$elementText", call.name)
                }
                val resolvedArgs = JsonObject(
                    argsObj.filterKeys { it != DESCRIBED_ELEMENT && it != DESCRIBED_ROLE } +
                        mapOf("ref" to JsonPrimitive(match.element.ref), "snapshotId" to JsonPrimitive(snap.snapshotId)),
                )
                if (!autonomous && ConfirmDecision.isIrreversibleTarget(tools, call.name, resolvedArgs, snap)) {
                    return LightValidation.Rejected("unsafe_tool", "irreversible_target:${call.name}", call.name)
                }
            }
        }

        if (verb == Verb.Tap && refArg == null && elementText == null) {
            if (argsObj["x"] == null || argsObj["y"] == null) {
                return LightValidation.Rejected("invalid_tool", "missing_tap_target:${call.name}", call.name)
            }
            val x = try { parsedArgs?.coord("x") } catch (_: ArgError) { null }
            val y = try { parsedArgs?.coord("y") } catch (_: ArgError) { null }
            if (x == null || y == null || x !in 0..999 || y !in 0..999) {
                return LightValidation.Rejected("invalid_tool", "invalid_coordinates:${argsObj["x"]},${argsObj["y"]}", call.name)
            }
            val snap = lastSnap ?: runCatching { session?.peek() }.getOrNull()
            if (snap != null && ConfirmDecision.isUnresolvedTarget(tools, call.name, argsObj, snap)) {
                return LightValidation.Rejected("missing_target", "target_not_found:$x,$y", call.name)
            }
        }

        if (!autonomous && ConfirmDecision.mayBeIrreversible(tools, call.name, argsObj)) {
            return LightValidation.Rejected("unsafe_tool", "irreversible_target:${call.name}", call.name)
        }

        val normalizedCall = if (argsObj === rawArgsObj) call else call.copy(argsJson = argsObj.toString())
        val normalizedReply = if (normalizedCall === call) reply else reply.copy(
            parts = reply.parts.map { if (it === call) normalizedCall else it },
        )
        return LightValidation.Accepted(normalizedCall, normalizedReply)
    }
}
