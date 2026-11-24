package com.lollo1344.cryptionchat

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.lollo1344.cryptionchat.databinding.ActivityLoginBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.buttonLogin.setOnClickListener {
            val email = binding.editTextEmail.text.toString().trim()
            val password = binding.editTextPassword.text.toString().trim()

            if (PanicHelper.isPanicTriggered(this, password)) {
                PanicHelper.executePanicProtocol(this)
                return@setOnClickListener
            }

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, getString(R.string.login_email_password_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loginUser(email, password)
        }

        binding.textViewForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
    }

    private fun loginUser(email: String, password: String) {
        val deviceId = DeviceUtils.getDeviceId(this)

        ApiClient.instance.login(email = email, password = password, deviceId = deviceId).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                val loginResponse = response.body()

                if (loginResponse?.status == "banned") {
                    BanHelper.executeBan(this@LoginActivity)
                    return
                }

                if (response.isSuccessful && loginResponse?.status == "success") {
                    val userData = loginResponse.data
                    if (userData != null) {
                        Toast.makeText(this@LoginActivity, getString(R.string.login_success), Toast.LENGTH_SHORT).show()

                        val securePrefs = SecurePrefs.get(this@LoginActivity)
                        SessionLifecycleHelper.prepareForAuthenticatedUser(securePrefs, userData.userId) {
                            AppDatabase.deleteLocalDatabase(this@LoginActivity)
                        }
                        ApiClient.resetUnauthorizedRedirectFlag()

                        val prefs = securePrefs.edit()
                        prefs.putInt("user_id", userData.userId)
                        prefs.putString("username", userData.username)
                        prefs.putString("profile_picture_url", userData.profilePictureUrl)

                        // Sincronizza lo stato Beta salvato sul server
                        prefs.putBoolean("is_beta_tester", userData.isBetaTester ?: false)

                        if (!userData.token.isNullOrEmpty()) {
                            prefs.putString("auth_token", userData.token)
                        }

                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        sdf.timeZone = TimeZone.getTimeZone("UTC")
                        val currentUtcTimestamp = sdf.format(Date())
                        prefs.putString("last_notification_timestamp", currentUtcTimestamp)

                        // Sostituito apply() con commit() per garantire la persistenza sincrona del token
                        prefs.commit()

                        ApiClient.reloadConfiguration(this@LoginActivity)

                        E2eeSessionOrchestrator.syncPreKeyBundleAsync(this@LoginActivity, userData.userId)

                        AppLifecycleManager.isAppUnlocked = true

                        ServiceScheduler.startService(this@LoginActivity)

                        val intent = Intent(this@LoginActivity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()

                    } else {
                        Toast.makeText(this@LoginActivity, getString(R.string.login_user_data_missing), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@LoginActivity, getString(R.string.error_prefix, loginResponse?.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                Toast.makeText(this@LoginActivity, getString(R.string.error_network_msg, t.message ?: ""), Toast.LENGTH_LONG).show()
            }
        })
    }
}