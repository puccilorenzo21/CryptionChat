package com.lollo1344.cryptionchat

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.lollo1344.cryptionchat.databinding.ActivityForgotPasswordBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// CORREZIONE: Eredita da BaseActivity invece di AppCompatActivity
class ForgotPasswordActivity : BaseActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.buttonSendCode.setOnClickListener {
            val email = binding.editTextEmail.text.toString().trim()
            if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Inserisci un indirizzo email valido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendResetCode(email)
        }
    }

    private fun sendResetCode(email: String) {
        ApiClient.instance.requestPasswordReset(email = email).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                if (response.isSuccessful && response.body()?.status == "success") {
                    Toast.makeText(this@ForgotPasswordActivity, response.body()?.message, Toast.LENGTH_LONG).show()
                    val intent = Intent(this@ForgotPasswordActivity, ResetPasswordActivity::class.java).apply {
                        putExtra("USER_EMAIL", email)
                    }
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this@ForgotPasswordActivity, "Errore: ${response.body()?.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                Toast.makeText(this@ForgotPasswordActivity, "Errore di rete: ${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }
}