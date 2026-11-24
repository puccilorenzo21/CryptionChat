package com.lollo1344.cryptionchat

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lollo1344.cryptionchat.databinding.ActivityMaintenanceBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * ACTIVITY MODIFICATA:
 * Ora include un controllo periodico per verificare se la modalità manutenzione è terminata.
 * Se disattivata, attende 1 secondo e riavvia il normale flusso dell'app.
 */
class MaintenanceActivity : BaseActivity() {

    private lateinit var binding: ActivityMaintenanceBinding
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var checkRunnable: Runnable
    private val CHECK_INTERVAL = 15000L // Controlla ogni 15 secondi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMaintenanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val message = intent.getStringExtra("MAINTENANCE_MESSAGE")
            ?: getString(R.string.maintenance_default_message)

        binding.textViewMaintenanceMessage.text = message

        setupMaintenanceCheck()
    }

    private fun setupMaintenanceCheck() {
        checkRunnable = Runnable {
            // Inviamo il timestamp corrente come parametro per aggirare la cache del server
            ApiClient.instance.checkForUpdates(System.currentTimeMillis()).enqueue(object : Callback<UpdateInfoResponse> {
                override fun onResponse(call: Call<UpdateInfoResponse>, response: Response<UpdateInfoResponse>) {
                    // Se la chiamata ha successo E la modalità manutenzione è disattivata
                    if (response.isSuccessful && response.body()?.maintenanceMode == false && !isFinishing) {
                        exitMaintenanceMode()
                    } else {
                        // Altrimenti, se siamo ancora in manutenzione, ripianifica il prossimo controllo
                        handler.postDelayed(checkRunnable, CHECK_INTERVAL)
                    }
                }

                override fun onFailure(call: Call<UpdateInfoResponse>, t: Throwable) {
                    // In caso di errore di rete, riprova comunque dopo l'intervallo
                    Log.w("MaintenanceActivity", "Controllo stato manutenzione fallito: ${t.message}")
                    handler.postDelayed(checkRunnable, CHECK_INTERVAL)
                }
            })
        }
    }

    private fun exitMaintenanceMode() {
        // Interrompi i controlli futuri per evitare azioni multiple
        handler.removeCallbacks(checkRunnable)

        // Attendi 2 secondi prima di procedere
        Handler(Looper.getMainLooper()).postDelayed({
            // Controlla di nuovo che l'activity sia ancora attiva prima di fare qualsiasi cosa
            if (!isFinishing) {
                // Riavvia l'app dal suo punto di ingresso principale (AuthActivity)
                val intent = Intent(this, AuthActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish() // Chiudi la schermata di manutenzione
            }
        }, 1000) // 1000 millisecondi = 1 secondo
    }

    override fun onResume() {
        super.onResume()
        // Avvia il controllo periodico quando l'activity diventa visibile
        handler.post(checkRunnable)
    }

    override fun onPause() {
        super.onPause()
        // Interrompi il controllo periodico per risparmiare batteria quando l'app non è in primo piano
        handler.removeCallbacks(checkRunnable)
    }

    override fun onBackPressed() {
        // Non fare nulla per bloccare l'utente su questa schermata.
    }
}