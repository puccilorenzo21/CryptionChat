package com.lollo1344.cryptionchat

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.lollo1344.cryptionchat.databinding.ActivityBannedBinding

class BannedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBannedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBannedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Blocca il tasto indietro
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity() // Chiude l'app completamente
            }
        })

        binding.buttonExit.setOnClickListener {
            finishAffinity()
        }
    }
}