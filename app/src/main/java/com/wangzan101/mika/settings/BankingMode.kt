package com.wangzan101.mika.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.a11y.MikaAccessibilityService
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.task.TaskService

/**
 * 银行模式:亲手暂停 Mika,让银行 App 不再把它当成从非官方商店装的无障碍应用。
 *
 * 用户在真机上验证过:无障碍服务关掉、无线调试关掉,HSBC 就能打开;其中任何一个
 * 开着,HSBC 就拦。暂停就是把这两样都关掉,再把内置助手停下。恢复时按已配对
 * 的连接重连,不用重新配对。
 *
 * 有两件事 App 自己做不了,安卓不允许,恢复时必须用户亲手做,界面要说清楚:
 * - 无障碍服务只能由用户在系统设置里重新打开(App 不能替自己打开它);
 * - 无线调试的开关 App 能写(拿到了修改系统设置的授权时),写不进去就得用户
 *   自己去开发者选项里打开。
 * 配对钥匙、端口缓存和「已配对」标记暂停时一律不动:暂停不是解除配对,
 * [BuiltinShellControl.unpair] 在这里绝不能调。
 */
class BankingMode private constructor(
    private val app: Context,
    private val shell: BuiltinShellControl,
    private val canWriteSecureSettings: () -> Boolean,
) {
    companion object {
        const val PREFS = "banking_mode"
        const val KEY_PAUSED = "paused"

        /** 持久化的暂停开关:进程重启后 App 启动时按它把暂停重设上。 */
        fun isPaused(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)

        fun create(context: Context): BankingMode {
            val app = context.applicationContext
            val phone = PhoneServices.of(context)
            return BankingMode(app, phone.shellControl) {
                app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /** 持久化的暂停开关。 */
    fun isPaused(): Boolean = isPaused(app)

    private fun setPaused(value: Boolean) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAUSED, value).apply()
    }

    /** 此刻的真实状态,直接读系统,不读缓存。 */
    fun snapshot(): BankingState {
        val instanceRunning = ScreenAccessibility.instance.value != null
        val enabled = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it.trim()) }
        val me = ComponentName(app, MikaAccessibilityService::class.java)
        val accessibility = when {
            instanceRunning -> AccessibilityState.On
            me in enabled -> AccessibilityState.EnabledNotRunning
            else -> AccessibilityState.Off
        }
        return BankingState(
            paused = isPaused(),
            accessibility = accessibility,
            accessibilityRunning = instanceRunning,
            wirelessDebugging = DeveloperSettings.wirelessDebugging(app.contentResolver),
            helper = shell.status.value,
            helperPaired = shell.paired,
            taskRunning = (app as? MikaApp)?.taskState?.busy?.value ?: false,
        )
    }

    /**
     * 暂停:先记下开关(中途出错也保持暂停),再停任务、停助手、关无障碍、关无线调试。
     * 必须在主线程调用([ScreenAccessibility.disableSelf] 是无障碍服务的方法)。
     */
    fun pause(): PauseResult {
        setPaused(true)
        val before = snapshot()
        var tasksStopped = false
        if (before.taskRunning) {
            TaskService.cancel(app)
            tasksStopped = true
        }
        // 先停助手:看门狗从这一刻起不再重起它;配对钥匙和端口缓存不动。
        shell.pause()
        // 无障碍:安卓 13 起服务能自己关掉自己;更早的系统或服务本来就没在跑时,
        // 只能由用户去系统设置里关,界面要说清楚。
        var a11yOff = before.accessibility == AccessibilityState.Off
        var a11yNeedsManual = false
        if (!a11yOff) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val instance = ScreenAccessibility.instance.value
                if (instance != null) {
                    runCatching { instance.disableSelf() }
                    a11yOff = true
                } else {
                    // 服务在列表里却没在跑:关掉再打开才能彻底停,App 替它关不掉。
                    a11yNeedsManual = true
                }
            } else {
                a11yNeedsManual = true
            }
        }
        // 无线调试:只关开关,不解除配对。写不进去(没有修改系统设置的授权)时,
        // 界面提示用户去开发者选项里亲手关。
        var wirelessOff = !before.wirelessDebugging
        var wirelessNeedsManual = false
        if (!wirelessOff) {
            wirelessOff = if (canWriteSecureSettings()) {
                DeveloperSettings.turnOffWirelessDebugging(app.contentResolver) &&
                    !DeveloperSettings.wirelessDebugging(app.contentResolver)
            } else {
                wirelessNeedsManual = true
                false
            }
        }
        return PauseResult(
            tasksStopped = tasksStopped,
            accessibilityOff = a11yOff,
            accessibilityNeedsManual = a11yNeedsManual,
            wirelessOff = wirelessOff,
            wirelessNeedsManual = wirelessNeedsManual,
            helperPaused = shell.paused,
        )
    }

    /**
     * 恢复:先清掉开关,再把无线调试打开(写得进去时),然后让助手按已配对的连接重连。
     * 无障碍服务 App 打不开,返回的步骤里一定有「去系统设置里打开」这一项,
     * 除非它已经开着。配对丢了才需要重新配对,平时绝不走那一步。
     * 必须在主线程调用。
     */
    fun resume(): ResumeResult {
        setPaused(false)
        val before = snapshot()
        if (!before.wirelessDebugging && canWriteSecureSettings()) {
            DeveloperSettings.turnOnWirelessDebugging(app.contentResolver)
        }
        shell.resume()
        // 再读一次系统:写开关可能被系统在不允许的 Wi-Fi 上撤回,以实测为准。
        val after = snapshot()
        return ResumeResult(
            wirelessOn = after.wirelessDebugging,
            helperReconnecting = after.helper == BuiltinShellStatus.Connecting ||
                after.helper == BuiltinShellStatus.Running,
            steps = planResumeSteps(after),
        )
    }
}

