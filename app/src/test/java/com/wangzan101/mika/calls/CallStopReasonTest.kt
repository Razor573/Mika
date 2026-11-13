package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStopReasonTest {
    @Test fun closingMediaAfterTakeoverDoesNotReplaceHistoryOutcome() {
        val stop = CallStopReason()
        val takeover = "AI stopped; the system dialler is handling the call"
        assertTrue(stop.record(takeover))
        assertFalse(stop.record("AI audio connection failed; the system Phone app is handling the call"))
        stop.refine("Reached the AI call duration limit", "Duration limit reached; Android hung up")
        assertEquals(takeover, stop.value)
    }

    @Test fun aGenuineMediaFailureStaysRecordedWhenCallLaterEnds() {
        val stop = CallStopReason()
        val mediaFailure = "AI audio connection failed; the system Phone app is handling the call"
        assertTrue(stop.record(mediaFailure))
        assertFalse(stop.record("Call ended"))
        assertEquals(mediaFailure, stop.value)
    }

    @Test fun timeoutMayBeRefinedWithItsOwnTelecomResult() {
        val stop = CallStopReason()
        val timeout = "Reached the AI call duration limit"
        assertTrue(stop.record(timeout))
        stop.refine(timeout, "Duration limit reached; Android hung up")
        assertEquals("Duration limit reached; Android hung up", stop.value)
    }
}
