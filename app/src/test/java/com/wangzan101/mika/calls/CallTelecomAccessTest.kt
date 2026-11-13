package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CallTelecomAccessTest {
    @Test fun recognizesExactShellPermissionDenialWithoutLeakingRawOutput() {
        val privateSuffix = "private-device-data"
        val output = """
            Exception occurred while executing 'set':
            java.lang.SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES. $privateSuffix
        """.trimIndent()
        for (exitCode in listOf(255, 0)) {
            val result = CallTelecomCommandOutcome.classify(exitCode, output)
            assertEquals(CallTelecomCommandOutcome.SHELL_APP_OPS_DENIED, result)
            assertEquals(
                "The system restricts ADB from changing call management permissions (missing MANAGE_APP_OPS_MODES), so call companion cannot be enabled",
                result.appOpsFailureMessage,
            )
            assertFalse(result.appOpsFailureMessage.contains(privateSuffix))
        }
    }

    @Test fun unknownFailuresDoNotSuggestShellAppOpsPermissionDenial() {
        listOf(
            "java.lang.SecurityException: uid 2000 does not have android.permission.GRANT_RUNTIME_PERMISSIONS.",
            "java.lang.SecurityException: uid 10123 does not have android.permission.MANAGE_APP_OPS_MODES.",
            "log: read MANAGE_APP_OPS_MODES",
            "Unknown command: set",
        ).forEach {
            val result = CallTelecomCommandOutcome.classify(255, it)
            assertEquals(CallTelecomCommandOutcome.FAILED, result)
            assertEquals("The system did not grant call management permission", result.appOpsFailureMessage)
        }
        assertEquals(CallTelecomCommandOutcome.OK, CallTelecomCommandOutcome.classify(0, ""))
    }
}
