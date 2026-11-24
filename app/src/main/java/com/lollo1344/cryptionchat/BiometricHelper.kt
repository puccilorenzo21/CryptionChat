package com.lollo1344.cryptionchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle

object BiometricHelper {

    fun canAuthenticate(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showBiometricPrompt(
        activity: AppCompatActivity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
        onError: (Int, CharSequence) -> Unit
    ) {
        // Controllo preventivo ciclo di vita
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Verifica che l'activity sia ancora viva prima di chiamare il callback
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                        onError(errorCode, errString)
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                        onSuccess()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                        onFailure()
                    }
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.settings_biometric_login))
            .setSubtitle(activity.getString(R.string.biometric_subtitle))
            .setNegativeButtonText(activity.getString(R.string.call_dialog_btn_cancel))
            .build()

        // Ritardiamo leggermente l'autenticazione per permettere il rendering completo dell'activity
        Handler(Looper.getMainLooper()).postDelayed({
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                try {
                    biometricPrompt.authenticate(promptInfo)
                } catch (e: Exception) {
                    e.printStackTrace()
                    onError(0, activity.getString(R.string.biometric_sensor_error))
                }
            }
        }, 150)
    }
}