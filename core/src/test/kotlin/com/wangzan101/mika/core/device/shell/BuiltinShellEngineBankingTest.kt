package com.wangzan101.mika.core.device.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuiltinShellEngineBankingTest {
    private class StubPlatform(
        val helper: MutableStateFlow<Any?>,
        var pairedFlag: Boolean = false,
    ) : BuiltinShellPlatform {
        var usbFlag = false
        var currentTransport: ShellTransport = ShellTransport.Auto
        var shizuku: ShizukuPlatformState = ShizukuPlatformState.Unavailable
        var launchGate: CompletableDeferred<Unit>? = null
        var autoAttachOnLaunch = true
        val calls = mutableListOf<String>()

        override fun paired(): Boolean = pairedFlag
        override fun setPaired(paired: Boolean) { pairedFlag = paired }
        override fun wirelessDebugging(): Boolean = true
        override fun wifiConnected(): Boolean = true
        override suspend fun findPairingPort(timeoutMs: Long): Int? = null
        override fun pair(port: Int, code: String) { calls += "pair $code" }
        override suspend fun launchHelper() {
            calls += "launch"
            launchGate?.await()
            if (autoAttachOnLaunch) {
                helper.value = "helper-${calls.size}"
            }
        }
        override fun stopHelper() {
            calls += "stop"
            helper.value = null
        }
        override fun forget() { calls += "forget" }
        override fun usbStarted(): Boolean = usbFlag
        override fun setUsbStarted(on: Boolean) { usbFlag = on }
        override fun transport(): ShellTransport = currentTransport
        override fun setTransport(transport: ShellTransport) { currentTransport = transport }
        override fun shizukuState(): ShizukuPlatformState = shizuku
    }

    private fun TestScope.makeEngine(paired: Boolean = false, initialHelper: Any? = null, persistedPaused: () -> Boolean = { false }): Pair<BuiltinShellEngine, StubPlatform> {
        val helper = MutableStateFlow(initialHelper)
        val platform = StubPlatform(helper, pairedFlag = paired)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = BuiltinShellEngine(
            platform = platform,
            helper = helper,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            io = dispatcher,
            graceMs = 0,
            persistedPaused = persistedPaused,
        )
        return engine to platform
    }

    @Test
    fun pauseStopsHelperAndWatchdogWithoutForgetting() = runTest {
        val (e, platform) = makeEngine(paired = true, initialHelper = "first")
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)

        e.pause()
        assertEquals(BuiltinShellStatus.Paused, e.status.value)
        assertTrue(e.paused)
        assertTrue(e.paired)
        assertEquals(listOf("stop"), platform.calls.filter { it == "stop" })
        assertFalse(platform.calls.any { it == "forget" })

        advanceUntilIdle()
        e.need()
        e.retry()
        e.wifiAvailable()
        e.wirelessDebuggingChanged()
        advanceUntilIdle()
        assertEquals(0, platform.calls.count { it == "launch" })
        assertEquals(BuiltinShellStatus.Paused, e.status.value)
    }

    @Test
    fun resumeReconnectsWithSavedPairing() = runTest {
        val (e, platform) = makeEngine(paired = true, initialHelper = "first")
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)

        e.pause()
        assertEquals(BuiltinShellStatus.Paused, e.status.value)
        e.resume()
        assertFalse(e.paused)
        advanceUntilIdle()
        assertEquals(1, platform.calls.count { it == "launch" })
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertFalse(platform.calls.any { it.startsWith("pair ") })
    }

    @Test
    fun pauseBeforeStartKeepsHelperStopped() = runTest {
        val (e, platform) = makeEngine(paired = true)
        e.pause()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Paused, e.status.value)
        assertEquals(0, platform.calls.count { it == "launch" })

        e.resume()
        advanceUntilIdle()
        assertEquals(1, platform.calls.count { it == "launch" })
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun resumeWithoutPairingDoesNotLaunch() = runTest {
        val (e, platform) = makeEngine(paired = false)
        e.pause()
        e.resume()
        advanceUntilIdle()
        assertEquals(0, platform.calls.count { it == "launch" })
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
    }

    @Test
    fun pauseDuringInFlightLaunchStopsLateAttachingHelper() = runTest {
        val (e, platform) = makeEngine(paired = true)
        val gate = CompletableDeferred<Unit>()
        platform.launchGate = gate
        platform.autoAttachOnLaunch = false

        e.start()
        runCurrent()
        assertEquals(1, platform.calls.count { it == "launch" })

        e.pause()
        assertEquals(BuiltinShellStatus.Paused, e.status.value)
        assertEquals(1, platform.calls.count { it == "stop" })

        gate.complete(Unit)
        runCurrent()
        platform.helper.value = "late-helper"
        advanceUntilIdle()

        assertTrue(platform.calls.count { it == "stop" } >= 2)
        assertNull(platform.helper.value)
        assertEquals(BuiltinShellStatus.Paused, e.status.value)
    }

    @Test
    fun shizukuChangedAndSelectTransportWhilePausedKeepPausedStatus() = runTest {
        val (e, platform) = makeEngine(paired = false)
        platform.currentTransport = ShellTransport.Auto
        platform.shizuku = ShizukuPlatformState(installed = true, running = false, permissionGranted = false)
        e.start()
        advanceUntilIdle()

        e.pause()
        assertEquals(BuiltinShellStatus.Paused, e.status.value)

        e.shizukuChanged()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Paused, e.status.value)

        e.selectTransport(ShellTransport.Shizuku)
        advanceUntilIdle()
        assertEquals(ShellTransport.Shizuku, platform.currentTransport)
        assertEquals(BuiltinShellStatus.Paused, e.status.value)

        e.shizukuChanged()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Paused, e.status.value)
        assertEquals(0, platform.calls.count { it == "launch" })
    }

    @Test
    fun persistedPauseFlagGatesWatchdogFromStartupAndBlocksDiscoveryAndPairing() = runTest {
        var persisted = true
        val (e, platform) = makeEngine(paired = true, persistedPaused = { persisted })
        assertEquals(BuiltinShellStatus.Paused, e.status.value)
        assertTrue(e.paused)

        e.start()
        advanceUntilIdle()
        assertEquals(0, platform.calls.count { it == "launch" })
        assertEquals(BuiltinShellStatus.Paused, e.status.value)

        e.need()
        e.shizukuChanged()
        e.startDiscovery()
        e.startPairing("123456", 37000)
        advanceUntilIdle()
        assertEquals(0, platform.calls.count { it == "launch" })
        assertEquals(0, platform.calls.count { it.startsWith("pair ") })
        assertEquals(PairingDiscovery.Idle, e.pairingDiscovery.value)
        assertEquals(BuiltinShellStatus.Paused, e.status.value)

        persisted = false
        e.resume()
        advanceUntilIdle()
        assertEquals(1, platform.calls.count { it == "launch" })
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }
}
