package com.wangzan101.mika.core.model.decision

import kotlinx.serialization.json.JsonElement

/** A bounded judgment over options supplied by the caller; it never generates or executes actions. */
fun interface DecisionModel {
    suspend fun choose(
        state: JsonElement,
        instructions: String,
        options: Map<String, String>,
        applicability: String?,
    ): DecisionChoice
}

/** [probability] is the chosen option's probability, not the distribution's confidence statistic. */
data class DecisionChoice(val choice: String, val probability: Double, val applicable: Double? = null)

object DecisionPolicy {
    const val MIN_PROBABILITY = 0.85
    const val MIN_APPLICABLE = 0.85
}

/** Fixed messages only: provider replies and transport exceptions can contain request content or keys. */
sealed class DecisionError(message: String) : Exception(message) {
    class InvalidRequest : DecisionError("Decision request configuration is invalid")
    class InvalidResponse : DecisionError("Decision model returned an invalid choice or probability")
    class Network : DecisionError("Decision model network connection failed or timed out")
    class Http(val status: Int) : DecisionError("Decision model request failed (HTTP $status)")
}
