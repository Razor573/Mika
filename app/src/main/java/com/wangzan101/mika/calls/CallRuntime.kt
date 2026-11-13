package com.wangzan101.mika.calls

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.instructions.CallInstructionStore
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.core.model.ModelRole
import java.util.UUID

data class CallActionResult(val ok: Boolean, val message: String)

data class CallRuntimeState(
    val prepared: Boolean = false,
    val phase: CallPhase = CallPhase.IDLE,
    val direction: CallDirection? = null,
    val callId: String? = null,
    val number: String? = null,
    val aiActive: Boolean = false,
    val detail: String? = null,
    val pendingAutoAnswer: Boolean = false,
    val autoAnswerStage: CallAutoAnswerStage = CallAutoAnswerStage.OFF,
    val autoAnswerRemainingSeconds: Int? = null,
    val autoAnswerPauseRemainingSeconds: Long? = null,
    val autoAnswerBlockedReason: String? = null,
    val liveTranscript: List<CallTranscriptLine> = emptyList(),
    val historyWriteFailed: Boolean = false,
    val simOptions: List<CallSim> = emptyList(),
    val shellConnected: Boolean = false,
    val notificationsAvailable: Boolean = false,
    val audioReady: Boolean = false,
    val audioDetail: String? = null,
    val canAnswer: Boolean = false,
    val canHangUp: Boolean = false,
    val canTakeOver: Boolean = false,
    val backgroundStandbyRunning: Boolean = false,
    val backgroundStandbyStarting: Boolean = false,
    val backgroundStandbyBlockedReason: String? = null,
    val lockedDialRequestId: String? = null,
    val lockedDialRemainingSeconds: Int? = null,
    val lockedDialPreparing: Boolean = false,
    val lockedDialDetail: String? = null,
) {
    override fun toString(): String =
        "CallRuntimeState(prepared=$prepared, phase=$phase, direction=$direction, aiActive=$aiActive, pendingAutoAnswer=$pendingAutoAnswer)"
}

