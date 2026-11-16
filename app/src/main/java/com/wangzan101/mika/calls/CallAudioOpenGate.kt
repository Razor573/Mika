package com.wangzan101.mika.calls

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Only null authorizes another attempt: it must mean the helper allocated no audio resources.
 * The caller adopts any successful descriptors before returning, including across cancellation.
 * This bounds retry starts; it cannot impose a hard timeout on an already running Binder call.
 */
internal suspend fun <T : Any> awaitCallAudioOpen(
    isCurrent: () -> Boolean,
    nowMs: () -> Long,
    attempt: suspend () -> T?,
): T {
    val startedAt = nowMs()
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CallMediaFailure("Call state or mute changed; AI did not join call audio")
        val beforeAttempt = nowMs() - startedAt
        if (beforeAttempt !in 0L until AUDIO_OPEN_WINDOW_MS) {
            throw CallMediaFailure("Carrier call audio did not become ready in time; handed back to the system")
        }
        // Deliberately do not catch: permission, routing, IPC and allocation faults are not retryable.
        val opened = attempt()
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CallMediaFailure("Call state or mute changed; AI did not join call audio")
        if (opened != null) return opened
        val elapsed = nowMs() - startedAt
        if (elapsed !in 0L until AUDIO_OPEN_WINDOW_MS) {
            throw CallMediaFailure("Carrier call audio did not become ready in time; handed back to the system")
        }
        delay(minOf(AUDIO_OPEN_RETRY_MS, AUDIO_OPEN_WINDOW_MS - elapsed))
    }
}

private const val AUDIO_OPEN_WINDOW_MS = 1_500L
private const val AUDIO_OPEN_RETRY_MS = 50L
