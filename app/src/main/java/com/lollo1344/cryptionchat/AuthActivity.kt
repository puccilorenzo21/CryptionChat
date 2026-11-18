package com.lollo1344.cryptionchat

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.lollo1344.cryptionchat.databinding.ActivityAuthBinding

class AuthActivity : BaseActivity() {

    private lateinit var binding: ActivityAuthBinding
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (CallManager.isActive) {
            val intent = Intent(this, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
            return
        }

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (intent.getBooleanExtra("STOP_SOUND", false)) {
            val stopIntent = Intent(NotificationService.ACTION_STOP_SOUND).setPackage(packageName)
            sendBroadcast(stopIntent)
        }

        setupButtonListeners()
        checkForMaintenanceModeOnLaunch()
    }

    override fun onResume() {
        super.onResume()

        if (CallManager.isActive) return

        val prefs = SecurePrefs.get(this)
        val userId = prefs.getInt("user_id", -1)
        val biometricEnabled = prefs.getBoolean("biometric_auth_enabled", false)

        if (userId != -1) {
            if (!biometricEnabled) {
                handleSuccessfulAuth()
            } else {
                binding.buttonBiometricLogin.visibility = View.VISIBLE

                // Se l'app è già sbloccata (es. rotazione), entra
                if (AppLifecycleManager.isAppUnlocked) {
                    handleSuccessfulAuth()
                } else {
                    // TRIGGER AUTOMATICO:
                    // Usiamo un delay di 250ms per dare tempo alla finestra di ricevere il focus dal sistema,
                    // altrimenti il prompt biometrico fallirebbe silenziosamente.
                    uiHandler.postDelayed({
                        if (!isFinishing && !isDestroyed && !AppLifecycleManager.isAppUnlocked) {
                            showBiometricPrompt()
                        }
                    }, 250)
                }
            }
        } else {
            binding.buttonBiometricLogin.visibility = View.GONE
        }
    }

    private fun setupButtonListeners() {
        binding.buttonGoToLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        binding.buttonGoToRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
        binding.buttonBiometricLogin.setOnClickListener {
            showBiometricPrompt()
        }
    }

    private fun showBiometricPrompt() {
        if (!BiometricHelper.canAuthenticate(this)) return

        BiometricHelper.showBiometricPrompt(
            activity = this,
            onSuccess = {
                handleSuccessfulAuth()
            },
            onFailure = {
                // Fallimento (impronta errata), non facciamo nulla, l'utente può riprovare o usare il tasto
            },
            onError = { errorCode, _ ->
                // Errore tecnico o annullato dall'utente
            }
        )
    }

    private fun handleSuccessfulAuth() {
        AppLifecycleManager.isAppUnlocked = true

        if (intent.getBooleanExtra("IS_FROM_NOTIFICATION", false)) {
            val chatIntent = Intent(this, ChatActivity::class.java)
            intent.extras?.let { chatIntent.putExtras(it) }
            startActivity(chatIntent)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private fun checkForMaintenanceModeOnLaunch() {
        val prefs = SecurePrefs.get(this@AuthActivity)
        val userId = prefs.getInt("user_id", -1)
        if (userId == -1) {
            // Non bloccare la schermata di login se l'utente deve ancora accedere
            return
        }

        ApiClient.instance.checkForUpdates(System.currentTimeMillis()).enqueue(object : retrofit2.Callback<UpdateInfoResponse> {
            override fun onResponse(call: retrofit2.Call<UpdateInfoResponse>, response: retrofit2.Response<UpdateInfoResponse>) {
                if (isDestroyed || isFinishing) return
                val updateInfo = response.body() ?: return
                if (updateInfo.maintenanceMode) {
                    val intent = Intent(this@AuthActivity, MaintenanceActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        putExtra("MAINTENANCE_MESSAGE", updateInfo.maintenanceMessage)
                    }
                    startActivity(intent)
                    finish()
                }
            }
            override fun onFailure(call: retrofit2.Call<UpdateInfoResponse>, t: Throwable) {}
        })
    }
}