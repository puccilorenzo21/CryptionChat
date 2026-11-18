package com.lollo1344.cryptionchat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.webrtc.PeerConnection
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

object MeteredcaManager {
    private const val TAG = "MeteredcaManager"

    // Account 1: Free Tier (20 GB)
    private const val APP1_NAME = "YOUR_METERED_APP1_NAME"
    private const val API1_KEY = "YOUR_METERED_API1_KEY"

    // Account 2: Trial Global (500 MB - Veloce)
    private const val APP2_NAME = "YOUR_METERED_APP2_NAME"
    private const val API2_KEY = "YOUR_METERED_API2_KEY"

    // Soglia prudenziale di 400 MB per evitare sforamenti improvvisi
    private const val LIMIT_BYTES = 400L * 1024L * 1024L

    private val client by lazy {
        ApiClient.okHttpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // Cache in memoria delle credenziali per ottimizzare i tempi d'avvio delle chiamate
    @Volatile
    private var cachedIceServers: List<PeerConnection.IceServer>? = null
    @Volatile
    private var cacheTimestamp: Long = 0L
    private const val CACHE_DURATION_MS = 12 * 60 * 60 * 1000L // Validità cache: 12 ore

    /**
     * Esegue il pre-caricamento preventivo dei server ICE in memoria in background.
     * Da invocare ad esempio all'avvio dell'applicazione per azzerare i tempi di caricamento delle chiamate.
     */
    fun preloadIceServers(context: Context) {
        // Se abbiamo già una cache recente e valida in memoria, evita richieste duplicate a vuoto
        if (cachedIceServers != null && (System.currentTimeMillis() - cacheTimestamp < CACHE_DURATION_MS)) {
            Log.d(TAG, "preloadIceServers: Cache già valida. Salto il pre-caricamento di rete.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val servers = fetchIceServersInternal(context)
                if (servers.isNotEmpty()) {
                    Log.i(TAG, "Pre-caricamento preventivo dei server ICE completato con successo.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante il pre-caricamento preventivo dei server ICE", e)
            }
        }
    }

    /**
     * Verifica la quota mensile utilizzata. Reimposta il contatore a 0 se il mese corrente è cambiato.
     * Ritorna true se l'Account 2 (Fast) è utilizzabile, false altrimenti.
     */
    fun checkAndManageUsage(context: Context): Boolean {
        val prefs = SecurePrefs.get(context)
        val currentMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val savedMonth = prefs.getString("metered_account2_month", "")

        if (currentMonth != savedMonth) {
            prefs.edit().apply {
                putString("metered_account2_month", currentMonth)
                putLong("metered_account2_bytes", 0L)
                apply()
            }
            Log.i(TAG, "Nuovo mese rilevato ($currentMonth). Contatore Metered.ca Account2 azzerato.")
            return true
        }

        val bytesUsed = prefs.getLong("metered_account2_bytes", 0L)
        val hasQuota = bytesUsed < LIMIT_BYTES
        Log.i(TAG, "Metered.ca Account2 - Consumo mensile: $bytesUsed / $LIMIT_BYTES byte. Disponibile: $hasQuota")
        return hasQuota
    }

    /**
     * Incrementa il consumo mensile registrato per l'Account 2.
     */
    fun addBytesUsed(context: Context, bytes: Long) {
        val prefs = SecurePrefs.get(context)
        val currentMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val savedMonth = prefs.getString("metered_account2_month", "")

        if (currentMonth == savedMonth) {
            val currentBytes = prefs.getLong("metered_account2_bytes", 0L)
            prefs.edit().putLong("metered_account2_bytes", currentBytes + bytes).apply()
        }
    }

    /**
     * Restituisce la lista di server ICE. Se presente una cache valida in memoria, la restituisce istantaneamente,
     * altrimenti interroga l'API REST di metered.live in modalità asincrona.
     */
    suspend fun fetchIceServers(context: Context, forceRefresh: Boolean = false): List<PeerConnection.IceServer> {
        if (!forceRefresh && cachedIceServers != null && (System.currentTimeMillis() - cacheTimestamp < CACHE_DURATION_MS)) {
            Log.i(TAG, "Utilizzo server ICE memorizzati nella cache locale.")
            return cachedIceServers!!
        }
        return fetchIceServersInternal(context)
    }

    /**
     * Recupera asincronamente i server ICE (STUN/TURN) dall'endpoint di metered.live.
     * Effettua il fallback automatico su account1 se account2 ha esaurito la banda o se la richiesta fallisce.
     */
    private suspend fun fetchIceServersInternal(context: Context): List<PeerConnection.IceServer> {
        val useAccount2 = checkAndManageUsage(context)
        val selectedApp: String
        val selectedKey: String

        if (useAccount2) {
            selectedApp = APP2_NAME
            selectedKey = API2_KEY
            Log.i(TAG, "Tentativo di utilizzo dell'Account 2 (Fast - 500 MB) per una latenza ottimale.")
        } else {
            selectedApp = APP1_NAME
            selectedKey = API1_KEY
            Log.i(TAG, "Quota dell'Account 2 esaurita. Fallback automatico su Account 1 (Primary - 20 GB).")
        }

        val url = "https://$selectedApp.metered.live/api/v1/turn/credentials?apiKey=$selectedKey"

        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val bodyString = response.body?.string()
                if (!bodyString.isNullOrBlank()) {
                    val iceServersList = parseIceServersJson(bodyString)
                    if (iceServersList.isNotEmpty()) {
                        SecurePrefs.get(context).edit().putString("active_call_metered_app", selectedApp).apply()

                        // Aggiorna la cache in memoria
                        cachedIceServers = iceServersList
                        cacheTimestamp = System.currentTimeMillis()

                        return iceServersList
                    }
                }
            }
            Log.e(TAG, "Richiesta credenziali fallita (HTTP ${response.code}). Provo il recupero alternativo.")
        } catch (e: Exception) {
            Log.e(TAG, "Eccezione durante il recupero dei server ICE per $selectedApp", e)
        }

        // Se l'Account 2 ha fallito per problemi di rete, proviamo l'Account 1 in modo dinamico prima del backup statico
        if (selectedApp == APP2_NAME) {
            Log.i(TAG, "Tentativo di recupero di emergenza dinamico su Account 1...")
            val fallbackUrl = "https://$APP1_NAME.metered.live/api/v1/turn/credentials?apiKey=$API1_KEY"
            try {
                val request = Request.Builder().url(fallbackUrl).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (!bodyString.isNullOrBlank()) {
                        val iceServersList = parseIceServersJson(bodyString)
                        if (iceServersList.isNotEmpty()) {
                            SecurePrefs.get(context).edit().putString("active_call_metered_app", APP1_NAME).apply()

                            // Aggiorna la cache in memoria
                            cachedIceServers = iceServersList
                            cacheTimestamp = System.currentTimeMillis()

                            return iceServersList
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Incapace di stabilire la connessione con gli endpoint di metered.live.", e)
            }
        }

        // Fallback statico finale offline basato sulla scelta iniziale del consumo quota
        Log.w(TAG, "Tutti i tentativi dinamici sono falliti. Applicazione delle credenziali statiche integrate di emergenza.")
        val fallbackServers = if (selectedApp == APP2_NAME) {
            SecurePrefs.get(context).edit().putString("active_call_metered_app", APP2_NAME).apply()
            getStaticIceServersAccount2()
        } else {
            SecurePrefs.get(context).edit().putString("active_call_metered_app", APP1_NAME).apply()
            getStaticIceServersAccount1()
        }

        // Memorizza il fallback statico in cache per evitare ripetuti e inopportuni cicli di richieste fallite ad ogni tentativo di connessione
        cachedIceServers = fallbackServers
        cacheTimestamp = System.currentTimeMillis()

        return fallbackServers
    }

    private fun parseIceServersJson(json: String): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val urlsArray = obj.getJSONArray("urls")
                val urlsList = mutableListOf<String>()
                for (j in 0 until urlsArray.length()) {
                    urlsList.add(urlsArray.getString(j))
                }

                val username = obj.optString("username", "")
                val credential = obj.optString("credential", "")

                val builder = PeerConnection.IceServer.builder(urlsList)
                if (username.isNotEmpty() && credential.isNotEmpty()) {
                    builder.setUsername(username)
                    builder.setPassword(credential)
                }
                servers.add(builder.createIceServer())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore di parsing dei server ICE", e)
        }
        return servers
    }

