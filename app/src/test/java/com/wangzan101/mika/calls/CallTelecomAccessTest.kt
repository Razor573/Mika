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

    @Test fun successfulCommandWithDefaultReadbackReportsSystemRejection() {
        val result = CallTelecomCommandOutcome.classify(0, "")
        val readBack = CallPolicy.previousAppOpMode("No operations.\nDefault mode: default")
        assertEquals("Attempted to grant permission, but the system read back call management permission as default, so call companion cannot be enabled",
            result.verificationFailure(readBack, false))
    }

    @Test fun defaultAppOpAcceptsAnEffectivePackageGrant() {
        assertEquals(null, CallTelecomCommandOutcome.OK.verificationFailure("default", true))
    }

    @Test fun failedReadbackIsNotReportedAsPermissionDenied() {
        assertEquals("Permission command ran, but call management permission status could not be read back; please try again",
            CallTelecomCommandOutcome.OK.verificationFailure(null, false))
    }

    @Test fun allowedAppOpStillRequiresTelecomPermission() {
        assertEquals("Call management permission read back as allow, but system telecom service still has not granted it, so call companion cannot be enabled",
            CallTelecomCommandOutcome.OK.verificationFailure("allow", false))
        assertEquals(null, CallTelecomCommandOutcome.OK.verificationFailure("allow", true))
    }

    @Test fun commandFailuresTakePriorityAndUnknownReadbackNeverLeaks() {
        for (outcome in listOf(CallTelecomCommandOutcome.FAILED, CallTelecomCommandOutcome.SHELL_APP_OPS_DENIED)) {
            assertEquals(outcome.appOpsFailureMessage, outcome.verificationFailure("allow", true))
        }
        assertEquals("Permission command ran, but call management permission status could not be read back; please try again",
            CallTelecomCommandOutcome.OK.verificationFailure("private-device-data", false))
        for (mode in listOf("ignore", "deny", "errored", "foreground")) {
            assertEquals("Attempted to grant permission, but the system read back call management permission as $mode, so call companion cannot be enabled",
                CallTelecomCommandOutcome.OK.verificationFailure(mode, true))
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
