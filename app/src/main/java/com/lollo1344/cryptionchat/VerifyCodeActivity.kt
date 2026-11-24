package com.lollo1344.cryptionchat

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.lollo1344.cryptionchat.databinding.ActivityVerifyCodeBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// CORREZIONE: Eredita da BaseActivity invece di AppCompatActivity
class VerifyCodeActivity : BaseActivity() {

    private lateinit var binding: ActivityVerifyCodeBinding
    private var username = ""
    private var email = ""
    private var password = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerifyCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        username = intent.getStringExtra("USERNAME") ?: ""
        email = intent.getStringExtra("EMAIL") ?: ""
        password = intent.getStringExtra("PASSWORD") ?: ""

        if (username.isEmpty() || email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, getString(R.string.verify_missing_reg_data), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.buttonVerify.setOnClickListener {
            val code = binding.editTextVerificationCode.text.toString().trim()
            if (code.length != 6) {
                Toast.makeText(this, getString(R.string.code_must_be_6_digits), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            verifyCodeAndRegister(code)
        }
    }

    private fun verifyCodeAndRegister(code: String) {
        ApiClient.instance.registerVerifyCode(
            username = username,
            email = email,
            password = password,
            code = code
        ).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                if (response.isSuccessful && response.body()?.status == "success") {
                    Toast.makeText(this@VerifyCodeActivity, getString(R.string.verify_registration_complete), Toast.LENGTH_LONG).show()
                    val intent = Intent(this@VerifyCodeActivity, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this@VerifyCodeActivity, getString(R.string.error_prefix, response.body()?.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                Toast.makeText(this@VerifyCodeActivity, getString(R.string.error_network_msg, t.message ?: ""), Toast.LENGTH_LONG).show()
            }
        })
    }
}