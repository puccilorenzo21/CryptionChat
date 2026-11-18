package com.lollo1344.cryptionchat

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.lollo1344.cryptionchat.databinding.ActivityResetPasswordBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// CORREZIONE: Eredita da BaseActivity invece di AppCompatActivity
class ResetPasswordActivity : BaseActivity() {

    private lateinit var binding: ActivityResetPasswordBinding
    private var userEmail: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResetPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userEmail = intent.getStringExtra("USER_EMAIL")
        if (userEmail == null) {
            Toast.makeText(this, "Errore: Email non fornita.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.buttonResetPassword.setOnClickListener {
            val code = binding.editTextCode.text.toString().trim()
            val newPassword = binding.editTextNewPassword.text.toString()

            if (code.length != 6) {
                Toast.makeText(this, "Il codice deve essere di 6 cifre", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (newPassword.isEmpty()) {
                Toast.makeText(this, "La nuova password non può essere vuota", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            submitNewPassword(userEmail!!, code, newPassword)
        }
    }

    private fun submitNewPassword(email: String, code: String, newPassword: String) {
        ApiClient.instance.submitPasswordReset(email = email, code = code, newPassword = newPassword)
            .enqueue(object : Callback<BaseResponse> {
                override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                    if (response.isSuccessful && response.body()?.status == "success") {
                        Toast.makeText(this@ResetPasswordActivity, response.body()?.message, Toast.LENGTH_LONG).show()
                        val intent = Intent(this@ResetPasswordActivity, LoginActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this@ResetPasswordActivity, "Errore: ${response.body()?.message}", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                    Toast.makeText(this@ResetPasswordActivity, "Errore di rete: ${t.message}", Toast.LENGTH_LONG).show()
                }
            })
    }
}