/** Owns one AI attempt at a time; the default dialer continues to own all carrier calls. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class CallRuntime(private val app: MikaApp) {
    val settings = CallSettings.create(app)
    val instructions = CallInstructionStore.create(app)
    private val access = CallTelecomAccess(app)
    private val telecom get() = app.getSystemService(TelecomManager::class.java)
    private val telephony get() = app.getSystemService(TelephonyManager::class.java)
    private val notifications = CallNotifications(app)
    private val records = CallHistory(app)
    val history = records.state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val accessLock = Mutex()
    private val mutable = MutableStateFlow(CallRuntimeState())
    val state = mutable.asStateFlow()
    private val changes = MutableStateFlow(0L)
    private val tracked = linkedMapOf<Call, Tracked>()
    private var sims: List<CarrierSim> = emptyList()
    private var shell: IMikaShell? = null
    private var armed = false
    private var configuring = false
    private var detail: String? = null
    private var work: Work? = null
    private var audioReady = false
    private var audioDetail: String? = null
    private var probeEpoch = 0
    private var unobservedManagedCall = false
    private var autoPauseWriting = false
    private var standbyService: CallStandbyService? = null
    private var standbyStarting = false
    private var standbyStartError: String? = null
    private var standbyIncomingRequested = false
    private var standbyStartId: String? = null
    private var standbyStartTimeout: Job? = null
    private val standbyRunning = MutableStateFlow(false)
    private val lockedDialGate = LockedDialGate(SystemClock::elapsedRealtime)
    private var lockedDial: PendingLockedDial? = null
    private var lockedDialDetail: String? = null
    private var liveTranscriptCallId: String? = null
    private val liveTranscript = mutableListOf<CallTranscriptLine>()


    init {
        scope.launch {
            app.phone.hub.shell.collect { current ->
                if (shell != null && shell !== current) {
                    lockedDial?.let { finishLockedDial(it, "Built-in assistant disconnected; locked-screen dialling was not run") }
                    cancelWork("Built-in assistant disconnected; the system Phone app is handling the call")
                }
                shell = current
                audioReady = false
                audioDetail = null
                publish()
                if (settings.state.value.enabled && current != null) probeAudio()
                maybeStartAutoAnswer()
            }
        }
        scope.launch {
            var previousMode: CallAudioMode? = null
            var previousEnabled = false
            settings.state.collect { config ->
                lockedDial?.takeIf { !config.enabled || !config.credentialsConfigured || config.audioMode != it.config.audioMode }
                    ?.let { finishLockedDial(it, "Call settings changed; locked-screen dialling was not run") }
                if (!hasAuthorizedIncomingStandby()) standbyService?.revokeIncomingAuthorization()
                stopBackgroundStandbyIfUnneeded()
                val active = work
                if (active != null && (!config.enabled || !config.credentialsConfigured ||
                        config.audioMode != active.config.audioMode ||
                        (active.automatic && !active.answerSent && !CallPolicy.callerAllowed(config, active.track?.number)))) {
                    cancelWork("AI call settings changed; the system Phone app is handling the call")
                }
                if (config.audioMode != previousMode || config.enabled != previousEnabled) {
                    audioReady = false
                    audioDetail = null
                    previousMode = config.audioMode
                    previousEnabled = config.enabled
                    publish()
                    if (config.enabled && shell != null) probeAudio()
                }
                publish()
                maybeStartAutoAnswer()
            }
        }
        scope.launch {
            var previous = settings.credentialsRevision.value
            settings.credentialsRevision.collect { current ->
                if (previous != current) {
                    lockedDial?.let { finishLockedDial(it, "Volcengine credentials changed; locked-screen dialling was not run") }
                    cancelWork("Volcengine credentials changed; the system Phone app is handling the call")
                }
                previous = current
            }
        }
        scope.launch {
            records.load()
            if (!records.writeFailed.value) {
                records.state.value.filter { it.summaryStatus == SummaryStatus.PENDING }
                    .forEach { summarizeCall(it.id, it.transcript) }
            }
        }
        scope.launch { records.writeFailed.collect { publish() } }
        scope.launch(Dispatchers.IO) { instructions.load() }
        scope.launch {
            // While the UI is hidden, only one expiry wake-up is needed for the standby notification.
            val pause = settings.state.map { config ->
                config.autoAnswerPausedUntilMs.takeIf { config.enabled && config.autoAnswer }
            }.distinctUntilChanged()
            combine(pause, app.foreground.visible, standbyRunning) { until, visible, running -> Triple(until, visible, running) }
                .collectLatest { (until, visible, running) ->
                    cancelPendingIfStandbyLost()
                    publish()
                    if (until == null) return@collectLatest
                    if (!visible) {
                        if (running) {
                            delay((until - System.currentTimeMillis()).coerceAtLeast(0L))
                            publish()
                        }
                        return@collectLatest
                    }
                    while (true) {
                        val remaining = until - System.currentTimeMillis()
                        if (remaining <= 0L) {
                            publish()
                            break
                        }
                        mutable.value = mutable.value.copy(autoAnswerPauseRemainingSeconds = 1L + (remaining - 1L) / 1000L)
                        delay((remaining - 1L) % 1000L + 1L)
                    }
                }
        }
        scope.launch {
            accessLock.withLock { armed = withContext(Dispatchers.IO) { access.ready() } }
            readSims()
            publish()
            maybeStartAutoAnswer()
        }
        scope.launch {
            val resume = CallStandbyResumeGate()
            // The app can reopen before the on-device audio helper finishes reconnecting.
            // Reevaluate when readiness changes; Android only permits this start while visible.
            combine(app.foreground.visible, state) { visible, _ -> visible }.collect { visible ->
                val eligible = visible && !standbyStarting && standbyService?.stickyAutoAnswer != true &&
                    work == null && lockedDial == null && !unobservedManagedCall && liveCalls().isEmpty() &&
                    hasAuthorizedIncomingStandby() && interactiveForeground() &&
                    autoAnswerBlocker(settings.state.value) == null &&
                    CallStandbyService.notificationsAvailable(app, create = true)
                if (resume.take(visible, eligible)) requestCallForegroundService(incoming = true)
            }
        }
    }

    /** Initial authorization comes only from this explicit action in the unlocked phone UI. */
    suspend fun startBackgroundStandby(): CallActionResult = onMain {
        val config = settings.state.value
        if (!interactiveForeground() || !userUnlocked()) return@onMain fail("Unlock your phone and start background standby from the Calls screen")
        if (!CallStandbyPolicy.optedIn(config, userUnlocked())) return@onMain fail("Enable automatic answering and complete call setup first")
        autoAnswerBlocker(config)?.let { return@onMain fail(it) }
        if (!CallStandbyService.notificationsAvailable(app, create = true)) return@onMain fail(tr(R.string.calls_standby_notifications_required))
        if (isIncomingStandbyRunning()) return@onMain ok("Background standby started")
        if (standbyStarting) return@onMain fail("Android is starting background standby")
        if (!settings.authorizeBackgroundStandby()) return@onMain fail("Could not save background answer authorisation; please try again")
        if (!requestCallForegroundService(incoming = true)) return@onMain fail(standbyStartError ?: "Background standby did not start")
        state.first { !it.backgroundStandbyStarting }
        if (isIncomingStandbyRunning()) ok("Background standby started") else fail(standbyStartError ?: "Background standby did not start")
    }

    private fun requestCallForegroundService(incoming: Boolean): Boolean {
        if (standbyStarting) return !incoming || standbyIncomingRequested
        standbyIncomingRequested = incoming
        standbyStarting = true
        standbyStartError = null
        val startId = UUID.randomUUID().toString()
        standbyStartId = startId
        publish()
        if (runCatching {
            val intent = CallStandbyService.startIntent(app, startId)
            if (standbyService != null) app.startService(intent) else app.startForegroundService(intent)
        }.getOrNull() == null) {
            onStandbyStartFailed(tr(R.string.calls_standby_start_blocked))
            return false
        }
        // This deadline belongs to the runtime, even if the initiating screen is dismissed.
        standbyStartTimeout = scope.launch {
            delay(STANDBY_START_MS)
            if (standbyStarting) {
                standbyStartTimeout = null
                onStandbyStartFailed("Android has not confirmed background standby startup yet; please try again")
            }
        }
        return true
    }

    private fun isStandbyOptedIn(): Boolean = CallStandbyPolicy.optedIn(settings.state.value, userUnlocked())

    internal fun hasAuthorizedIncomingStandby(): Boolean = isStandbyOptedIn() && settings.state.value.backgroundStandbyAuthorized

    private fun isIncomingStandbyRunning(): Boolean = standbyService?.stickyAutoAnswer == true && hasAuthorizedIncomingStandby()

    internal fun hasLockedDialForegroundWork(): Boolean = userUnlocked() && settings.state.value.enabled &&
        (lockedDial != null || work?.lockedDialTicket != null)

    internal fun isStandbyStartRequested(requestId: String?): Boolean = standbyStarting && requestId != null && requestId == standbyStartId

    internal fun isIncomingStandbyStartRequested(): Boolean = standbyIncomingRequested

    internal fun onStandbyStarted(service: CallStandbyService) {
        standbyStartTimeout?.cancel()
        standbyStartTimeout = null
        standbyService = service
        standbyStarting = false
        standbyIncomingRequested = false
        standbyStartId = null
        standbyStartError = null
        standbyRunning.value = true
        publish()
    }

    internal fun onStandbyStartFailed(reason: String) {
        standbyStartTimeout?.cancel()
        standbyStartTimeout = null
        standbyStarting = false
        standbyIncomingRequested = false
        standbyStartId = null
        standbyStartError = reason
        lockedDial?.let { finishLockedDial(it, "Background call service did not start; locked-screen dialling was not run") }
        publish()
    }

    internal fun onStandbyStopped(service: CallStandbyService) {
        if (standbyService !== service) return
        standbyService = null
        standbyRunning.value = false
        lockedDial?.let { finishLockedDial(it, "Background call service stopped; locked-screen dialling was not run") }
        if (work?.let { (it.lockedDialTicket != null && it.placed) || (it.automatic && it.answerSent) } == true && !interactiveForeground()) {
            cancelWork("Background call service stopped; the system Phone app is handling the call")
        }
        cancelPendingIfStandbyLost()
        publish()
    }

    private fun stopBackgroundStandby() {
        val running = standbyService != null
        standbyStartTimeout?.cancel()
        standbyStartTimeout = null
        standbyStarting = false
        standbyIncomingRequested = false
        standbyStartId = null
        standbyService = null
        standbyRunning.value = false
        // Android can crash the process if a startForegroundService request is stopped before
        // it is acknowledged with startForeground. An in-flight request acknowledges then stops itself.
        if (running) runCatching { app.stopService(CallStandbyService.startIntent(app)) }
        cancelPendingIfStandbyLost()
    }

    private fun stopBackgroundStandbyIfUnneeded() {
        if (!hasLockedDialForegroundWork() && (standbyService != null || standbyStarting) &&
            !(hasAuthorizedIncomingStandby() && (standbyService?.stickyAutoAnswer == true || standbyIncomingRequested))) {
            stopBackgroundStandby()
        }
    }

    private fun cancelPendingIfStandbyLost() {
        if (!canAutomaticallyAnswerHere() && work?.let { it.automatic && !it.answerSent && it.stopReason.value == null } == true) {
            cancelWork("Background standby is unavailable; the system Phone app is handling this incoming call")
        }
    }

    private fun userUnlocked(): Boolean =
        runCatching { app.getSystemService(UserManager::class.java)?.isUserUnlocked == true }.getOrDefault(false)

    private fun interactiveForeground(): Boolean = runCatching {
        CallStandbyPolicy.interactiveForeground(
            visible = app.foreground.visible.value,
            interactive = app.getSystemService(PowerManager::class.java)?.isInteractive == true,
            keyguardShowing = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false,
        )
    }.getOrDefault(false)

    private fun keyguardLocked(): Boolean =
        runCatching { app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true }.getOrDefault(false)

    private fun canAutomaticallyAnswerHere(): Boolean = CallStandbyPolicy.allowsAutomaticAnswer(
        userUnlocked = userUnlocked(), interactiveForeground = interactiveForeground(), serviceRunning = isIncomingStandbyRunning(),
        notificationsAllowed = standbyService != null && CallStandbyService.notificationsAvailable(app),
    )

    suspend fun pauseAutoAnswer(minutes: Int): CallActionResult = onMain {
        if (minutes !in 1..CallPolicy.MAX_AUTO_ANSWER_PAUSE_MINUTES) {
            return@onMain fail("Pause duration must be between 1 minute and 24 hours")
        }
        if (!settings.state.value.enabled || !settings.state.value.autoAnswer) {
            return@onMain fail("Automatic answering is not enabled")
        }
        saveAutoPause(System.currentTimeMillis() + minutes * 60_000L)
    }

    suspend fun resumeAutoAnswer(): CallActionResult = onMain { saveAutoPause(null) }

    private suspend fun saveAutoPause(until: Long?): CallActionResult {
        if (autoPauseWriting) return fail("Auto-answer settings are saving")
        autoPauseWriting = true
        if (until != null) {
            // Existing calls handled by AI stay connected; a pending auto-answer is left alone.
            if (work?.let { it.automatic && !it.answerSent } == true) {
                cancelWork("Automatic answering is paused; the system Phone app is handling this incoming call")
            }
            liveCalls().filter { it.phase == CallPhase.RINGING }.forEach { it.autoTried = true }
        }
        val saved = try {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    synchronized(settings) {
                        settings.save(settings.state.value.copy(autoAnswerPausedUntilMs = until))
                    }
                }.isSuccess
            }
        } finally {
            autoPauseWriting = false
            publish()
        }
        return if (!saved) fail("Could not save auto-answer pause settings")
        else ok(if (until == null) "Auto-answer resumed" else "Auto-answer paused")
    }

    suspend fun setHistoryHandled(id: String, handled: Boolean): CallActionResult = onMain {
        historyResult(records.setHandled(id, handled), if (handled) "Marked as handled" else "Marked for follow-up")
    }

    suspend fun deleteHistory(id: String): CallActionResult = onMain {
        historyResult(records.delete(id), tr(R.string.calls_history_deleted))
    }

    suspend fun clearHistory(): CallActionResult = onMain {
        historyResult(records.clear(), tr(R.string.calls_history_cleared))
    }

    private fun historyResult(result: CallHistory.Result, message: String): CallActionResult = when (result) {
        CallHistory.Result.SAVED -> ok(message)
        CallHistory.Result.NOT_FOUND -> fail("This call record no longer exists")
        CallHistory.Result.WRITE_FAILED -> fail("Could not save call history changes; please try again")
    }

    suspend fun refresh(): CallActionResult = onMain {
        accessLock.withLock { armed = withContext(Dispatchers.IO) { access.ready() } }
        readSims()
        if (settings.state.value.enabled && work == null && liveCalls().isEmpty()) probeAudio()
        unobservedManagedCall = settings.state.value.enabled && liveCalls().isEmpty() && managedCallPresent() == true
        publish()
        maybeStartAutoAnswer()
        if (state.value.prepared) ok("System call management permission is available; connection status follows actual call callbacks")
        else fail(tr(R.string.calls_needs_setup))
    }

    suspend fun prepare(): CallActionResult = onMain {
        accessLock.withLock {
            if (!settings.state.value.enabled) return@withLock report("Enable AI calls first")
            if (work != null || liveCalls().isNotEmpty()) return@withLock report("Prepare AI calls when no call is active")
            if (!access.supported) return@withLock report("Call companion mode requires Android 12 or above, the primary user, and carrier calling")
            if (!access.hasPhonePermissions()) return@withLock report("Allow Phone permissions for Mika")
            if (managedCallPresent() != false) return@withLock report("Android is in a call or cannot confirm it is idle; try preparing again shortly")
            val helper = shell ?: return@withLock report(tr(R.string.calls_setup_shell))
            configuring = true
            publish()
            try {
                // The small transaction must run its rollback even when its UI caller leaves.
                val error = withContext(NonCancellable + Dispatchers.IO) { access.prepare(helper) }
                if (!settings.state.value.enabled) {
                    withContext(NonCancellable + Dispatchers.IO) { access.rollback(helper) }
                    armed = false
                    return@withLock report(tr(R.string.calls_disabled))
                }
                armed = error == null && access.ready()
                readSims()
                if (error != null) return@withLock report(error)
                probeAudio()
                detail = "Call companion is configured; device call audio support still needs separate verification"
                ok(detail.orEmpty())
            } finally {
                configuring = false
                publish()
                maybeStartAutoAnswer()
            }
        }
    }

    suspend fun stop(): CallActionResult = onMain {
        accessLock.withLock {
            armed = false
            configuring = true
            val active = cancelWork("AI calls disabled; the system Phone app is handling calling")
            lockedDial?.let { finishLockedDial(it, "AI calls disabled; locked-screen dialling was not run") }
            stopBackgroundStandby()
            publish()
            val saved: Boolean
            val audioRestored: Boolean
            val restored: Boolean
            try {
                val stopped = withContext(NonCancellable) {
                    val savedSettings = withContext(Dispatchers.IO) {
                        runCatching { settings.save(settings.state.value.copy(enabled = false, autoAnswer = false)) }.isSuccess
                    }
                    val cleanedAudio = awaitCleanup(active)
                    val restoredAccess = withContext(Dispatchers.IO) { access.rollback(shell) }
                    Triple(savedSettings, cleanedAudio, restoredAccess)
                }
                saved = stopped.first
                audioRestored = stopped.second
                restored = stopped.third
            } finally {
                configuring = false
                publish()
            }
            if (!saved || !restored || !audioRestored) {
                report("AI stopped; system setup or audio restore is not yet confirmed. Connect the built-in assistant and try disabling again")
            } else {
                detail = "AI calls are disabled; the system dialler works as usual"
                publish()
                ok(detail.orEmpty())
            }
        }
    }

    suspend fun dial(number: String, simId: String? = null, task: CallTaskBrief? = null): CallActionResult = onMain {
        val taskSnapshot = try {
            task?.validatedSnapshot()
        } catch (e: IllegalArgumentException) {
            return@onMain report(e.message ?: "Invalid call task brief")
        }
        prerequisite()?.let { return@onMain report(it) }
        if (lockedDial != null) return@onMain report("Cancel the scheduled locked-screen dial first")
        val normalized = CallPolicy.outgoingNumber(number) ?: return@onMain report("Only standard phone numbers are supported; emergency numbers, short codes, and control codes are not allowed")
        if (isEmergency(normalized) != false) return@onMain report("Emergency numbers or numbers that cannot be confirmed as safe cannot be dialled")
        if (liveCalls().isNotEmpty() || work != null || managedCallPresent() != false) {
            return@onMain report("A call is already in progress or idle state could not be confirmed; did not dial")
        }
        readSims()
        prerequisite()?.let { return@onMain report(it) }
        if (lockedDial != null) return@onMain report("Cancel the scheduled locked-screen dial first")
        if (liveCalls().isNotEmpty() || work != null || managedCallPresent() != false) {
            return@onMain report("A call is already in progress or idle state could not be confirmed; did not dial")
        }
        val config = settings.state.value
        val picked = CallPolicy.chooseOutgoingSim(sims.map { it.info }, simId, config.defaultOutgoingSimId)
            ?: return@onMain report(when {
                simId == null && config.defaultOutgoingSimId != null -> "Saved default outgoing SIM is unavailable; please choose another"
                sims.isEmpty() -> "No SIM available for carrier calling"
                else -> "Choose a valid SIM, or explicitly select the system default SIM"
            })
        val sim = sims.single { it.info.id == picked.id }
        val attempt = Work(CallDirection.OUTGOING, config, requestedNumber = normalized, sim = sim, task = taskSnapshot)
        startWork(attempt)
        awaitResult(attempt)
    }

    /** Takes effect only after the owner confirms this specific task while the app is visible. */
    suspend fun queueLockedDial(number: String, simId: String? = null, task: CallTaskBrief): CallActionResult = onMain {
        if (!interactiveForeground() || !userUnlocked()) return@onMain fail("Unlock your phone and confirm this call from the Calls screen")
        if (!secureDeviceLock()) return@onMain fail("Set a PIN, pattern, or password screen lock first")
        if (lockedDial != null) return@onMain fail("A locked-screen dial is already scheduled; cancel it first")
        val snapshot = try { task.validatedSnapshot() } catch (e: IllegalArgumentException) {
            return@onMain fail(e.message ?: "Invalid call task brief")
        }
        prerequisite()?.let { return@onMain fail(it) }
        val normalized = CallPolicy.outgoingNumber(number) ?: return@onMain fail("Only standard phone numbers are supported; emergency numbers, short codes, and control codes are not allowed")
        if (isEmergency(normalized) != false) return@onMain fail("Emergency numbers or numbers that cannot be confirmed as safe cannot be dialled")
        if (!lockedDialIdle()) return@onMain fail("A call is already in progress or idle state could not be confirmed; did not schedule dial")
        readSims()
        if (!withContext(Dispatchers.IO) { access.ready() }) return@onMain fail("System call management permission is unavailable")
        if (!interactiveForeground() || !userUnlocked() || !secureDeviceLock()) {
            return@onMain fail("Confirm the scheduled locked-screen dial while your phone is unlocked")
        }
        prerequisite()?.let { return@onMain fail(it) }
        if (lockedDial != null || !lockedDialIdle()) return@onMain fail("Another dial is already scheduled or a call is active; did not schedule dial")
        val config = settings.state.value
        val picked = CallPolicy.chooseOutgoingSim(sims.map { it.info }, simId, config.defaultOutgoingSimId)
            ?: return@onMain fail(if (simId == null && config.defaultOutgoingSimId != null) "Saved default outgoing SIM is unavailable; please choose another"
                else "Choose a valid SIM, or explicitly select an available system default SIM")
        val sim = sims.single { it.info.id == picked.id }
        if (!CallStandbyService.notificationsAvailable(app, create = true)) return@onMain fail("Allow background standby notifications so locked-screen dialling can be cancelled")
        val ticket = lockedDialGate.arm(normalized, picked.id, snapshot) ?: return@onMain fail("Could not schedule this locked-screen dial")
        val pending = PendingLockedDial(ticket, config, sim, settings.credentialsRevision.value)
        lockedDial = pending
        lockedDialDetail = "Locked-screen dial scheduled; lock your screen within 45 seconds"
        // A short one-shot lock keeps the confirmation window ticking as the screen turns off.
        // There is no indefinite standby wake lock and no attempt to wake or unlock the display.
        pending.wakeLock = runCatching {
            val power = checkNotNull(app.getSystemService(PowerManager::class.java))
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mika:LockedDial").apply {
                setReferenceCounted(false)
                acquire((ticket.expiresAtMs - SystemClock.elapsedRealtime() + 1_000L).coerceIn(1L, 46_000L))
            }
        }.getOrNull()
        if (pending.wakeLock == null) {
            finishLockedDial(pending, "Could not hold the locked-screen dial confirmation window; did not schedule dial")
            return@onMain fail(lockedDialDetail.orEmpty())
        }
        if (standbyService == null && !requestCallForegroundService(incoming = false)) {
            return@onMain fail(lockedDialDetail ?: "Background call service did not start")
        }
        pending.job = scope.launch(start = CoroutineStart.LAZY) { observeLockedDial(pending) }
        pending.job?.start()
        publish()
        if (lockedDial === pending) ok("Locked-screen dial scheduled; Android has not dialled yet")
        else fail(lockedDialDetail ?: "This locked-screen dial was not scheduled")
    }

    suspend fun cancelLockedDial(requestId: String): CallActionResult = onMain {
        val pending = lockedDial?.takeIf { it.ticket.id == requestId }
            ?: return@onMain fail("This locked-screen dial has ended or was already submitted to Android")
        finishLockedDial(pending, "Locked-screen dial cancelled")
        ok("Locked-screen dial cancelled")
    }

    private fun secureDeviceLock(): Boolean =
        runCatching { app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)

    private fun lockedDialIdle(pending: PendingLockedDial? = null): Boolean =
        (work == null || pending != null && work?.lockedDialTicket === pending.ticket) &&
            liveCalls().isEmpty() && managedCallPresent() == false

    private fun lockedDialEligible(pending: PendingLockedDial): Boolean = userUnlocked() && secureDeviceLock() &&
        (standbyService != null || standbyStarting) && settings.state.value.audioMode == pending.config.audioMode &&
        settings.credentialsRevision.value == pending.credentialsRevision &&
        prerequisite(ignoreWork = true) == null && CallStandbyService.notificationsAvailable(app) && lockedDialIdle(pending)

    private suspend fun observeLockedDial(pending: PendingLockedDial) {
        while (lockedDial === pending) {
            val remaining = pending.ticket.expiresAtMs - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                finishLockedDial(pending, "Locked-screen dial schedule expired; did not dial")
                return
            }
            val deviceLocked = keyguardLocked()
            val eligible = lockedDialEligible(pending)
            if (pending.claimed) {
                if (!deviceLocked || !eligible || standbyService == null) {
                    finishLockedDial(pending, "Phone was unlocked or call conditions changed; locked-screen dialling was not run")
                    return
                }
            } else when (val decision = lockedDialGate.poll(pending.ticket, deviceLocked && standbyService != null, eligible)) {
                LockedDialGate.Decision.WaitingForLock -> Unit
                is LockedDialGate.Decision.Rejected -> {
                    finishLockedDial(pending, if (decision.reason == LockedDialGate.Rejection.EXPIRED) "Locked-screen dial schedule expired; did not dial"
                        else "Call conditions changed; locked-screen dialling was not run")
                    return
                }
                is LockedDialGate.Decision.Claimed -> {
                    pending.claimed = true
                    lockedDialDetail = "Phone locked; preparing to dial"
                    val request = decision.request
                    startWork(Work(CallDirection.OUTGOING, pending.config, requestedNumber = request.number,
                        sim = pending.sim, task = request.task, lockedDialTicket = pending.ticket))
                }
            }
            if (mutable.value.lockedDialRemainingSeconds != remainingSeconds(pending)) publish()
            delay(minOf(250L, remaining))
        }
    }

    private fun remainingSeconds(pending: PendingLockedDial): Int =
        ((pending.ticket.expiresAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L).div(1_000L).toInt()

    private fun finishLockedDial(pending: PendingLockedDial, reason: String, cancelUnplacedWork: Boolean = true) {
        if (lockedDial !== pending) return
        lockedDial = null
        lockedDialGate.cancel(pending.ticket)
        pending.job?.cancel()
        runCatching { pending.wakeLock?.takeIf { it.isHeld }?.release() }
        pending.wakeLock = null
        lockedDialDetail = reason
        if (cancelUnplacedWork && work?.let { it.lockedDialTicket === pending.ticket && !it.placed } == true) cancelWork(reason)
        stopBackgroundStandbyIfUnneeded()
        publish()
    }

    private fun validateLockedDial(w: Work) {
        val ticket = w.lockedDialTicket ?: return
        val pending = lockedDial?.takeIf { it.ticket === ticket && it.claimed }
            ?: throw CallProblem("Locked-screen dial cancelled; did not dial")
        when (lockedDialGate.submissionRejection(ticket) {
            standbyService != null && lockedDialEligible(pending) && keyguardLocked()
        }) {
            LockedDialGate.Rejection.EXPIRED -> throw CallProblem("Locked-screen dial schedule expired; did not dial")
            null -> Unit
            else -> throw CallProblem("Phone was unlocked or call conditions changed; locked-screen dialling was not run")
        }
    }

    suspend fun answer(expectedCallId: String? = null): CallActionResult = onMain {
        prerequisite()?.let { return@onMain report(it) }
        val call = liveCalls().singleOrNull()
        if (expectedCallId != null && call?.id != expectedCallId) return@onMain report(CALL_CHANGED)
        if (call == null || call.direction != CallDirection.INCOMING || call.phase != CallPhase.RINGING ||
            call.suppressed || !carrierCall(call.call)) {
            return@onMain report("No single incoming carrier call available to answer")
        }
        work?.let {
            if (it.track === call && it.direction == CallDirection.INCOMING && it.stopReason.value == null) {
                // An explicit answer for this exact call takes priority over a scheduled answer.
                if (!it.answerSent) {
                    it.manualAnswerRequested = true
                    it.autoDelayEndsAt = null
                    it.skipAutoDelay.complete(Unit)
                    detail = tr(R.string.calls_notification_answer_preparing)
                    publish()
                }
                return@onMain awaitResult(it)
            }
            return@onMain report("An AI call is already in progress")
        }
        val attempt = Work(CallDirection.INCOMING, settings.state.value, track = call, expectedCallId = expectedCallId)
        startWork(attempt)
        awaitResult(attempt)
    }

    suspend fun hangup(expectedCallId: String? = null): CallActionResult = onMain {
        if (!settings.state.value.enabled || !armed) return@onMain report(tr(R.string.calls_needs_setup))
        val call = liveCalls().singleOrNull()
        if (expectedCallId != null && call?.id != expectedCallId) return@onMain report(CALL_CHANGED)
        if (call == null || call.phase == CallPhase.DISCONNECTING || !carrierCall(call.call)) {
            return@onMain report("No single carrier call available to hang up")
        }
        call.suppressed = true
        val active = cancelWork("Hung up by user")
        val cleaned = awaitCleanup(active)
        val endedMessage = if (cleaned) "Android confirmed the call has ended" else "Android confirmed the call has ended; audio restore is not yet confirmed"
        if (confirmedEnded(call)) return@onMain ok(endedMessage)
        if (!isCurrentCall(call, expectedCallId) || call.phase == CallPhase.DISCONNECTING) return@onMain report(CALL_CHANGED)
        if (runCatching {
                if (call.phase == CallPhase.RINGING) call.call.reject(false, null) else call.call.disconnect()
            }.isFailure) return@onMain report("Android could not send the hang-up request")
        val ended = waitFor(CallPolicy.CONTROL_TIMEOUT_MS) { confirmedEnded(call) } || confirmedEnded(call)
        if (ended) ok(endedMessage) else report("Android has not confirmed that the call ended; check the system Phone screen")
    }

    suspend fun cancelAutoAnswer(expectedCallId: String? = null): CallActionResult = onMain {
        val current = work
        val target = current?.track
        if (current == null || target == null || !isPendingAutoAnswer(current, target) ||
            !isCurrentCall(target, expectedCallId)) {
            return@onMain fail("No active auto-answer to cancel")
        }
        val active = cancelWork("AI answer cancelled")
        if (!awaitCleanup(active)) return@onMain fail("AI answer cancelled; cleanup is not yet confirmed")
        if (isCurrentCall(target, expectedCallId) && target.phase == CallPhase.RINGING) ok("AI answer cancelled; the phone continues ringing")
        else ok("AI answer cancelled")
    }

    suspend fun takeover(): CallActionResult = takeover(null)

    internal suspend fun takeover(callId: String?): CallActionResult = onMain {
        val current = work
        if (current == null || (callId != null && callId != current.id)) return@onMain fail("This AI call has already ended")
        current.track?.suppressed = true
        val active = cancelWork(if (current.pendingRing) "AI answer cancelled" else "AI stopped; the system dialler is handling the call")
        if (awaitCleanup(active)) ok(detail ?: "AI stopped")
        else report("AI stopped; call audio restore is not yet confirmed. Check the system Phone screen")
    }

    internal fun onCallAdded(service: MikaInCallService, call: Call) {
        unobservedManagedCall = false
        tracked.getOrPut(call) { Tracked(call, service) }
        onCallChanged(service, call)
    }

    internal fun onCallChanged(service: MikaInCallService, call: Call) {
        val t = tracked[call]?.takeIf { it.service === service } ?: return
        if (t.phase == CallPhase.RINGING && t.initialRingPolicy == null) {
            t.initialRingPolicy = settings.state.value
            t.ringObservedAt = SystemClock.elapsedRealtime()
            t.ringObservedWallMs = System.currentTimeMillis()
            if (autoPauseWriting || !canAutomaticallyAnswerHere()) t.autoTried = true
        }
        val calls = liveCalls()
        if (calls.isNotEmpty()) lockedDial?.takeUnless { work?.lockedDialTicket === it.ticket && work?.placed == true }
            ?.let { finishLockedDial(it, "Another call detected; locked-screen dialling was not run") }
        if (calls.size > 1) calls.filter { it.phase == CallPhase.RINGING }.forEach { it.autoTried = true }
        val active = work
        if (active != null) {
            if (active.track == null && active.placed && matchesOutgoing(active, t)) {
                active.track = t
            }
            val target = active.track
            if (target === t && active.direction == CallDirection.OUTGOING &&
                call.state in setOf(Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_ACTIVE)) {
                if (active.lockedDialTicket != null) lockedDialDetail = "Android confirmed locked-screen dialling"
                active.result.complete(ok("Android confirmed dialling; check live status to see if it connects"))
            }
            when {
                calls.size > 1 -> cancelWork("Multiple calls detected; handed back to the system dialler")
                target === t && disconnected(call) -> cancelWork(callEndMessage(t))
                target === t && active.direction == CallDirection.INCOMING && !active.answerSent && t.phase != CallPhase.RINGING ->
                    cancelWork(if (t.phase == CallPhase.ACTIVE || t.phase == CallPhase.CONNECTING || t.phase == CallPhase.HOLDING)
                        "The system Phone app handled the call before AI stepped in" else "Incoming call stopped ringing; AI did not answer")
                target === t && active.direction == CallDirection.INCOMING && !active.answerSent &&
                    (t.direction != CallDirection.INCOMING || !carrierCall(t.call)) ->
                    cancelWork("Incoming call changed; AI did not answer")
                target === t && active.automatic && !active.answerSent &&
                    !CallPolicy.callerAllowed(settings.state.value, t.number) -> cancelWork("Incoming call no longer matches auto-answer rules")
                target === t && t.phase != CallPhase.ACTIVE && active.bridging ->
                    cancelWork("Call switched; AI audio stopped")
            }
        }
        publish()
        maybeStartAutoAnswer()
    }

    private fun maybeStartAutoAnswer() {
        if (work != null || lockedDial != null || autoPauseWriting || !canAutomaticallyAnswerHere()) return
        val t = liveCalls().singleOrNull() ?: return
        val initial = t.initialRingPolicy ?: return
        if (t.phase != CallPhase.RINGING || t.direction != CallDirection.INCOMING || t.suppressed || t.autoTried) return
        val config = settings.state.value
        if (!CallPolicy.canStartAutoAnswer(initial, config, t.number,
                SystemClock.elapsedRealtime() - t.ringObservedAt,
                ringObservedWallMs = t.ringObservedWallMs)) return
        if (autoAnswerBlocker(config) != null || !t.service.tracks(t.call) || !carrierCall(t.call)) return
        t.autoTried = true
        startWork(Work(CallDirection.INCOMING, config, track = t, auto = true))
    }

    internal fun onCallRemoved(service: MikaInCallService, call: Call) {
        val removed = tracked[call]?.takeIf { it.service === service } ?: return
        tracked.remove(call)
        if (work?.track === removed) cancelWork(
            if (confirmedEnded(removed)) callEndMessage(removed) else "Android call state disconnected; the system Phone app is handling the call",
        )
        publish()
    }

    internal fun onTelecomMuteChanged(service: MikaInCallService, muted: Boolean) {
        val current = work ?: return
        if (current.track?.service === service && current.muteLease?.observeTelecomMute(muted) == true) {
            cancelWork("System call mute was lifted; AI handed the call back")
        }
    }

    private fun startWork(attempt: Work) {
        check(work == null)
        work = attempt
        detail = tr(
            if (attempt.automatic) R.string.calls_answer_pending else R.string.calls_notification_preparing,
        )
        if (attempt.automatic) {
            attempt.autoDelayEndsAt = SystemClock.elapsedRealtime() + attempt.config.autoAnswerDelaySeconds * 1_000L
        }
        attempt.job = scope.launch(start = CoroutineStart.LAZY) { runWork(attempt) }
        publish()
        attempt.job?.start()
    }

    private suspend fun waitAutoAnswerDelay(w: Work) {
        while (w.automatic) {
            val remaining = (w.autoDelayEndsAt ?: break) - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            withTimeoutOrNull(minOf(1_000L, remaining)) { w.skipAutoDelay.await() }
            publish()
        }
        w.autoDelayEndsAt = null
        detail = tr(R.string.calls_notification_answer_preparing)
        publish()
    }

    private suspend fun runWork(w: Work) {
        try {
            if (!notifications.show(w.id, detail.orEmpty(), ringing = w.direction == CallDirection.INCOMING)) {
                throw CallProblem("AI call notifications must be allowed so you can cancel or take over at any time")
            }
            if (!interactiveForeground()) acquireCallWakeLock(w, 20_000L)
            if (w.automatic) waitAutoAnswerDelay(w)
            prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
            validateTarget(w)
            val helper = shell ?: throw CallProblem("Built-in Mika assistant is not connected")
            val credentials = withContext(Dispatchers.IO) { settings.credentials() }
                ?: throw CallProblem("Volcengine credentials are unavailable; please save them again")
            if (!probeAudio()) throw CallProblem(audioDetail ?: "Two-way call audio is unavailable on this device")
            if (shell !== helper || w.config.audioMode != settings.state.value.audioMode) {
                throw CallProblem("Call audio settings changed; please try again")
            }
            validateTarget(w)
            w.mediaStage = MediaStage.VOLC_CONNECT
            val ownerRules = withContext(Dispatchers.IO) {
                runCatching { instructions.promptSnapshot() }
                    .getOrElse { throw CallProblem("Could not read call instructions; AI did not handle the call") }
            }
            validateTarget(w)
            val session = withTimeoutOrNull(5_000L) {
                VolcRealtimeClient().connect(credentials, VolcRealtimeConfig(
                    systemPrompt = CallPolicy.prompt(
                        w.config.systemPrompt,
                        w.direction,
                        ownerRules,
                        task = w.task,
                        language = CallPolicy.resolveLanguage(app),
                    ),
                    speaker = w.config.speaker,
                ))
            } ?: throw CallProblem("Volcengine realtime voice connection timed out; AI did not handle the call")
            w.session = session
            if (!session.setMuted(true)) throw CallProblem("Volcengine realtime voice did not become ready")
            if (!withContext(Dispatchers.IO) { access.ready() }) {
                armed = false
                throw CallProblem("System call management permission is unavailable; AI did not handle the call")
            }
            prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
            validateTarget(w)
            w.mediaStage = MediaStage.TELECOM_CONNECT
            if (w.direction == CallDirection.INCOMING) {
                val target = w.track ?: throw CallProblem("Incoming call has ended")
                w.answerSent = true
                w.telecomActionIssued = true
                detail = "Waiting for Android to confirm answering"
                if (runCatching { target.call.answer(VideoProfile.STATE_AUDIO_ONLY) }.isFailure) {
                    throw CallProblem("Android could not send the answer request")
                }
                publish()
            } else {
                if (liveCalls().isNotEmpty() || managedCallPresent() != false) throw CallProblem("A call is already in progress or idle state could not be confirmed; did not dial")
                val sim = w.sim ?: throw CallProblem("No SIM available")
                val number = w.requestedNumber ?: throw CallProblem("No phone number available")
                // Preparing the realtime session takes time; do not let Telecom fall back to a
                // different account if the chosen SIM disappeared in the meantime.
                readSims()
                if (w.lockedDialTicket != null && !withContext(Dispatchers.IO) { access.ready() }) {
                    armed = false
                    throw CallProblem("System call management permission is unavailable; locked-screen dialling was not run")
                }
                if (sims.none { it.handle == sim.handle }) throw CallProblem("The selected outgoing SIM is unavailable; please choose another")
                prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
                if (liveCalls().isNotEmpty() || managedCallPresent() != false) throw CallProblem("A call is already in progress or idle state could not be confirmed; did not dial")
                if (isEmergency(number) != false) throw CallProblem("Could not confirm this number is safe to dial")
                val extras = Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim.handle)
                    putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString(TOKEN, w.id) })
                }
                validateLockedDial(w)
                w.placed = true
                w.telecomActionIssued = true
                val issued = runCatching { checkNotNull(telecom).placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras) }.isSuccess
                lockedDial?.takeIf { it.ticket === w.lockedDialTicket }?.let {
                    finishLockedDial(it, if (issued) "Waiting for Android to confirm locked-screen dialling" else "Android rejected locked-screen dialling")
                }
                if (!issued) {
                    throw CallProblem("Android rejected dialling")
                }
                if (!waitFor(CallPolicy.DIAL_TIMEOUT_MS) { w.track != null }) {
                    throw CallProblem("No Android call callback received for this number; check the system dialler")
                }
            }
            val limit = if (w.direction == CallDirection.INCOMING) CallPolicy.CONTROL_TIMEOUT_MS else 55_000L
            if (!waitFor(limit) { w.track?.phase == CallPhase.ACTIVE }) {
                throw CallProblem("Android did not confirm call connection; the system Phone app is handling the call")
            }
            val target = w.track ?: throw CallProblem("Call ended")
            if (!carrierCall(target.call) || liveCalls().singleOrNull() !== target) {
                throw CallProblem("The AI cannot safely take over the current call")
            }
            w.activeAt = System.currentTimeMillis()
            w.activeElapsed = SystemClock.elapsedRealtime()
            acquireCallWakeLock(w)
            w.bridging = true
            detail = "Connected; setting up two-way audio"
            publish()
            runAudio(w, helper, session)
        } catch (e: CancellationException) {
            // Coroutine cancellation may carry a secondary descriptor-close exception.
            if (BuildConfig.DEBUG) e.suppressed.take(MAX_MEDIA_DIAGNOSTICS).forEach { debugMediaException(w, it) }
            throw e
        } catch (e: CallProblem) {
            debugMediaException(w, e)
            w.stopReason.record(e.message ?: "AI calls are not ready")
        } catch (e: CallMediaFailure) {
            debugMediaException(w, e)
            if (w.stopReason.value == null) {
                w.stopReason.record(shellAudioFailure(w) ?: e.message ?: "Call audio disconnected")
            }
        } catch (e: VolcRealtimeException) {
            debugMediaException(w, e)
            w.stopReason.record(when (e.kind) {
                VolcRealtimeException.Kind.AUTH -> "Volcengine authentication rejected; check your call voice credentials and service activation"
                VolcRealtimeException.Kind.RATE_LIMITED -> "Volcengine realtime voice hit its service limit; the system Phone app is handling the call"
                VolcRealtimeException.Kind.TIMEOUT, VolcRealtimeException.Kind.NETWORK -> "Volcengine realtime voice connection is unavailable; the system Phone app is handling the call"
                VolcRealtimeException.Kind.BACKPRESSURE -> "Volcengine realtime audio is congested; the system Phone app is handling the call"
                else -> "Volcengine realtime voice could not continue; check call settings"
            })
        } catch (e: Exception) {
            debugMediaException(w, e)
            w.stopReason.record("AI audio connection failed; the system Phone app is handling the call")
        } finally {
            withContext(NonCancellable) {
                w.mediaStage = MediaStage.RELEASE
                runCatching { w.session?.close() }.onFailure { debugMediaException(w, it) }
                val restored = try { restoreAudio(w) } finally { releaseCallWakeLock(w) }
                if (w.stopReason.value == FAREWELL_ENDING) {
                    val target = w.track
                    val ended = target != null && (confirmedEnded(target) ||
                        waitFor(CallPolicy.CONTROL_TIMEOUT_MS) { confirmedEnded(target) } || confirmedEnded(target))
                    w.stopReason.refine(FAREWELL_ENDING, if (ended) "Call wrapped up; Android hung up"
                        else "Call wrapped up; Android did not confirm hang-up, handed back to the system")
                }
                val reason = w.stopReason.value ?: "AI left the call; the system Phone app is handling it"
                if (BuildConfig.DEBUG) w.mediaCounters.playbackDiagnostic()?.let { Log.d("MikaCallMedia", "playback $it") }
                w.track?.let {
                    if (w.telecomActionIssued) {
                        val answeredByAi = w.aiActive && w.direction == CallDirection.INCOMING
                        val summaryRequested = settings.state.value.summaryEnabled && w.transcript.isNotEmpty()
                        val record = CallRecord(w.id, it.number, w.direction, w.activeAt ?: it.createdAt,
                            System.currentTimeMillis(), reason, w.transcript.toList(), task = w.task,
                            audioReport = w.mediaCounters.recordReport(),
                            summaryStatus = if (summaryRequested) SummaryStatus.PENDING else SummaryStatus.NOT_REQUESTED)
                        scope.launch {
                            records.append(record)
                            if (answeredByAi) notifications.completed(w.id, record.number,
                                summaryPending = summaryRequested)
                            if (summaryRequested) summarizeCall(w.id, record.transcript)
                        }
                    }
                }
                if (work === w) {
                    lockedDial?.takeIf { it.ticket === w.lockedDialTicket }?.let {
                        finishLockedDial(it, reason, cancelUnplacedWork = false)
                    }
                    work = null
                    detail = if (restored) reason else "$reason; audio restore is not yet confirmed"
                    if (w.lockedDialTicket != null) lockedDialDetail = detail
                    stopBackgroundStandbyIfUnneeded()
                    if (w.telecomActionIssued && w.track?.phase == CallPhase.ACTIVE) notifications.handoff()
                    else notifications.clear()
                    publish()
                }
                w.result.complete(fail(detail ?: reason))
                w.cleaned.complete(restored)
            }
        }
    }

    suspend fun retrySummary(id: String): CallActionResult {
        val record = records.state.value.firstOrNull { it.id == id }
            ?: return CallActionResult(false, "Could not find this call record")
        if (record.transcript.isEmpty()) return CallActionResult(false, "No transcript available to summarise")
        records.setSummary(id, null, SummaryStatus.PENDING)
        return if (summarizeCall(id, record.transcript)) CallActionResult(true, tr(R.string.calls_summary_updated))
        else CallActionResult(false, tr(R.string.calls_summary_failed))
    }

    private suspend fun summarizeCall(id: String, transcript: List<CallTranscriptLine>): Boolean {
        val summary = try {
            app.providers.modelFor(ModelRole.Main)?.let { CallSummaryGenerator.generate(it, transcript, app) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return if (summary == null) {
            records.setSummary(id, null, SummaryStatus.FAILED)
            records.state.value.firstOrNull { it.id == id }?.let { notifications.updateCompletedSummary(id, it.number, null, failed = true) }
            false
        } else {
            val saved = records.setSummary(id, summary, SummaryStatus.READY) == CallHistory.Result.SAVED
            if (saved) records.state.value.firstOrNull { it.id == id }?.let {
                notifications.updateCompletedSummary(id, it.number, summary, failed = false)
            }
            saved
        }
    }

    /** Only a pending AI attempt or active call can hold a timed partial wake lock. */
    private fun acquireCallWakeLock(w: Work, timeoutMs: Long = CallStandbyPolicy.activeWakeBudgetMs(w.config.maxCallSeconds)) {
        releaseCallWakeLock(w)
        runCatching {
            val power = app.getSystemService(PowerManager::class.java) ?: return
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mika:ActiveCall")
            lock.setReferenceCounted(false)
            w.wakeLock = lock
            lock.acquire(timeoutMs)
        }.onFailure { debugMediaException(w, it) }
    }

    private fun releaseCallWakeLock(w: Work) {
        val lock = w.wakeLock ?: return
        w.wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }.onFailure { debugMediaException(w, it) }
    }

    private suspend fun probeAudio(): Boolean {
        val helper = shell ?: return false
        val mode = settings.state.value.audioMode
        val epoch = ++probeEpoch
        val probe = withContext(Dispatchers.IO) { runCatching { helper.probeCallAudio(mode.wire) }.getOrNull() }
        if (helper !== shell || mode != settings.state.value.audioMode) return false
        val ready = probe?.getBoolean("ready") == true
        if (epoch == probeEpoch) {
            audioReady = ready
            audioDetail = if (ready) "Audio path meets requirements; two-way on-device testing is still required"
                else probe?.getString("reason")?.take(160)?.takeIf { it.isNotBlank() } ?: "Built-in assistant could not probe call audio"
            publish()
        }
        return ready
    }

    private suspend fun runAudio(w: Work, helper: IMikaShell, session: VolcRealtimeSession) {
        val greeting = CallPolicy.spokenGreeting(w.config.greeting, hasTask = w.task != null)
        val notification = tr(
            if (greeting.isBlank()) R.string.calls_notification_connected_waiting else R.string.calls_notification_connected,
        )
        if (!notifications.show(w.id, tr(R.string.calls_notification_connecting_audio), ringing = false)) {
            throw CallProblem("Call notification is unavailable; handed back to the system")
        }
        // Capture is never started while ringing, dialing, held, or in a different call.
        val target = w.track ?: throw CallProblem("Call ended")
        if (!sameActiveMuteTarget(w, target) || target.suppressed || shell !== helper) {
            throw CallProblem("Call state changed; AI did not join audio")
        }
        w.mediaStage = MediaStage.MUTE_ACQUIRE
        val mute = acquireTelecomMute(w, target)
        if (!sameActiveMuteTarget(w, target) || target.suppressed || shell !== helper ||
            !settings.state.value.enabled || !mute.isConfirmed(target.service.telecomMuted, microphoneMuted())) {
            throw CallProblem("Call state or mute changed; AI did not join audio")
        }
        w.mediaStage = MediaStage.SHELL_OPEN
        w.audioHelper = helper
        val channel = withContext(NonCancellable + Dispatchers.IO) {
            val result = helper.openCallAudio(w.config.audioMode.wire, w.audioOwner)
            if (result?.getBoolean("ready") != true) {
                throw CallProblem(result?.getString("reason")?.take(160)?.takeIf { it.isNotBlank() } ?: "Two-way call audio was not established on this device")
            }
            val down = result.fd("downlink")
            val up = result.fd("uplink")
            if (down == null || up == null ||
                result.getInt("captureRate") != VolcRealtimeClient.INPUT_SAMPLE_RATE ||
                result.getInt("playRate") != VolcRealtimeClient.OUTPUT_SAMPLE_RATE) {
                runCatching { down?.close() }
                runCatching { up?.close() }
                throw CallProblem("Built-in assistant call audio format is incompatible")
            }
            val capture = ParcelFileDescriptor.AutoCloseInputStream(down)
            val playback = ParcelFileDescriptor.AutoCloseOutputStream(up)
            w.capture = capture
            w.playback = playback
            CallPcmBridge(capture, playback) {
                withContext(Dispatchers.IO) { runCatching { helper.flushCallAudio(w.audioOwner) }.getOrDefault(false) }
            }
        }
        if (!sameActiveMuteTarget(w, target) || target.suppressed || shell !== helper ||
            !settings.state.value.enabled || !mute.isConfirmed(target.service.telecomMuted, microphoneMuted())) {
            throw CallProblem("Call state or mute changed; voice was not sent")
        }
        val remaining = (w.config.maxCallSeconds * 1_000L - (SystemClock.elapsedRealtime() - w.activeElapsed)).coerceAtLeast(1)
        w.mediaStage = MediaStage.PCM_BRIDGE
        withTimeoutOrNull(remaining) {
            coroutineScope {
                val watchdog = launch {
                    var lastBytes = -1L
                    var stalled = 0
                    while (true) {
                        delay(if (w.aiActive) 1_500 else 350)
                        val stats = withContext(Dispatchers.IO) {
                            runCatching { helper.callAudioStats(w.audioOwner) }.getOrNull()
                        }
                        if (stats?.getBoolean("active") != true) {
                            throw CallMediaFailure(shellAudioStopDiagnostic(stats?.getString("reason"))
                                ?: "The device stopped two-way audio; the system Phone app is handling the call")
                        }
                        val bytes = stats.getLong("downBytes")
                        if (!w.aiActive) {
                            if (callAudioHasStarted(greeting, bytes, stats.getLong("upBytes"))) {
                                if (!notifications.show(w.id, notification, ringing = false)) {
                                    throw CallProblem("Call notification is unavailable; handed back to the system")
                                }
                                w.aiActive = true
                                detail = notification
                                if (w.direction == CallDirection.INCOMING) {
                                    w.result.complete(ok(if (greeting.isBlank()) "Android answered; AI is waiting for the other party to speak"
                                        else "Android answered; two-way audio bridge is streaming"))
                                }
                                publish()
                            } else if (SystemClock.elapsedRealtime() - w.activeElapsed >= 7_000) {
                                throw CallMediaFailure("Two-way audio could not be established in time; the system Phone app is handling the call")
                            }
                        } else {
                            stalled = if (lastBytes == bytes) stalled + 1 else 0
                            if (stalled >= 2) throw CallMediaFailure("Carrier audio input stopped making progress; the system Phone app is handling the call")
                        }
                        lastBytes = bytes
                    }
                }
                try {
                    channel.run(
                        session, greeting, counters = w.mediaCounters,
                        onTranscript = { role, text -> appendTranscript(w.transcript, role, text) },
                        onFinalTranscript = { role, text ->
                            if (work === w && w.stopReason.value == null && target.phase == CallPhase.ACTIVE &&
                                liveCalls().singleOrNull() === target && liveTranscriptCallId == target.id &&
                                appendTranscript(liveTranscript, role, text)) {
                                publish()
                            }
                        },
                        awaitFarewellDrain = { fence ->
                            if (!sameActiveMuteTarget(w, target) || target.suppressed || work !== w) false
                            else awaitFarewellPlayback(helper, w, fence)
                        },
                        commitFarewell = {
                            // No suspension: speech can still cancel the waiter up to this point.
                            if (target.suppressed || w.stopReason.value != null || shell !== helper ||
                                !settings.state.value.enabled || !sameActiveMuteTarget(w, target) ||
                                !mute.isConfirmed(target.service.telecomMuted, microphoneMuted())) false
                            else if (runCatching { target.call.disconnect() }.isSuccess) w.stopReason.record(FAREWELL_ENDING)
                            else false
                        },
                    )
                } finally {
                    watchdog.cancel()
                }
            }
        }
        // A natural ending already issued the owner-bound disconnect with media still running.
        if (w.stopReason.value == FAREWELL_ENDING) return
        val ending = "Reached the AI call duration limit"
        if (!w.stopReason.record(ending)) return
        w.mediaStage = MediaStage.RELEASE
        w.session?.close()
        val restored = restoreAudio(w)
        w.aiActive = false
        w.bridging = false
        if (!restored || work !== w || target.suppressed || !sameActiveMuteTarget(w, target)) return
        val ended = runCatching { target.call.disconnect() }.isSuccess &&
            (waitFor(CallPolicy.CONTROL_TIMEOUT_MS) { confirmedEnded(target) } || confirmedEnded(target))
        w.stopReason.refine(ending, if (ended) "Duration limit reached; Android hung up"
            else "Duration limit reached; Android did not confirm hang-up, handed back to the system")
    }

    private suspend fun awaitFarewellPlayback(helper: IMikaShell, w: Work, fence: CallFarewellFence): Boolean {
        suspend fun reached(): Boolean {
            val stats = withContext(Dispatchers.IO) { runCatching { helper.callAudioStats(w.audioOwner) }.getOrNull() }
            return stats?.getBoolean("active") == true && fence.reached(
                playedEpoch = stats.getLong("upPlaybackEpoch", -1L),
                acceptedBytes = stats.getLong("upPlaybackEpochDataBytes", -1L),
                primeFrames = stats.getLong("upPlaybackPrimeFrames", -1L),
                playedFrames = stats.getLong("upPlaybackHeadFrames", -1L),
                fresh = stats.getBoolean("upPlaybackHeadFresh"),
                routed = stats.getBoolean("upPlaybackRouteReady"),
            )
        }
        return withTimeoutOrNull(10_000L) {
            while (!reached()) delay(100L)
            // Keep the collector live through the quiet tail; any new speech cancels this waiter.
            delay(if (fence.explicit) 1_000L else 3_500L)
            reached()
        } == true
    }

    private fun appendTranscript(lines: MutableList<CallTranscriptLine>, role: String, text: String): Boolean {
        val line = CallTranscriptLine(role, text.trim().take(CallHistory.MAX_LINE_LENGTH))
        if (line.text.isEmpty() || lines.lastOrNull() == line) return false
        if (lines.size >= CallHistory.MAX_LINES) lines.removeAt(0)
        lines.add(line)
        return true
    }

    private suspend fun acquireTelecomMute(w: Work, target: Tracked): TelecomMuteLease {
        val service = target.service
        var baseline: TelecomMuteLease? = null
        val known = awaitMute {
            if (!sameActiveMuteTarget(w, target) || target.suppressed) return@awaitMute false
            baseline = TelecomMuteLease.fromBaseline(service.telecomMuted, microphoneMuted())
            baseline != null
        }
        val lease = baseline?.takeIf { known }
            ?: throw CallProblem("Android did not confirm the existing call mute state; AI did not join audio")
        w.muteLease = lease
        if (lease.needsMuteRequest) {
            // Mark before the Binder request: an in-flight mute still needs a compensating
            // unmute if the acknowledgement times out while this exact call remains active.
            lease.markMuteRequested()
            if (!service.requestMute(target.call, true)) {
                throw CallProblem("Android did not accept the call mute request")
            }
        }
        val confirmed = awaitMute {
            sameActiveMuteTarget(w, target) && !target.suppressed &&
                lease.isConfirmed(service.telecomMuted, microphoneMuted())
        }
        if (!confirmed) throw CallProblem("Android did not confirm microphone mute; AI did not join audio")
        return lease
    }

    private fun sameActiveMuteTarget(w: Work, target: Tracked): Boolean =
        target.phase == CallPhase.ACTIVE && sameMuteTarget(w, target)

    private fun sameMuteTarget(w: Work, target: Tracked): Boolean =
        work === w && w.track === target && isCurrentCall(target, target.id) &&
            (target.phase == CallPhase.ACTIVE || target.phase == CallPhase.HOLDING) && target.service.tracks(target.call)

    private fun microphoneMuted(): Boolean? = runCatching {
        app.getSystemService(AudioManager::class.java)?.isMicrophoneMute
    }.getOrNull()

    private suspend fun awaitMute(ready: () -> Boolean): Boolean = withTimeoutOrNull(MUTE_ACK_MS) {
        while (!ready()) delay(40)
        true
    } == true

    private suspend fun shellAudioFailure(w: Work): String? = withContext(Dispatchers.IO) {
        val helper = w.audioHelper ?: return@withContext null
        val stats = runCatching { helper.callAudioStats(w.audioOwner) }.getOrNull() ?: return@withContext null
        if (stats.getBoolean("active")) null else shellAudioStopDiagnostic(stats.getString("reason"))
    }

    private suspend fun restoreAudio(w: Work): Boolean {
        // Release and flush hardware media before making the handset microphone available again.
        val shellRestored = restoreShellAudio(w)
        val muteRestored = restoreTelecomMute(w)
        return shellRestored && muteRestored
    }

    private suspend fun restoreTelecomMute(w: Work): Boolean {
        val lease = w.muteLease ?: return true
        val target = w.track
        fun sameCall() = target != null && sameMuteTarget(w, target)
        val plan = lease.beginRelease(sameCall())
        if (plan == TelecomMuteLease.Release.REQUEST_UNMUTE && target != null) {
            // No suspension since sameCall() above. Do not send a global mute command on a
            // different call, even if this service is still bound after the original call ended.
            target.service.requestMute(target.call, false)
        }
        val restored = awaitMute {
            plan.confirmed(sameCall(), target?.service?.telecomMuted, microphoneMuted())
        }
        if (restored) w.muteLease = null
        return restored
    }

    private suspend fun restoreShellAudio(w: Work): Boolean = withContext(Dispatchers.IO) {
        val helper = w.audioHelper
        runCatching { w.capture?.close() }.onFailure { debugMediaException(w, it) }
        runCatching { w.playback?.close() }.onFailure { debugMediaException(w, it) }
        w.capture = null
        w.playback = null
        if (helper == null) return@withContext true
        runCatching {
            helper.stopCallAudio(w.audioOwner)
            val stats = helper.callAudioStats(w.audioOwner) ?: return@runCatching false
            w.mediaCounters.outputShellAccepted(
                bytes = stats.getLong("upBytes", -1L),
                zeroCount = stats.getLong("upWriteZeroCount", -1L),
                zeroMaxMs = stats.getLong("upWriteZeroMaxMs", -1L),
                lastWriteAgeMs = stats.getLong("upLastWriteAgeMs", -1L),
                playbackHeadFrames = stats.getLong("upPlaybackHeadFrames", -1L),
                flushMaxMs = stats.getLong("upFlushMaxMs", -1L),
                captureSilencedMs = if (stats.getBoolean("clientSilencedKnown")) stats.getLong("clientSilencedMaxMs", -1L) else -1L,
            )
            !stats.getBoolean("active") && stats.getBoolean("microphoneRestored")
        }.onFailure { debugMediaException(w, it) }.getOrDefault(false).also {
            if (it) w.audioHelper = null
        }
    }

    private fun debugMediaException(w: Work, error: Throwable) {
        if (!BuildConfig.DEBUG || w.mediaDiagnosticCount >= MAX_MEDIA_DIAGNOSTICS) return
        w.mediaDiagnosticCount++
        val type = error.javaClass.simpleName.take(64).ifBlank { "Exception" }
        Log.d("MikaCallMedia", "stage=${w.mediaStage.name} exception=$type")
    }

    private val CallAudioMode.wire: Int get() = if (this == CallAudioMode.DIRECT) 1 else 2

    private fun Bundle.fd(key: String): ParcelFileDescriptor? =
        if (Build.VERSION.SDK_INT >= 33) getParcelable(key, ParcelFileDescriptor::class.java)
        else getParcelable(key)

    private fun validateTarget(w: Work) {
        if (w.direction == CallDirection.INCOMING) {
            val t = w.track
            if (t == null || !isCurrentCall(t, w.expectedCallId) || t.phase != CallPhase.RINGING ||
                t.direction != CallDirection.INCOMING || t.suppressed || !t.service.tracks(t.call) || !carrierCall(t.call)) {
                throw CallProblem("Incoming call changed; AI did not answer")
            }
            if (w.automatic) {
                if (!canAutomaticallyAnswerHere()) throw CallProblem("Background standby is unavailable; the system Phone app is handling this incoming call")
                val config = settings.state.value
                val initial = t.initialRingPolicy
                if (autoPauseWriting || initial == null ||
                    !CallPolicy.callerAllowed(initial, t.number, t.ringObservedWallMs) ||
                    !CallPolicy.callerAllowed(config, t.number) || config.audioMode != w.config.audioMode ||
                    SystemClock.elapsedRealtime() - t.ringObservedAt > CallPolicy.AUTO_ANSWER_WINDOW_MS) {
                    throw CallProblem("Incoming call timed out or no longer matches auto-answer rules")
                }
            }
        } else {
            if (liveCalls().isNotEmpty()) throw CallProblem("A call is already in progress; did not dial")
            validateLockedDial(w)
        }
    }

    private fun isCurrentCall(target: Tracked, expectedCallId: String?): Boolean =
        liveCalls().singleOrNull() === target && (expectedCallId == null || target.id == expectedCallId)

    private fun isPendingAutoAnswer(w: Work, target: Tracked): Boolean =
        w.automatic && w.track === target && target.phase == CallPhase.RINGING && !target.suppressed &&
            !w.answerSent && w.stopReason.value == null

    private fun autoAnswerBlocker(config: CallConfig, notificationReady: Boolean? = null): String? = when {
        !config.audioVerified -> "Verify two-way call audio for the current mode first"
        !config.allowAllIncoming && config.allowedIncomingNumbers.isEmpty() -> "Auto-answer list is empty"
        else -> prerequisite(ignoreWork = true, notificationReady = notificationReady)
            ?: if (!audioReady) audioDetail ?: "Device call audio is not ready yet" else null
    }

    private fun prerequisite(
        ignoreWork: Boolean = false,
        notificationReady: Boolean? = null,
    ): String? = when {
        !settings.state.value.enabled -> tr(R.string.calls_disabled)
        configuring -> "AI calls are being configured"
        !armed || !access.hasPhonePermissions() -> "Prepare AI calls first"
        shell == null -> "Built-in Mika assistant is not connected"
        !settings.state.value.credentialsConfigured -> tr(R.string.calls_setup_credentials)
        !(notificationReady ?: notifications.ready()) -> "Allow AI call notifications so you can cancel or take over"
        !ignoreWork && work?.direction == CallDirection.OUTGOING -> "Another call is already being handled"
        else -> null
    }

    private fun cancelWork(reason: String): Work? {
        val active = work ?: return null
        active.stopReason.record(reason)
        active.track?.suppressed = true
        active.job?.cancel()
        publish()
        return active
    }

    private suspend fun awaitCleanup(w: Work?): Boolean =
        w == null || withTimeoutOrNull(CallPolicy.CONTROL_TIMEOUT_MS) { w.cleaned.await() } == true

    private suspend fun awaitResult(w: Work): CallActionResult = try {
        w.result.await()
    } catch (e: CancellationException) {
        if (!w.telecomActionIssued && !w.automatic && work === w) cancelWork("Operation cancelled; the system Phone app is handling the call")
        throw e
    }

    private suspend fun waitFor(timeout: Long, test: () -> Boolean): Boolean =
        withTimeoutOrNull(timeout) { changes.first { test() }; true } == true

    private fun publish() {
        val config = settings.state.value
        val enabled = config.enabled
        val calls = if (enabled) liveCalls() else emptyList()
        val current = calls.firstOrNull()
        val soleCallId = calls.singleOrNull()?.id
        if (soleCallId != liveTranscriptCallId) {
            liveTranscriptCallId = soleCallId
            liveTranscript.clear()
        }
        val active = work.takeIf { enabled }
        val notificationReady = enabled && notifications.ready()
        val pending = current != null && active != null && isPendingAutoAnswer(active, current) && calls.size == 1
        val countdown = active?.autoDelayEndsAt?.takeIf { pending }
            ?.let { it - SystemClock.elapsedRealtime() }?.takeIf { it > 0L }?.let { ((it + 999) / 1_000).toInt() }
        val pauseSeconds = if (enabled && config.autoAnswer) {
            CallPolicy.autoAnswerPauseRemainingMs(config, System.currentTimeMillis())
                .takeIf { it > 0L }?.let { 1L + (it - 1L) / 1000L }
        } else null
        val standbyNotify = enabled && config.autoAnswer && CallStandbyService.notificationsAvailable(app)
        val baseBlocker = if (enabled && config.autoAnswer) autoAnswerBlocker(config, notificationReady) else null
        val standbyBlocked = if (enabled && config.autoAnswer) when {
            !userUnlocked() -> "Unlock your phone after restarting first"
            !standbyNotify -> tr(R.string.calls_standby_notifications_required)
            baseBlocker != null -> baseBlocker
            pauseSeconds != null -> tr(R.string.calls_auto_paused)
            standbyStarting && standbyIncomingRequested -> null
            !isIncomingStandbyRunning() -> standbyStartError ?: "Start background standby on the Calls screen"
            else -> null
        } else null
        val blocker = if (enabled && config.autoAnswer) baseBlocker ?: if (!canAutomaticallyAnswerHere()) {
            standbyBlocked ?: "Background standby is starting"
        } else null else null
        val autoStage = when {
            countdown != null -> CallAutoAnswerStage.COUNTDOWN
            pending -> CallAutoAnswerStage.PREPARING
            active?.automatic == true && active.answerSent && active.stopReason.value == null && !active.aiActive ->
                CallAutoAnswerStage.ANSWERING
            !enabled || !config.autoAnswer -> CallAutoAnswerStage.OFF
            pauseSeconds != null -> CallAutoAnswerStage.PAUSED
            blocker != null -> CallAutoAnswerStage.BLOCKED
            calls.isNotEmpty() || active != null || unobservedManagedCall -> CallAutoAnswerStage.BUSY
            else -> CallAutoAnswerStage.STANDBY
        }
        mutable.value = CallRuntimeState(
            prepared = enabled && armed && !configuring,
            phase = current?.phase ?: when {
                active?.direction == CallDirection.OUTGOING -> CallPhase.DIALING
                enabled && unobservedManagedCall -> CallPhase.UNKNOWN
                else -> CallPhase.IDLE
            },
            direction = current?.direction ?: active?.direction,
            callId = calls.singleOrNull()?.id,
            number = current?.number ?: active?.requestedNumber,
            aiActive = active?.aiActive == true,
            detail = if (enabled && unobservedManagedCall && current == null) "Android is in a call, but Mika has not received the callback for it yet" else detail,
            pendingAutoAnswer = pending,
            autoAnswerStage = autoStage,
            autoAnswerRemainingSeconds = countdown,
            autoAnswerPauseRemainingSeconds = pauseSeconds,
            autoAnswerBlockedReason = blocker.takeIf { autoStage == CallAutoAnswerStage.BLOCKED },
            liveTranscript = liveTranscript.toList(),
            historyWriteFailed = records.writeFailed.value,
            simOptions = sims.map { it.info },
            shellConnected = shell != null,
            notificationsAvailable = notificationReady,
            audioReady = enabled && audioReady,
            audioDetail = audioDetail,
            canAnswer = enabled && current?.phase == CallPhase.RINGING && current.direction == CallDirection.INCOMING &&
                !current.suppressed && calls.size == 1 && (active == null || active.track === current && !active.answerSent && active.stopReason.value == null) &&
                prerequisite(ignoreWork = true, notificationReady = notificationReady) == null && carrierCall(current.call),
            canHangUp = enabled && armed && current != null && current.phase != CallPhase.DISCONNECTING &&
                calls.size == 1 && carrierCall(current.call),
            canTakeOver = active != null && active.stopReason.value == null,
            backgroundStandbyRunning = isIncomingStandbyRunning(),
            backgroundStandbyStarting = standbyStarting && standbyIncomingRequested,
            backgroundStandbyBlockedReason = standbyBlocked,
            lockedDialRequestId = lockedDial?.ticket?.id,
            lockedDialRemainingSeconds = lockedDial?.let(::remainingSeconds),
            lockedDialPreparing = lockedDial?.claimed == true,
            lockedDialDetail = lockedDialDetail,
        )
        changes.value++
    }

    private fun report(message: String): CallActionResult {
        detail = message
        publish()
        return fail(message)
    }

    private fun liveCalls() = tracked.values.filter { it.call.state != Call.STATE_DISCONNECTED }

    private fun confirmedEnded(t: Tracked): Boolean = t.call.state == Call.STATE_DISCONNECTED ||
        (t.call !in tracked && managedCallPresent() == false)

    private fun managedCallPresent(): Boolean? = runCatching { telecom?.isInManagedCall }.getOrNull()

    private fun isEmergency(number: String): Boolean? = runCatching { telephony?.isEmergencyNumber(number) }.getOrNull()

    private fun carrierCall(call: Call): Boolean {
        val d = call.details ?: return false
        if (d.videoState != VideoProfile.STATE_AUDIO_ONLY ||
            d.callProperties and (Call.Details.PROPERTY_SELF_MANAGED or Call.Details.PROPERTY_IS_EXTERNAL_CALL or
                Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL or Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE or
                Call.Details.PROPERTY_CONFERENCE or Call.Details.PROPERTY_RTT) != 0 ||
            call.parent != null || call.children.isNotEmpty()) return false
        val number = d.handle?.let { rawHandle ->
            if (rawHandle.scheme != PhoneAccount.SCHEME_TEL) return false
            CallPolicy.incomingNumber(rawHandle.schemeSpecificPart) ?: return false
        }
        val handle = d.accountHandle ?: return false
        if (subscription(handle) == null) return false
        return number == null || isEmergency(number) == false
    }

    private fun matchesOutgoing(w: Work, tracked: Tracked): Boolean =
        tracked.direction == CallDirection.OUTGOING && tracked.number == w.requestedNumber &&
            tracked.call.details?.accountHandle == w.sim?.handle &&
            runCatching { tracked.call.details?.intentExtras?.getString(TOKEN) == w.id }.getOrDefault(false)

    private suspend fun readSims() {
        sims = if (!access.hasPhonePermissions()) emptyList() else withContext(Dispatchers.IO) {
            runCatching {
                val service = telecom ?: return@runCatching emptyList()
                val preferred = service.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
                val subscriptionService = app.getSystemService(SubscriptionManager::class.java)
                service.callCapablePhoneAccounts.mapNotNull { handle ->
                    val id = subscription(handle) ?: return@mapNotNull null
                    val sim = subscriptionService?.getActiveSubscriptionInfo(id) ?: return@mapNotNull null
                    CarrierSim(CallSim("sim-$id", sim.displayName?.toString()?.take(80)
                        ?.ifBlank { null } ?: "SIM ${sim.simSlotIndex + 1}", handle == preferred), handle)
                }.distinctBy { it.info.id }
            }.getOrDefault(emptyList())
        }
    }

    private fun subscription(handle: PhoneAccountHandle): Int? = runCatching {
        telephony?.createForPhoneAccountHandle(handle)?.subscriptionId?.takeIf { it >= 0 }
    }.getOrNull()

    private fun callEndMessage(call: Tracked): String = when (call.call.details?.disconnectCause?.code) {
        android.telecom.DisconnectCause.BUSY -> "Line busy; call ended"
        android.telecom.DisconnectCause.ERROR, android.telecom.DisconnectCause.RESTRICTED -> "Android reported that the call failed"
        android.telecom.DisconnectCause.MISSED -> "Missed incoming call"
        else -> "Call ended"
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
    private fun ok(message: String) = CallActionResult(true, message)
    private fun fail(message: String) = CallActionResult(false, message)

    private class CarrierSim(val info: CallSim, val handle: PhoneAccountHandle)
    private class PendingLockedDial(
        val ticket: LockedDialGate.Ticket,
        val config: CallConfig,
        val sim: CarrierSim,
        val credentialsRevision: Long,
    ) {
        var job: Job? = null
        var claimed = false
        var wakeLock: PowerManager.WakeLock? = null
    }

    private class Tracked(val call: Call, val service: MikaInCallService) {
        val id = UUID.randomUUID().toString()
        val number get() = numberOf(call)
        val direction get() = when (call.details?.callDirection) {
            Call.Details.DIRECTION_INCOMING -> CallDirection.INCOMING
            Call.Details.DIRECTION_OUTGOING -> CallDirection.OUTGOING
            else -> null
        }
        val phase get() = when (call.state) {
            Call.STATE_DIALING, Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_NEW -> CallPhase.DIALING
            Call.STATE_RINGING -> CallPhase.RINGING
            Call.STATE_CONNECTING -> CallPhase.CONNECTING
            Call.STATE_ACTIVE -> CallPhase.ACTIVE
            Call.STATE_HOLDING -> CallPhase.HOLDING
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> CallPhase.DISCONNECTING
            else -> CallPhase.UNKNOWN
        }
        val createdAt = System.currentTimeMillis()
        var initialRingPolicy: CallConfig? = null
        var ringObservedAt: Long = 0L
        var ringObservedWallMs: Long = 0L
        var autoTried = false
        var suppressed = false
    }
    private enum class MediaStage { PREFLIGHT, VOLC_CONNECT, TELECOM_CONNECT, MUTE_ACQUIRE, SHELL_OPEN, PCM_BRIDGE, RELEASE }

    private class Work(
        val direction: CallDirection,
        val config: CallConfig,
        var track: Tracked? = null,
        val auto: Boolean = false,
        val requestedNumber: String? = null,
        val sim: CarrierSim? = null,
        val expectedCallId: String? = null,
        val task: CallTaskBrief? = null,
        val lockedDialTicket: LockedDialGate.Ticket? = null,
    ) {
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<CallActionResult>()
        val cleaned = CompletableDeferred<Boolean>()
        val transcript = mutableListOf<CallTranscriptLine>()
        val pendingRing get() = direction == CallDirection.INCOMING && !answerSent
        var manualAnswerRequested = false
        val automatic get() = auto && !manualAnswerRequested
        var autoDelayEndsAt: Long? = null
        val skipAutoDelay = CompletableDeferred<Unit>()
        var job: Job? = null
        var placed = false
        var answerSent = false
        var telecomActionIssued = false
        var bridging = false
        var aiActive = false
        var activeAt: Long? = null
        var activeElapsed: Long = 0L
        val stopReason = CallStopReason()
        var mediaStage = MediaStage.PREFLIGHT
        var mediaDiagnosticCount = 0
        val mediaCounters = CallMediaCounters()
        var session: VolcRealtimeSession? = null
        val audioOwner = Binder()
        var audioHelper: IMikaShell? = null
        var muteLease: TelecomMuteLease? = null
        var wakeLock: PowerManager.WakeLock? = null
        var capture: ParcelFileDescriptor.AutoCloseInputStream? = null
        var playback: ParcelFileDescriptor.AutoCloseOutputStream? = null
    }

    private class CallProblem(message: String) : Exception(message)

    private companion object {
        const val MAX_MEDIA_DIAGNOSTICS = 4
        const val STANDBY_START_MS = 5_000L
        const val MUTE_ACK_MS = 2_000L
        const val CALL_CHANGED = "The specified call changed or ended; check the call state again"
        private const val FAREWELL_ENDING = "Call wrapping up; ending call"
        const val TOKEN = "com.wangzan101.mika.CALL_REQUEST_TOKEN"
        fun disconnected(call: Call): Boolean =
            call.state == Call.STATE_DISCONNECTED || call.state == Call.STATE_DISCONNECTING

        fun numberOf(call: Call): String? {
            val details = call.details ?: return null
            if (details.callDirection == Call.Details.DIRECTION_INCOMING &&
                details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED) return null
            return details.handle?.takeIf { it.scheme == PhoneAccount.SCHEME_TEL }
                ?.schemeSpecificPart?.let(CallPolicy::incomingNumber)
        }
    }
}

/** The first event owns the history outcome; secondary cleanup exceptions cannot replace it. */
internal class CallStopReason {
    var value: String? = null
        private set

    fun record(reason: String): Boolean {
        if (value != null) return false
        value = reason
        return true
    }

    /** A timeout may report the confirmed outcome of its own hangup attempt. */
    fun refine(expected: String, reason: String) {
        if (value == expected) value = reason
    }
}
