package com.wangzan101.mika.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.about.ApkDownloadState
import com.wangzan101.mika.about.ApkRejected
import com.wangzan101.mika.about.DownloadPhase
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UpdateDownloadControls(release: ReleaseInfo) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val updater = app.apkUpdater
    val scope = rememberCoroutineScope()
    var state by remember(release.apkUrl) { mutableStateOf(ApkDownloadState()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var shellStarted by remember { mutableStateOf(false) }
    var repairResult by remember { mutableStateOf<Boolean?>(null) }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApkRejected) {
                app.automaticUpdates.dismiss(release)
                error = when (e.reason) {
                    ApkRejected.Reason.Corrupt -> R.string.about_update_bad_file
                    ApkRejected.Reason.NotNewer -> R.string.about_update_not_newer
                    ApkRejected.Reason.Signer -> R.string.about_update_bad_signer
                }
            } catch (_: Exception) {
                error = R.string.about_update_error
            } finally {
                busy = false
                refresh++
            }
        }
    }

    fun install() = run {
        val intent = withContext(Dispatchers.IO) { updater.installIntent(release) }
        context.startActivity(intent)
    }

    /** Install through the phone helper so Google Play stays the installer of record; the app restarts when it is done. */
    fun installViaShell() = run {
        val ok = withContext(Dispatchers.IO) { updater.installViaShell(release) }
        if (ok) shellStarted = true else error = R.string.about_update_error
    }

    /** Repair the installer record after a normal in-app update, without reinstalling. */
    fun repairInstaller() = run {
        repairResult = withContext(Dispatchers.IO) { updater.repairInstallerRecord() }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
    }

    LaunchedEffect(release.apkUrl, refresh) {
        try {
            do {
                state = withContext(Dispatchers.IO) { updater.state(release) }
                if (state.phase !in listOf(DownloadPhase.Downloading, DownloadPhase.Paused)) break
                delay(1000)
            } while (true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = R.string.about_update_error
        }
    }

    // If this copy was installed from Google Play, installing an update
    // with the system installer hands the APK to it, which then becomes
    // the installer of record - and banking apps that check the install
    // source (e.g. HSBC UK) may flag Mika again. Warn, and offer the
    // phone-helper install instead when the helper is paired: it runs
    // `pm install -i com.android.vending` as shell (see ShellApkInstaller)
    // and keeps Google Play as the installer. The app itself still cannot
    // keep or claim the Play record through its own PackageInstaller
    // session; only the shell helper (or adb from a computer) can.
    val playInstalled = remember { com.wangzan101.mika.about.InstallSource.isPlayInstalled(context) }
    val shellPaired = remember { app.phone.shellControl.paired }

    UpdateDownloadActions(state, busy, error,
        playInstalled = playInstalled,
        shellPaired = shellPaired,
        shellStarted = shellStarted,
        repairResult = repairResult,
        onInstallViaShell = { installViaShell() },
        onRepairInstaller = { repairInstaller() },
        onDownload = { run { withContext(Dispatchers.IO) { updater.download(release) } } },
        onCancel = {
            run {
                withContext(Dispatchers.IO) { updater.cancel() }
                app.automaticUpdates.dismiss(release)
            }
        },
        onInstall = {
            if (context.packageManager.canRequestPackageInstalls()) install()
            else run {
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }
        },
    )
}

@Composable
internal fun UpdateDownloadActions(
    state: ApkDownloadState,
    busy: Boolean,
    /** 上次操作没成时的说明;为空时显示安装提示。 */
    error: Int?,
    /** This copy was installed from Google Play; kept as a defaulted parameter so existing callers still compile. */
    playInstalled: Boolean = false,
    /** The phone helper is paired, so a shell install / installer repair is available. */
    shellPaired: Boolean = false,
    /** A phone-helper install was started; the app will restart when it is done. */
    shellStarted: Boolean = false,
    /** Result of the last "set installer to Google Play" tap, if any. */
    repairResult: Boolean? = null,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onInstallViaShell: () -> Unit = {},
    onRepairInstaller: () -> Unit = {},
) {
    val active = state.phase == DownloadPhase.Downloading || state.phase == DownloadPhase.Paused
    val text = when {
        busy -> stringResource(R.string.about_update_working)
        state.phase == DownloadPhase.Paused -> stringResource(R.string.about_update_paused)
        active -> state.percent?.let { stringResource(R.string.about_update_progress, it) }
            ?: stringResource(R.string.about_update_downloading)
        state.phase == DownloadPhase.Ready -> stringResource(R.string.about_update_install)
        state.phase == DownloadPhase.Failed -> stringResource(R.string.about_update_retry)
        else -> stringResource(R.string.about_update_download_in_app)
    }
    MikaButton(text, if (state.phase == DownloadPhase.Ready) onInstall else onDownload,
        Modifier.fillMaxWidth().padding(top = 14.dp), enabled = !busy && !active && !shellStarted)
    if (state.phase == DownloadPhase.Ready && shellPaired && !shellStarted) {
        MikaButton(stringResource(R.string.about_update_install_via_helper), onInstallViaShell,
            Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Tonal, enabled = !busy)
    }
    if (active || state.phase == DownloadPhase.Ready) {
        MikaButton(stringResource(R.string.about_update_cancel), onCancel,
            Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Tonal, enabled = !busy)
    }
    val note = when {
        shellStarted -> R.string.about_update_shell_install_started
        repairResult == true -> R.string.about_update_set_installer_done
        repairResult == false -> R.string.about_update_set_installer_failed
        error != null -> error
        state.phase == DownloadPhase.Failed -> R.string.about_update_error
        else -> R.string.about_update_install_hint
    }
    Text(stringResource(note),
        Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3)
    if (playInstalled && error == null && !shellStarted) {
        Text(stringResource(R.string.about_update_play_installer_warning),
            Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3)
    }
    // Already lost the Play record to a normal in-app update? The helper
    // can set it back without reinstalling, when it is paired.
    if (!playInstalled && shellPaired && repairResult != true && !shellStarted) {
        MikaButton(stringResource(R.string.about_update_set_installer), onRepairInstaller,
            Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Tonal, enabled = !busy)
    }
}
