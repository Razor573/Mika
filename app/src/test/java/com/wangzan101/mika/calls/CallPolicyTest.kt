package com.wangzan101.mika.calls

import com.wangzan101.mika.i18n.AppLanguage
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CallPolicyTest {
    @Test fun blocksControlCodesAndNeverStripsDialingSyntax() {
        listOf("112", "911", "*#06#", "**21*123456789#", "123456789,22", "123456789;22", "tel:123456789",
            "123456789p1", "%2a123456789", "+1+5551234567").forEach {
            assertNull(it, CallPolicy.outgoingNumber(it))
        }
        assertEquals("+15551234567", CallPolicy.outgoingNumber(" +1 (555) 123-4567 "))
    }

    @Test fun allowlistFailsClosedAndOnlyExplicitAllAllowsHiddenCallers() {
        val config = CallConfig(enabled = true, autoAnswer = true, credentialsConfigured = true, audioVerified = true)
        assertFalse(CallPolicy.callerAllowed(config, "15551234567"))
        assertFalse(CallPolicy.callerAllowed(config, null))
        val restricted = config.copy(allowedIncomingNumbers = setOf("+15551234567"))
        assertTrue(CallPolicy.callerAllowed(restricted, "+1 (555) 123-4567"))
        assertFalse(CallPolicy.callerAllowed(restricted, "5551234567"))
        val all = config.copy(allowAllIncoming = true)
        assertTrue(CallPolicy.callerAllowed(all, null))
        assertFalse(CallPolicy.callerAllowed(all.copy(autoAnswer = false), null))
        assertFalse(CallPolicy.callerAllowed(all.copy(credentialsConfigured = false), null))
        assertFalse(CallPolicy.callerAllowed(all.copy(audioVerified = false), null))
    }

    @Test fun requiresExplicitSelectionOnMultiSimDevices() {
        val sim1 = CallSim("1", "SIM 1", true)
        val sim2 = CallSim("2", "SIM 2", false)
        assertEquals(sim1, CallPolicy.chooseSim(listOf(sim1), null))
        assertNull(CallPolicy.chooseSim(listOf(sim1, sim2), null))
        assertEquals(sim1, CallPolicy.chooseSim(listOf(sim1, sim2), "default"))
        assertEquals(sim2, CallPolicy.chooseSim(listOf(sim1, sim2), "2"))
        assertNull(CallPolicy.chooseSim(listOf(sim1.copy(isDefault = false), sim2), "default"))
        assertNull(CallPolicy.chooseSim(listOf(sim1), "missing"))
    }

    @Test fun mainlandMobileAllowlistAcceptsOnlyCompleteEquivalentNumbers() {
        val local = "13800138000"
        val enabled = CallConfig(enabled = true, autoAnswer = true, credentialsConfigured = true, audioVerified = true)
        for (allowed in listOf(local, "+8613800138000", "008613800138000")) {
            val config = enabled.copy(allowedIncomingNumbers = setOf(allowed))
            assertTrue(CallPolicy.callerAllowed(config, local))
            assertTrue(CallPolicy.callerAllowed(config, "+86 138 0013 8000"))
            assertTrue(CallPolicy.callerAllowed(config, "0086-138-0013-8000"))
            assertFalse(CallPolicy.callerAllowed(config, "800138000"))
            assertFalse(CallPolicy.callerAllowed(config, "8613800138000"))
            assertFalse(CallPolicy.callerAllowed(config, "+113800138000"))
            assertFalse(CallPolicy.callerAllowed(config, "13800138001"))
        }
        val all = enabled.copy(allowAllIncoming = true)
        assertFalse(CallPolicy.callerAllowed(all, "*#06#"))
        assertFalse(CallPolicy.callerAllowed(all, "13800138000;22"))
    }

    @Test fun delayedStartupCannotAuthorizeAnExistingCallOrBroadenItsPolicy() {
        val number = "13800138000"
        val ready = CallConfig(enabled = true, autoAnswer = true, credentialsConfigured = true,
            audioVerified = true, allowedIncomingNumbers = setOf("+8613800138000"))
        assertTrue(CallPolicy.canStartAutoAnswer(ready, ready, number, CallPolicy.AUTO_START_WINDOW_MS))
        assertFalse(CallPolicy.canStartAutoAnswer(ready, ready, number, CallPolicy.AUTO_START_WINDOW_MS + 1))
        assertFalse(CallPolicy.canStartAutoAnswer(ready.copy(autoAnswer = false), ready, number, 100L))
        assertFalse(CallPolicy.canStartAutoAnswer(ready.copy(allowedIncomingNumbers = emptySet()), ready, number, 100L))
        assertFalse(CallPolicy.canStartAutoAnswer(ready, ready.copy(allowedIncomingNumbers = emptySet()), number, 100L))
        assertFalse(CallPolicy.canStartAutoAnswer(ready, ready.copy(audioMode = CallAudioMode.REDIRECT), number, 100L))
    }

    @Test fun bypassWinsOverBothAllowAllAndExplicitAllowlist() {
        val ready = CallConfig(enabled = true, autoAnswer = true, credentialsConfigured = true, audioVerified = true,
            allowedIncomingNumbers = setOf("13800138000", "028-8123-4567"),
            bypassIncomingNumbers = setOf("+86 138 0013 8000", "0086 28 8123 4567"))
        for (allowAll in listOf(false, true)) {
            val config = ready.copy(allowAllIncoming = allowAll)
            for (number in listOf("13800138000", "008613800138000", "+8613800138000", "02881234567", "+862881234567")) {
                assertTrue(CallPolicy.isBypassed(config, number))
                assertFalse(CallPolicy.callerAllowed(config, number))
            }
        }
        for (number in listOf(null, "800138000", "81234567", "+113800138000", "13800138001")) {
            assertFalse(CallPolicy.isBypassed(ready, number))
        }
        assertTrue(CallPolicy.callerAllowed(ready.copy(allowAllIncoming = true), "13800138001"))
    }

    @Test fun pauseExpiresForNewCallsWithoutAnsweringCallsThatArrivedDuringIt() {
        val deadline = 100_000L
        val paused = CallConfig(enabled = true, autoAnswer = true, credentialsConfigured = true, audioVerified = true,
            allowAllIncoming = true, autoAnswerPausedUntilMs = deadline)
        val resumed = paused.copy(autoAnswerPausedUntilMs = null)
        assertEquals(1L, CallPolicy.autoAnswerPauseRemainingMs(paused, deadline - 1L))
        assertEquals(0L, CallPolicy.autoAnswerPauseRemainingMs(paused, deadline))
        assertFalse(CallPolicy.callerAllowed(paused, null, deadline - 1L))
        assertTrue(CallPolicy.callerAllowed(paused, null, deadline))
        assertFalse(CallPolicy.canStartAutoAnswer(paused, resumed, null, 1L, nowMs = deadline))
        assertFalse(CallPolicy.canStartAutoAnswer(resumed, paused, null, 1L, nowMs = deadline - 1L))
        assertTrue(CallPolicy.canStartAutoAnswer(paused, paused, null, 1L, nowMs = deadline + 1L))
    }

    @Test fun ownerRulesArePassedAsAnExplicitSectionOfTheBoundedCallPrompt() {
        val ownerRules = "Only if the caller mentions a parcel arrival, ask where it was left."
        val prompt = CallPolicy.prompt("Confirm caller name.", CallDirection.INCOMING, ownerRules)
        assertTrue(prompt.indexOf("Never ask for or disclose passwords") in 0 until prompt.indexOf(ownerRules))
        assertTrue(prompt.contains("Saved call rules from the owner"))
        assertTrue(prompt.contains("Confirm caller name."))
        assertFalse(CallPolicy.prompt("", CallDirection.INCOMING).contains("Saved call rules from the owner"))
    }

    @Test fun legacyGreetingMigratesOnlyTheExactOldDefault() {
        val legacyV1Default = "您好，我是 Mika 的 AI 电话助手。请问有什么需要我转告？"
        val legacyZhDefault = "您好，我是 Mika AI 电话助手。请问有什么需要我转告？"
        val customWithOldName = "您好，我是 Mika 的 AI 电话助手。请留下可回拨的时间。"
        val currentDefault = "Hello, I'm Mika, your AI phone assistant. Would you like me to pass on a message?"
        assertEquals(currentDefault, CallConfig().greeting)
        assertEquals(currentDefault, CallPolicy.migrateDefaultGreeting(legacyV1Default))
        assertEquals(currentDefault, CallPolicy.migrateDefaultGreeting(legacyZhDefault))
        assertEquals(currentDefault, CallPolicy.spokenGreeting(legacyV1Default, hasTask = false))
        assertEquals(currentDefault, CallPolicy.spokenGreeting(legacyZhDefault, hasTask = false))
        assertEquals(customWithOldName, CallPolicy.migrateDefaultGreeting(customWithOldName))
        assertEquals(customWithOldName, CallPolicy.spokenGreeting(customWithOldName, hasTask = false))
        assertEquals("Hello, I'm Mika, your AI phone assistant. Is now a convenient time to talk?",
            CallPolicy.spokenGreeting(currentDefault, hasTask = true))
    }

    @Test fun explicitGreetingIsUsedExactlyAndBlankMeansSilence() {
        for (hasTask in listOf(false, true)) {
            assertEquals("你好", CallPolicy.spokenGreeting("你好", hasTask))
            assertEquals("", CallPolicy.spokenGreeting("", hasTask))
            assertEquals("", CallPolicy.spokenGreeting(" \n\t", hasTask))
        }
    }

    @Test fun dialogueDoesNotDemandAnotherIntroductionAfterTheConfiguredGreeting() {
        for (direction in CallDirection.entries) {
            val prompt = CallPolicy.prompt("Keep replies brief.", direction)
            assertTrue(prompt.contains("Keep replies brief."))
            assertFalse(prompt.contains("Flash phone"))
            assertTrue(prompt.contains("State your identity truthfully in one sentence only if directly asked who is speaking"))
            assertTrue(prompt.contains("if asked 'Can you hear me?', give only a brief confirmation"))
            assertFalse(prompt.contains("I'm Mika, your AI phone assistant"))
        }
    }

    @Test fun callPromptFollowsConfiguredLanguageAndDefaultsToEnglish() {
        assertTrue(CallPolicy.prompt("", CallDirection.INCOMING).contains("Use brief, natural English."))
        assertTrue(CallPolicy.prompt("", CallDirection.INCOMING, language = AppLanguage.System)
            .contains("Use brief, natural English."))
        assertTrue(CallPolicy.prompt("", CallDirection.INCOMING, language = AppLanguage.SimplifiedChinese)
            .contains("Use brief, natural Simplified Chinese."))
        assertTrue(CallPolicy.prompt("", CallDirection.INCOMING, language = AppLanguage.Japanese)
            .contains("Use brief, natural Japanese."))
        assertEquals(AppLanguage.English, CallPolicy.resolveLocale(Locale.US))
        assertEquals(AppLanguage.TraditionalChinese, CallPolicy.resolveLocale(Locale.TAIWAN))
        assertEquals(AppLanguage.Japanese, CallPolicy.resolveLocale(Locale.JAPAN))
    }

    @Test fun explicitSimOverridesPreferenceAndMissingPreferenceNeverFallsBack() {
        val first = CallSim("sim-1", "SIM 1", true)
        val second = CallSim("sim-2", "SIM 2", false)
        val both = listOf(first, second)
        assertEquals(second, CallPolicy.chooseOutgoingSim(both, null, second.id))
        assertEquals(first, CallPolicy.chooseOutgoingSim(both, first.id, second.id))
        assertEquals(first, CallPolicy.chooseOutgoingSim(both, "default", second.id))
        assertEquals(first, CallPolicy.chooseOutgoingSim(both, "default", "sim-404"))
        assertNull(CallPolicy.chooseOutgoingSim(listOf(first), null, second.id))
        assertNull(CallPolicy.chooseOutgoingSim(both, "sim-404", second.id))
        assertNull(CallPolicy.chooseOutgoingSim(both, null, null))
    }

    @Test fun capturesAppOpBaselineWithoutGuessingOnUnknownOutput() {
        assertEquals("default", CallPolicy.previousAppOpMode("No operations.\n"))
        assertEquals("default", CallPolicy.previousAppOpMode("No operations.\nDefault mode: default\n"))
        assertNull(CallPolicy.previousAppOpMode("No operations.\nDefault mode: allow"))
        assertNull(CallPolicy.previousAppOpMode("No operations.\nPermission denial"))
        assertEquals("allow", CallPolicy.previousAppOpMode("MANAGE_ONGOING_CALLS: allow; time=1s"))
        assertEquals("ignore", CallPolicy.previousAppOpMode("Uid mode: MANAGE_ONGOING_CALLS: ignore"))
        assertNull(CallPolicy.previousAppOpMode("Permission denial"))
        assertNull(CallPolicy.previousAppOpMode("Uid mode: MANAGE_ONGOING_CALLS: deny\nMANAGE_ONGOING_CALLS: allow"))
    }
}
