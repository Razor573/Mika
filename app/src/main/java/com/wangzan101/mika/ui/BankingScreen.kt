package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.BankingMode
import com.wangzan101.mika.settings.ResumeStep
import com.wangzan101.mika.settings.planResumeSteps
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * Banking mode: pause Mika so a bank app opens, then resume it.
 *
 * The user verified on his phone that HSBC opens only when BOTH the accessibility
 * service and wireless debugging are off. Pause turns both off and stops the phone
 * helper; the wireless-debugging pairing stays saved, so resume reconnects with it
 * and needs no new pairing code. Android does not let an app turn its own
 * accessibility service back on, so resume lists that step for the user to tap in
 * system settings. State is read fresh from the system every time the helper or
 * the accessibility service changes, never assumed.
 */
@Composable
fun BankingScreen(onBack: () -> Unit, onFix: (Fix) -> Unit) {
    val context = LocalContext.current
    val mode = remember { BankingMode.create(context) }
    val phone = remember { PhoneServices.of(context) }
    // 助手状态或无障碍实例一变就重读系统状态:界面永远是实测,不是上次记的。
    val shellStatus by phone.shellControl.status.collectAsState()
    val a11yInstance by ScreenAccessibility.instance.collectAsState()
    var state by remember { mutableStateOf(mode.snapshot()) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shellStatus, a11yInstance) { state = mode.snapshot() }

    val helperText = when (state.helper) {
        BuiltinShellStatus.Running -> stringResource(R.string.banking_helper_running)
        BuiltinShellStatus.Paused -> stringResource(R.string.banking_helper_paused)
        BuiltinShellStatus.Connecting -> stringResource(R.string.banking_helper_connecting)
        else -> stringResource(R.string.banking_helper_not_running)
    }
    val helperTone = when (state.helper) {
        BuiltinShellStatus.Running -> ValueTone.Ok
        BuiltinShellStatus.Paused -> ValueTone.Plain
        else -> ValueTone.Bad
    }
    val wirelessText = stringResource(if (state.wirelessDebugging) R.string.state_on else R.string.state_off)
    val (a11yText, a11yTone) = StatusCopy.accessibilityValue(state.accessibility)
    // 没暂停时,按真实状态算出恢复后还剩哪些步骤要用户亲手做。
    val resumeSteps = if (state.paused) emptyList() else planResumeSteps(state)

    PageScaffold(stringResource(R.string.banking_title), onBack) {
        StateCard(
            MikaIcons.Shield,
            stringResource(if (state.paused) R.string.banking_paused_title else R.string.banking_active_title),
            stringResource(if (state.paused) R.string.banking_paused_body else R.string.banking_active_body),
            if (state.paused) TileTone.Ok else TileTone.Warn,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.banking_explain),
            style = MikaType.Caption, color = MikaTheme.colors.ink2,
        )
        Spacer(Modifier.height(12.dp))

        GroupLabel(stringResource(R.string.banking_title))
        GroupCard {
            ListRow(
                MikaIcons.Shield, stringResource(R.string.banking_a11y_label), null, first = true,
            ) { RowValue(a11yText, a11yTone) }
            ListRow(
                MikaIcons.Wrench, stringResource(R.string.banking_wireless_label), null,
            ) { RowValue(wirelessText, if (state.wirelessDebugging) ValueTone.Bad else ValueTone.Ok) }
            ListRow(
                MikaIcons.Phone, stringResource(R.string.banking_helper_label), null,
            ) { RowValue(helperText, helperTone) }
        }
        Spacer(Modifier.height(12.dp))

        GroupCard {
            if (state.paused) {
                ListRow(
                    MikaIcons.Power, stringResource(R.string.banking_resume),
                    stringResource(R.string.banking_resume_sub), first = true,
                    onClick = {
                        val result = mode.resume()
                        state = mode.snapshot()
                        notice = if (result.steps.isEmpty()) {
                            context.getString(R.string.banking_resumed)
                        } else {
                            null
                        }
                    },
                ) { }
            } else {
                ListRow(
                    MikaIcons.Power, stringResource(R.string.banking_pause),
                    stringResource(R.string.banking_pause_sub), first = true,
                    onClick = {
                        val result = mode.pause()
                        state = mode.snapshot()
                        notice = buildString {
                            if (result.tasksStopped) append(context.getString(R.string.banking_task_stopped))
                            if (result.readyForBanking) {
                                if (isNotEmpty()) append(" ")
                                append(context.getString(R.string.banking_paused_body))
                            } else {
                                if (result.accessibilityNeedsManual) {
                                    if (isNotEmpty()) append(" ")
                                    append(context.getString(R.string.banking_a11y_manual))
                                }
                                if (result.wirelessNeedsManual) {
                                    if (isNotEmpty()) append(" ")
                                    append(context.getString(R.string.banking_wireless_manual))
                                }
                            }
                        }.takeIf { it.isNotEmpty() }
                    },
                ) { }
            }
        }

        // 恢复后还剩的、App 自己做不了的步骤:每一步都是一个直达系统设置的按钮。
        if (!state.paused && resumeSteps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.banking_resume_note),
                style = MikaType.Caption, color = MikaTheme.colors.ink2,
            )
            Spacer(Modifier.height(12.dp))
            GroupCard {
                resumeSteps.forEachIndexed { index, step ->
                    val (icon, label, fix) = when (step) {
                        ResumeStep.OpenAccessibilitySettings ->
                            Triple(MikaIcons.Shield, stringResource(R.string.banking_step_a11y), Fix.OpenAccessibility)
                        ResumeStep.OpenWirelessDebuggingPage ->
                            Triple(MikaIcons.Wrench, stringResource(R.string.banking_step_wireless), Fix.OpenWirelessDebugging)
                        ResumeStep.RePairHelper ->
                            Triple(MikaIcons.Phone, stringResource(R.string.banking_step_repair), Fix.OpenPhoneSetup)
                    }
                    ListRow(
                        icon, label, null, first = index == 0,
                        onClick = { onFix(fix) },
                    ) { }
                }
            }
        }

        // 助手已配对、却还没连上(例如刚恢复、正在等 Wi-Fi):手动再连一次。
        if (!state.paused && state.helperPaired &&
            state.helper != BuiltinShellStatus.Running && state.helper != BuiltinShellStatus.Connecting
        ) {
            Spacer(Modifier.height(12.dp))
            GroupCard {
                ListRow(
                    MikaIcons.Refresh, stringResource(R.string.banking_reconnect), null, first = true,
                    onClick = { onFix(Fix.RetryPhoneHelper) },
                ) { }
            }
        }

        notice?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MikaType.Caption, color = MikaTheme.colors.ink2)
        }
    }
}
