package com.lollo1344.cryptionchat

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import com.lollo1344.cryptionchat.databinding.ActivityRegisterBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// CORREZIONE: Eredita da BaseActivity invece di AppCompatActivity
class RegisterActivity : BaseActivity() {

    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonRegister.setOnClickListener {
            binding.usernameInputLayout.error = null
            binding.emailInputLayout.error = null
            binding.passwordInputLayout.error = null

            val username = binding.editTextUsername.text.toString().trim()
            val email = binding.editTextEmail.text.toString().trim()
            val password = binding.editTextPassword.text.toString().trim()

            var isValid = true
            if (username.isEmpty()) {
                binding.usernameInputLayout.error = "Il nome utente è obbligatorio"
                isValid = false
            }
            if (email.isEmpty()) {
                binding.emailInputLayout.error = "L'email è obbligatoria"
                isValid = false
            } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                binding.emailInputLayout.error = "Inserisci un indirizzo email valido"
                isValid = false
            }
            if (password.isEmpty()) {
                binding.passwordInputLayout.error = "La password è obbligatoria"
                isValid = false
            }

            if (!isValid) {
                return@setOnClickListener
            }

            // MODIFICATO: Chiama il primo step della registrazione (richiesta codice)
            requestVerificationCode(username, email, password)
        }
    }

    private fun requestVerificationCode(username: String, email: String, password: String) {
        val deviceId = DeviceUtils.getDeviceId(this) // <-- RECUPERA ID

        ApiClient.instance.registerRequestCode(username = username, email = email, password = password, deviceId = deviceId)
            .enqueue(object : Callback<BaseResponse> {
                override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                    // --- CONTROLLO BAN ---
                    if (response.body()?.status == "banned") {
                        BanHelper.executeBan(this@RegisterActivity)
                        return
                    }

                    // ... (resto del codice esistente per il success)
                    if (response.isSuccessful && response.body()?.status == "success") {
                        Toast.makeText(this@RegisterActivity, "Codice inviato! Controlla la tua email.", Toast.LENGTH_LONG).show()

                        // Avvia l'activity per inserire il codice
                        val intent = Intent(this@RegisterActivity, VerifyCodeActivity::class.java).apply {
                            putExtra("USERNAME", username)
                            putExtra("EMAIL", email)
                            putExtra("PASSWORD", password)
                        }
                        startActivity(intent)

                    } else {
                        Toast.makeText(this@RegisterActivity, "Errore: ${response.body()?.message}", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                    Toast.makeText(this@RegisterActivity, "Errore di rete: ${t.message}", Toast.LENGTH_LONG).show()
                }
            })
    }
}