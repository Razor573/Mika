package com.wangzan101.mika.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

sealed interface AppAccessAuthResult {
    data object Authenticated : AppAccessAuthResult
    data object NeedsCountdownFallback : AppAccessAuthResult
    data class Failed(val message: String?) : AppAccessAuthResult
}

object AppAccessAuth {
    private tailrec fun Context.hostContext(): Context = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.hostContext()
        else -> this
    }

    fun authenticateUnlock(
        context: Context,
        title: String,
        subtitle: String,
        onResult: (AppAccessAuthResult) -> Unit,
    ): CancellationSignal? {
        val uiContext = context.hostContext()
        val keyguard = uiContext.getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) {
            onResult(AppAccessAuthResult.NeedsCountdownFallback)
            return null
        }
        val signal = CancellationSignal()
        val builder = BiometricPrompt.Builder(uiContext)
            .setTitle(title)
            .setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            signal,
            uiContext.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onResult(AppAccessAuthResult.Authenticated)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL) {
                        onResult(AppAccessAuthResult.NeedsCountdownFallback)
                    } else {
                        onResult(AppAccessAuthResult.Failed(errString?.toString()))
                    }
                }
            },
        )
        return signal
    }
}