    /**
     * Configurazione statica integrata per l'Account 2 (500 MB - Global) [1]
     */
    private fun getStaticIceServersAccount2(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        servers.add(PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer())

        val user = "YOUR_TURN_USERNAME"
        val pass = "YOUR_TURN_PASSWORD"

        servers.add(PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80").setUsername(user).setPassword(pass).createIceServer())
        servers.add(PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80?transport=tcp").setUsername(user).setPassword(pass).createIceServer())
        servers.add(PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443").setUsername(user).setPassword(pass).createIceServer())
        servers.add(PeerConnection.IceServer.builder("turns:global.relay.metered.ca:443?transport=tcp").setUsername(user).setPassword(pass).createIceServer())
        return servers
    }

    /**
     * Configurazione statica integrata per l'Account 1 (20 GB - Standard) [1]
     */
    private fun getStaticIceServersAccount1(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        servers.add(PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer())

        val user = "YOUR_TURN_USERNAME"
        val pass = "YOUR_TURN_PASSWORD"

        servers.add(PeerConnection.IceServer.builder("turn:standard.relay.metered.ca:80").setUsername(user).setPassword(pass).createIceServer())
        servers.add(PeerConnection.IceServer.builder("turn:standard.relay.metered.ca:80?transport=tcp").setUsername(user).setPassword(pass).createIceServer())
        servers.add(PeerConnection.IceServer.builder("turn:standard.relay.metered.ca:443").setUsername(user).setPassword(pass).createIceServer())
        servers.add(PeerConnection.IceServer.builder("turns:standard.relay.metered.ca:443?transport=tcp").setUsername(user).setPassword(pass).createIceServer())
        return servers
    }
}