/** 银行模式此刻的真实状态。 */
data class BankingState(
    val paused: Boolean,
    val accessibility: AccessibilityState,
    val accessibilityRunning: Boolean,
    val wirelessDebugging: Boolean,
    val helper: BuiltinShellStatus,
    val helperPaired: Boolean,
    val taskRunning: Boolean,
)

/** 暂停一次做完之后,哪些关掉了、哪些还要用户亲手做。 */
data class PauseResult(
    val tasksStopped: Boolean,
    val accessibilityOff: Boolean,
    val accessibilityNeedsManual: Boolean,
    val wirelessOff: Boolean,
    val wirelessNeedsManual: Boolean,
    val helperPaused: Boolean,
) {
    /** 银行 App 现在应该能打开:两样都关掉了,助手也停了。 */
    val readyForBanking: Boolean get() = accessibilityOff && wirelessOff && helperPaused
}

/** 恢复一次做完之后,还有哪些步骤要用户亲手做。 */
data class ResumeResult(
    val wirelessOn: Boolean,
    val helperReconnecting: Boolean,
    val steps: List<ResumeStep>,
)

/** 恢复时 App 自己做不了、必须用户亲手做的步骤。 */
enum class ResumeStep {
    /** 去系统设置里打开 Mika 的无障碍服务:App 不能替自己打开它。 */
    OpenAccessibilitySettings,
    /** 去开发者选项里打开无线调试:App 写不进去开关时才需要。 */
    OpenWirelessDebuggingPage,
    /** 配对已经丢了:重新走一遍配对流程。平时不出现。 */
    RePairHelper,
}

/** 纯函数:按恢复后的真实状态,算出还有哪些步骤要用户亲手做。 */
fun planResumeSteps(state: BankingState): List<ResumeStep> = buildList {
    if (state.accessibility != AccessibilityState.On) add(ResumeStep.OpenAccessibilitySettings)
    if (!state.wirelessDebugging) add(ResumeStep.OpenWirelessDebuggingPage)
    if (!state.helperPaired) add(ResumeStep.RePairHelper)
}
