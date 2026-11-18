package com.lollo1344.cryptionchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed class AIGenerationResult {
    data class Success(val bitmap: Bitmap) : AIGenerationResult()
    data class Failure(val message: String) : AIGenerationResult()
}

object AIImageHelper {
    private const val TAG = "AIImageHelper"

    private val client by lazy {
        ApiClient.okHttpClient.newBuilder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun generateImage(context: Context, prompt: String): AIGenerationResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Avvio generazione immagine. Prompt originale: $prompt")

            // 1. TRADUZIONE AUTOMATICA DALL'ITALIANO ALL'INGLESE
            val englishPrompt = translateToEnglish(prompt)
            Log.d(TAG, "Prompt tradotto in inglese: $englishPrompt")

            // 2. MIGLIORAMENTO AUTOMATICO (Prompt Enhancement stile Gemini)
            val enhancedPrompt = "$englishPrompt, masterpiece, highly detailed, stunning wallpaper, best quality, 8k resolution, cinematic lighting, vibrant colors"

            // Codifica il testo per l'URL
            val encodedPrompt = URLEncoder.encode(enhancedPrompt, "UTF-8").replace("+", "%20")

            // Un seme casuale ad altissima variabilità (fino a 9 cifre) per evitare caching
            val seed = (100000000..999999999).random()

            // Recuperiamo la configurazione dalle preferenze protette
            val prefs = SecurePrefs.get(context)
            val selectedModel = prefs.getString("pollinations_image_model", "flux_free") ?: "flux_free"
            val apiKey = prefs.getString("pollinations_api_key", "") ?: ""

            // Determiniamo l'URL corretto in base alla scelta dell'utente
            val url = if (selectedModel == "flux_free" || apiKey.isBlank()) {
                // FLUX gratuito e illimitato (non richiede né usa crediti API)
                "https://image.pollinations.ai/prompt/$encodedPrompt?width=1080&height=1920&nologo=true&seed=$seed&model=flux"
            } else {
                // Modelli PRO premium (richiedono chiave API salvata su enter.pollinations.ai)
                "https://gen.pollinations.ai/image/$encodedPrompt?model=$selectedModel&width=1024&height=1024&seed=$seed&key=$apiKey"
            }

            Log.d(TAG, "URL Generazione: $url")

            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .get()
                .build()

            Log.d(TAG, "Download immagine in corso da Pollinations (Seed: $seed)...")
            val response = client.newCall(request).execute()

            // Gestione dei fallimenti HTTP per mostrare risposte mirate all'utente
            if (!response.isSuccessful) {
                val code = response.code
                val bodyString = response.body?.string() ?: ""
                Log.e(TAG, "Errore API Immagini: HTTP $code. Response: $bodyString")

                var serverErrorMessage = "Nessun messaggio dal server."
                try {
                    val json = JSONObject(bodyString)
                    val errorObj = json.optJSONObject("error")
                    if (errorObj != null) {
                        serverErrorMessage = errorObj.optString("message", serverErrorMessage)
                    }
                } catch (e: Exception) {
                    // Fallback al testo grezzo se il JSON non è parsabile
                    if (bodyString.isNotBlank()) serverErrorMessage = bodyString
                }

                val userFriendlyMessage = when (code) {
                    401 -> "Chiave API non valida o non autorizzata.\nVerifica la chiave inserita nelle impostazioni della generazione."
                    402 -> "Crediti insufficienti nel portafoglio Pollinations!\n\n$serverErrorMessage\n\nPuoi ricaricare su enter.pollinations.ai o reimpostare il modello Flux Gratuito ed illimitato nelle impostazioni."
                    else -> "Errore del server: $serverErrorMessage (HTTP $code)"
                }
                return@withContext AIGenerationResult.Failure(userFriendlyMessage)
            }

            val inputStream = response.body?.byteStream()
            if (inputStream == null) {
                return@withContext AIGenerationResult.Failure("Corpo della risposta vuoto dal server.")
            }

            val bitmap = BitmapFactory.decodeStream(inputStream)
            if (bitmap != null) {
                return@withContext AIGenerationResult.Success(bitmap)
            } else {
                return@withContext AIGenerationResult.Failure("Impossibile decodificare l'immagine scaricata.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Eccezione durante la generazione dell'immagine", e)
            return@withContext AIGenerationResult.Failure("Errore di rete durante il contatto con il server: ${e.localizedMessage ?: "connessione assente"}")
        }
    }

    /**
     * Usa l'API pubblica e gratuita di MyMemory per tradurre il prompt dall'italiano all'inglese.
     */
    private fun translateToEnglish(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=it|en"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val jsonObject = JSONObject(body)
                    val responseData = jsonObject.optJSONObject("responseData")
                    val translatedText = responseData?.optString("translatedText")

                    if (!translatedText.isNullOrEmpty()) {
                        return translatedText
                    }
                }
            }
            text
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante la traduzione del prompt", e)
            text
        }
    }
}