package com.lollo1344.cryptionchat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

object AIDialogHelper {

    fun showAIGeneratorDialog(
        activity: Activity,
        buttonText: String = "Imposta come Sfondo",
        onImageSelected: (Bitmap) -> Unit
    ) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_ai_generator, null)

        val editPrompt = dialogView.findViewById<TextInputEditText>(R.id.edit_text_ai_prompt)
        val btnGenerate = dialogView.findViewById<MaterialButton>(R.id.btn_generate_ai)
        val btnAiSettings = dialogView.findViewById<ImageButton>(R.id.btn_ai_settings)
        val loadingLayout = dialogView.findViewById<View>(R.id.ai_loading_layout)
        val previewCard = dialogView.findViewById<MaterialCardView>(R.id.ai_preview_card)
        val previewImage = dialogView.findViewById<ImageView>(R.id.ai_preview_image)
        val btnSetBg = dialogView.findViewById<MaterialButton>(R.id.btn_set_generated_bg)

        btnSetBg.text = buttonText

        var lastGeneratedBitmap: Bitmap? = null

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .create()

        val presets = mapOf(
            R.id.preset_cyberpunk to "Neon cyberpunk portrait, futuristic city background, vibrant neon glow, hyperrealistic 8k render, octane render",
            R.id.preset_landscape to "Breathtaking minimalist vector landscape, warm sunset gradient, majestic mountains and pine trees, aesthetic flat design",
            R.id.preset_abstract to "Vaporwave abstract 3D geometric shapes, dark blue and purple neon gradient, fluid art, octane render",
            R.id.preset_space to "Deep space nebula, glowing cosmic dust, distant galaxies, photorealistic, cinematic lighting, 8k wallpaper",
            R.id.preset_rainy to "Cozy rainy city night view through a wet glass window, blurry neon streetlights, lofi aesthetic, dark chill vibe",
            R.id.preset_anime to "Aesthetic anime art style, japanese city street at dusk, cherry blossoms, Studio Ghibli inspired, vibrant colors",
            R.id.preset_3d_avatar to "Cute 3D Pixar style character avatar, smooth clay lighting, vibrant colors, clean studio background, highly detailed",
            R.id.preset_fantasy to "Epic dark fantasy kingdom, glowing magical runes, misty enchanted forest, dramatic cinematic lighting, concept art",
            R.id.preset_synthwave to "80s synthwave retro sunset, grid landscape, neon palm trees, chrome reflections, vintage VHS aesthetic",
            R.id.preset_dark_gothic to "Dark gothic castle under a full moon, dramatic shadows, mysterious fog, dark academia aesthetic, ultra detailed"
        )

        presets.forEach { (btnId, prompt) ->
            dialogView.findViewById<Chip>(btnId)?.setOnClickListener { chipView ->
                val chip = chipView as Chip
                if (chip.isChecked) {
                    editPrompt.setText(prompt)
                }
            }
        }

        btnAiSettings.setOnClickListener {
            showAiSettingsDialog(activity)
        }

        btnGenerate.setOnClickListener {
            val prompt = editPrompt.text.toString().trim()
            if (prompt.isBlank()) {
                Toast.makeText(activity, "La descrizione è obbligatoria.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnGenerate.isEnabled = false
            loadingLayout.visibility = View.VISIBLE
            previewCard.visibility = View.GONE
            btnSetBg.visibility = View.GONE
            dialog.setCancelable(false)

            val lifecycleOwner = activity as? LifecycleOwner ?: return@setOnClickListener
            lifecycleOwner.lifecycleScope.launch {
                val result = AIImageHelper.generateImage(activity, prompt)

                btnGenerate.isEnabled = true
                loadingLayout.visibility = View.GONE
                dialog.setCancelable(true)

                when (result) {
                    is AIGenerationResult.Success -> {
                        lastGeneratedBitmap = result.bitmap
                        previewImage.setImageBitmap(result.bitmap)
                        previewCard.visibility = View.VISIBLE
                        btnSetBg.visibility = View.VISIBLE
                    }
                    is AIGenerationResult.Failure -> {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle("Generazione Fallita")
                            .setMessage(result.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }

        btnSetBg.setOnClickListener {
            val bitmap = lastGeneratedBitmap
            if (bitmap != null) {
                onImageSelected(bitmap)
                dialog.dismiss()
            }
        }

        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        dialog.show()
    }

    // Overload retrocompatibile per lo sfondo chat
    fun showAIGeneratorDialog(
        activity: Activity,
        target: String,
        groupId: Int? = null,
        onBackgroundApplied: () -> Unit
    ) {
        showAIGeneratorDialog(activity, "Imposta come Sfondo") { bitmap ->
            if (target == ChatBackgroundManager.TARGET_CHAT) {
                if (ChatBackgroundManager.setCustomBackgroundFromBitmap(activity, bitmap, target, groupId)) {
                    Toast.makeText(activity, "Sfondo chat aggiornato!", Toast.LENGTH_SHORT).show()
                    onBackgroundApplied()
                } else {
                    Toast.makeText(activity, "Errore salvataggio.", Toast.LENGTH_SHORT).show()
                }
            } else {
                val options = arrayOf("Solo Schermata Home", "Solo Chat Globale", "Entrambi")
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Destinazione Sfondo")
                    .setItems(options) { _, which ->
                        val finalTarget = when (which) {
                            0 -> ChatBackgroundManager.TARGET_HOME
                            1 -> ChatBackgroundManager.TARGET_CHAT
                            else -> ChatBackgroundManager.TARGET_BOTH
                        }
                        if (ChatBackgroundManager.setCustomBackgroundFromBitmap(activity, bitmap, finalTarget)) {
                            Toast.makeText(activity, "Sfondo applicato!", Toast.LENGTH_SHORT).show()
                            onBackgroundApplied()
                        } else {
                            Toast.makeText(activity, "Errore di salvataggio.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            }
        }
    }

    private fun showAiSettingsDialog(activity: Activity) {
        val prefs = SecurePrefs.get(activity)
        val currentModel = prefs.getString("pollinations_image_model", "flux_free") ?: "flux_free"
        val currentKey = prefs.getString("pollinations_api_key", "") ?: ""

        val scrollView = ScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val linearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, activity.resources.displayMetrics).toInt()
            setPadding(padding, padding, padding, padding)
        }

        val infoCard = MaterialCardView(activity).apply {
            cardElevation = 0f
            strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, activity.resources.displayMetrics).toInt()
            setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.outline_variant_light)))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics).toInt()
            }
            layoutParams = params
            visibility = View.GONE
        }

        val infoLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val p = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()
            setPadding(p, p, p, p)
        }
        infoCard.addView(infoLayout)

        val avatarImage = ShapeableImageView(activity).apply {
            val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, activity.resources.displayMetrics).toInt()
            val params = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()
            }
            this.layoutParams = params
            this.shapeAppearanceModel = ShapeAppearanceModel().toBuilder()
                .setAllCorners(CornerFamily.ROUNDED, size / 2f)
                .build()
            this.setImageResource(R.drawable.ic_person)
        }
        infoLayout.addView(avatarImage)

        val textLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = params
        }
        infoLayout.addView(textLayout)

        val accountNameText = TextView(activity).apply {
            text = "Nome Account: Caricamento..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        textLayout.addView(accountNameText)

        val balanceText = TextView(activity).apply {
            text = "Saldo Totale: Caricamento..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(activity, R.color.primary_light))
        }
        textLayout.addView(balanceText)

        val questBalanceText = TextView(activity).apply {
            text = "Quest: ..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(activity, R.color.primary_light))
            visibility = View.GONE
        }
        textLayout.addView(questBalanceText)

        val paidBalanceText = TextView(activity).apply {
            text = "Paid: ..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(activity, R.color.primary_light))
            visibility = View.GONE
        }
        textLayout.addView(paidBalanceText)

        val usageText = TextView(activity).apply {
            text = "Uso questa chiave: Caricamento..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(activity, R.color.outline_dark))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, activity.resources.displayMetrics).toInt()
            }
            layoutParams = params
        }
        textLayout.addView(usageText)

        linearLayout.addView(infoCard)

        val keyInputLayout = TextInputLayout(activity).apply {
            hint = "Pollinations API Key"
            helperText = "Inserisci una chiave per abilitare i modelli PRO"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_CUSTOM
            endIconDrawable = ContextCompat.getDrawable(activity, R.drawable.ic_check_circle)
            setEndIconContentDescription("Verifica Chiave")
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
            }
            layoutParams = params
        }

        val keyEditText = TextInputEditText(activity).apply {
            setText(currentKey)
        }
        keyInputLayout.addView(keyEditText)
        linearLayout.addView(keyInputLayout)

        val linkText = TextView(activity).apply {
            text = "Crea una chiave API su enter.pollinations.ai"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(activity, R.color.primary_light))
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics).toInt()
                topMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, -8f, activity.resources.displayMetrics).toInt()
                leftMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, activity.resources.displayMetrics).toInt()
            }
            this.layoutParams = params
            setOnClickListener {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://enter.pollinations.ai/#keys"))
                    activity.startActivity(browserIntent)
                } catch (e: Exception) {
                    Toast.makeText(activity, "Impossibile aprire il browser.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        linearLayout.addView(linkText)

        fun loadAccountStats(apiKey: String) {
            infoCard.visibility = View.VISIBLE
            accountNameText.text = "Aggiornamento in corso..."
            balanceText.text = "Saldo Totale: ..."
            questBalanceText.text = "Quest: ..."
            paidBalanceText.text = "Paid: ..."
            usageText.text = "Uso questa chiave: ..."
            avatarImage.setImageResource(R.drawable.ic_person)

            fetchPollinationsAccountInfo(activity, apiKey) { name: String?, total: Double?, allowance: Double?, pack: Double?, imageUrl: String?, usageCount: Int, totalCost: Double ->
                if (name != null) {
                    accountNameText.text = name

                    if (allowance != null || pack != null) {
                        balanceText.text = "Saldo Totale: ${if (total != null && total >= 0) String.format(Locale.getDefault(), "%.4f", total) else "N/D"} Pollen"
                        questBalanceText.text = "Quest: ${if (allowance != null && allowance >= 0) String.format(Locale.getDefault(), "%.4f", allowance) else "0.0000"} Pollen"
                        questBalanceText.visibility = View.VISIBLE
                        paidBalanceText.text = "Paid: ${if (pack != null && pack >= 0) String.format(Locale.getDefault(), "%.4f", pack) else "0.0000"} Pollen"
                        paidBalanceText.visibility = View.VISIBLE
                    } else {
                        balanceText.text = "Limite Chiave: ${if (total != null && total >= 0) String.format(Locale.getDefault(), "%.2f", total) else "N/D"} Pollen"
                        questBalanceText.visibility = View.GONE
                        paidBalanceText.visibility = View.GONE
                    }

                    usageText.text = "Uso questa chiave: \$${String.format(Locale.getDefault(), "%.4f", totalCost)} (${usageCount} gen.)"
                    if (!imageUrl.isNullOrBlank()) {
                        Glide.with(activity).load(imageUrl).placeholder(R.drawable.ic_person).into(avatarImage)
                    }
                } else {
                    accountNameText.text = "Chiave Non Valida"
                    balanceText.text = "Verifica i permessi della chiave"
                    questBalanceText.visibility = View.GONE
                    paidBalanceText.visibility = View.GONE
                    usageText.text = ""
                }
            }
        }

        keyInputLayout.setEndIconOnClickListener {
            val enteredKey = keyEditText.text.toString().trim()
            if (enteredKey.isNotBlank()) {
                loadAccountStats(enteredKey)
            } else {
                infoCard.visibility = View.GONE
                Toast.makeText(activity, "Inserisci prima una chiave API", Toast.LENGTH_SHORT).show()
            }
        }

        if (currentKey.isNotBlank()) {
            loadAccountStats(currentKey)
        }

        val modelLabel = TextView(activity).apply {
            text = "Seleziona Modello di Generazione:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
                topMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
            }
            layoutParams = params
        }
        linearLayout.addView(modelLabel)

        val models = listOf(
            "flux_free" to "Flux (Gratuito, senza limiti)",
            "kontext" to "Kontext (Pro - richiede API Key)",
            "gptimage" to "GPT Image (Pro - richiede API Key)",
            "gptimage-large" to "GPT Image Large (Pro - richiede API Key)",
            "zimage" to "ZImage (Pro - richiede API Key)",
            "klein" to "Klein (Pro - richiede API Key)",
            "nova-canvas" to "Nova Canvas (Pro - richiede API Key)",
            "flux" to "Flux Pro (Pro - richiede API Key)"
        )

        val radioGroup = android.widget.RadioGroup(activity).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }

        models.forEach { (modelId, displayName) ->
            val radioButton = com.google.android.material.radiobutton.MaterialRadioButton(activity).apply {
                id = View.generateViewId()
                text = displayName
                tag = modelId
                isChecked = (modelId == currentModel)
            }
            radioGroup.addView(radioButton)
        }
        linearLayout.addView(radioGroup)

        scrollView.addView(linearLayout)

        MaterialAlertDialogBuilder(activity)
            .setTitle("Configura Generatore")
            .setView(scrollView)
            .setPositiveButton("Salva") { _, _ ->
                val newKey = keyEditText.text.toString().trim()
                val checkedId = radioGroup.checkedRadioButtonId
                val checkedRadioButton = radioGroup.findViewById<View>(checkedId) as? com.google.android.material.radiobutton.MaterialRadioButton
                val newModel = checkedRadioButton?.tag as? String ?: "flux_free"

                prefs.edit().apply {
                    putString("pollinations_api_key", newKey)
                    putString("pollinations_image_model", newModel)
                    apply()
                }

                if (newModel == "flux_free" || newKey.isBlank()) {
                    Toast.makeText(activity, "Impostato modello Flux gratuito ed illimitato.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Salvato modello PRO: $newModel", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun fetchPollinationsAccountInfo(
        context: Context,
        apiKey: String,
        onResult: (name: String?, total: Double?, allowance: Double?, pack: Double?, imageUrl: String?, usageCount: Int, totalCost: Double) -> Unit
    ) {
        if (apiKey.isBlank()) {
            onResult(null, null, null, null, null, 0, 0.0)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            var name: String? = null
            var totalBalance: Double? = null
            var allowanceBalance: Double? = null
            var paidBalance: Double? = null
            var imageUrl: String? = null
            var usageCount = 0
            var totalCost = 0.0

            val client = ApiClient.okHttpClient

            try {
                val profileRequest = Request.Builder()
                    .url("https://gen.pollinations.ai/account/profile")
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                client.newCall(profileRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            name = json.optString("name", "").ifBlank {
                                json.optString("githubUsername", "")
                            }
                            imageUrl = json.optString("image", null)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AIDialogHelper", "Errore nel caricamento del profilo Pollinations", e)
            }

            try {
                val balanceRequest = Request.Builder()
                    .url("https://gen.pollinations.ai/account/balance")
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                client.newCall(balanceRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            if (json.has("allowance") || json.has("pack") || json.has("total")) {
                                allowanceBalance = json.optDouble("allowance", 0.0)
                                paidBalance = json.optDouble("pack", 0.0)
                                totalBalance = json.optDouble("total", -1.0)
                            } else {
                                totalBalance = json.optDouble("balance", -1.0)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AIDialogHelper", "Errore nel caricamento del bilancio Pollinations", e)
            }

            try {
                val usageRequest = Request.Builder()
                    .url("https://gen.pollinations.ai/account/key/usage")
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                client.newCall(usageRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            val usageArray = json.optJSONArray("usage")
                            if (usageArray != null) {
                                usageCount = usageArray.length()
                                for (i in 0 until usageCount) {
                                    val usageItem = usageArray.getJSONObject(i)
                                    totalCost += usageItem.optDouble("cost_usd", 0.0)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AIDialogHelper", "Errore nel caricamento del report di consumo della chiave", e)
            }

            withContext(Dispatchers.Main) {
                onResult(name, totalBalance, allowanceBalance, paidBalance, imageUrl, usageCount, totalCost)
            }
        }
    }
}