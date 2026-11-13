package com.wangzan101.mika.calls

import kotlinx.serialization.Serializable
import java.util.Collections

/** The owner's objective for a single outgoing call. It does not record a business outcome. */
@Serializable
data class CallTaskBrief(
    val goal: String,
    val questions: List<String> = emptyList(),
    val context: String = "",
    val constraints: String = "",
) {
    /** Validate before dialing and detach the pending call from any mutable form inputs. */
    fun validatedSnapshot(): CallTaskBrief {
        val normalizedGoal = goal.trim()
        val normalizedContext = context.trim()
        val normalizedConstraints = constraints.trim()
        require(normalizedGoal.length in 1..MAX_GOAL_CHARS) { "Call goal must be 1–400 characters" }
        require(questions.size <= MAX_QUESTIONS) { "You can add at most 8 required questions for this call" }
        val normalizedQuestions = questions.map(String::trim)
        require(normalizedQuestions.all { it.length in 1..MAX_QUESTION_CHARS }) { "Each required question must be 1–200 characters" }
        require(normalizedContext.length <= MAX_CONTEXT_CHARS) { "Call context can be at most 1,000 characters" }
        require(normalizedConstraints.length <= MAX_CONSTRAINT_CHARS) { "Call boundaries can be at most 600 characters" }
        return CallTaskBrief(normalizedGoal, Collections.unmodifiableList(normalizedQuestions),
            normalizedContext, normalizedConstraints)
    }

    override fun toString(): String = "CallTaskBrief(questions=${questions.size})"

    companion object {
        const val MAX_GOAL_CHARS = 400
        const val MAX_QUESTIONS = 8
        const val MAX_QUESTION_CHARS = 200
        const val MAX_CONTEXT_CHARS = 1_000
        const val MAX_CONSTRAINT_CHARS = 600
    }
}
