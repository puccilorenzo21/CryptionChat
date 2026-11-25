package com.lollo1344.cryptionchat

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.gson.GsonBuilder
import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ApiClient {
    const val BASE_URL = "https://your-server.example.com/CryptionChat/"
    private const val TAG = "ApiClient"
    // User-Agent uniforme OkHttp (senza fingerprinting di modello/OS e compatibile con .htaccess)
    private const val UNIFORM_USER_AGENT = "okhttp/4.12.0"

    private var _okHttpClient: OkHttpClient? = null
    private var _apiService: ApiService? = null
    private val isRedirectingToAuth = AtomicBoolean(false)

    // Cache in-memory di auth_token e user_id sincronizzata con SecurePrefs per evitare decifrature Tink ad ogni richiesta HTTP
    @Volatile private var cachedAuthToken: String? = null
    @Volatile private var cachedUserId: Int = -1
    @Volatile private var prefsCacheInitialized = false
    private var prefsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // Cache breve (solo esito positivo) della raggiungibilità locale di Orbot per evitare doppi socket su richieste ravvicinate
    private const val TOR_REACHABLE_CACHE_MS = 5_000L
    @Volatile private var lastTorReachableElapsedMs: Long = 0L
    @Volatile private var lastTorReachableEndpoint: String = ""

    fun resetUnauthorizedRedirectFlag() {
        isRedirectingToAuth.set(false)
    }

    private fun ensureAuthCacheRegistered(context: Context) {
        val prefs = SecurePrefs.get(context.applicationContext)
        if (!prefsCacheInitialized) {
            synchronized(this) {
                if (!prefsCacheInitialized) {
                    cachedAuthToken = prefs.getString("auth_token", null)
                    cachedUserId = prefs.getInt("user_id", -1)
                    val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                        when (key) {
                            null -> {
                                cachedAuthToken = sp.getString("auth_token", null)
                                cachedUserId = sp.getInt("user_id", -1)
                            }
                            "auth_token" -> cachedAuthToken = sp.getString("auth_token", null)
                            "user_id" -> cachedUserId = sp.getInt("user_id", -1)
                        }
                    }
                    prefsChangeListener = listener
                    prefs.registerOnSharedPreferenceChangeListener(listener)
                    prefsCacheInitialized = true
                }
            }
        }
    }

    fun init(context: Context) {
        Log.d(TAG, "Inizializzazione ApiClient...")
        val appCtx = context.applicationContext
        ensureAuthCacheRegistered(appCtx)
        lastTorReachableElapsedMs = 0L
        _okHttpClient = buildClient(appCtx)
        _apiService = buildRetrofit(_okHttpClient!!)
    }

    fun reloadConfiguration(context: Context) {
        Log.i(TAG, "Ricaricamento configurazione di rete...")
        init(context)
    }

    val instance: ApiService
        get() {
            if (_apiService == null) {
                throw IllegalStateException("ApiClient non inizializzato. Assicurati di chiamare ApiClient.init(context) nella classe Application.")
            }
            return _apiService!!
        }

    val okHttpClient: OkHttpClient
        get() {
            if (_okHttpClient == null) {
                throw IllegalStateException("ApiClient non inizializzato.")
            }
            return _okHttpClient!!
        }

    private fun applyCertificatePinning(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val host = try { URI(BASE_URL).host } catch (_: Exception) { null }
        if (!host.isNullOrBlank() && !host.contains("example.com")) {
            val pinner = CertificatePinner.Builder()
                // Catena SPKI Let's Encrypt / ISRG verificata per il server di produzione
                .add(host, "sha256/LoMHBotttiDko50Gi13uXW71eIy7LAttI+rYT8wXF4w=") // Let's Encrypt YR1
                .add(host, "sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=") // ISRG Root YR
                .add(host, "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=") // ISRG Root X1
                .build()
            builder.certificatePinner(pinner)
        }
        return builder
    }

    /**
     * Scarica il contenuto testuale di un URL usando [okHttpClient] (Tor SOCKS5, DNS fail-closed,
     * CertificatePinner e User-Agent uniforme).
     */
    fun fetchUrlAsString(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante fetchUrlAsString per $url", e)
            null
        }
    }

    private fun configureNetworkSecurity(
        builder: OkHttpClient.Builder,
        context: Context
    ): OkHttpClient.Builder {
        applyCertificatePinning(builder)
        // FIX CRITICO: Forza HTTP/1.1 ed evita il crash "Required SETTINGS preface not received"
        builder.protocols(listOf(okhttp3.Protocol.HTTP_1_1))

        // Header User-Agent uniforme per evitare fingerprinting di modello/OS
        builder.addInterceptor { chain ->
            val updatedRequest = chain.request().newBuilder()
                .header("User-Agent", UNIFORM_USER_AGENT)
                .build()
            chain.proceed(updatedRequest)
        }

        var directAllowed = false
        try {
            val prefs = SecurePrefs.get(context)
            val useProxy = prefs.getBoolean("use_proxy", false)

            if (useProxy) {
                val host = (prefs.getString("proxy_host", "127.0.0.1") ?: "127.0.0.1")
                    .trim()
                    .ifEmpty { "127.0.0.1" }
                val portStr = prefs.getString("proxy_port", "9050")?.trim()
                val port = portStr?.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw IllegalArgumentException("Porta proxy non valida: $portStr")

                Log.i(TAG, "Abilitazione Proxy SOCKS5h (DNS remoto, fail-closed): $host:$port")
                val proxyAddress = if (host == "127.0.0.1") {
                    InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
                } else {
                    InetSocketAddress(host, port)
                }
                builder.proxy(Proxy(Proxy.Type.SOCKS, proxyAddress))

                // Impedisce qualsiasi fallback su Dns.SYSTEM (niente leak DNS locale)
                builder.dns(Dns { hostname ->
                    throw UnknownHostException("Risoluzione DNS locale bloccata con Tor attivo: $hostname")
                })

                // Fail-closed se Orbot non è raggiungibile localmente (con cache breve solo sugli esiti positivi)
                builder.addInterceptor { chain ->
                    val endpoint = "$host:$port"
                    val nowElapsed = SystemClock.elapsedRealtime()
                    val recentlyVerified = lastTorReachableEndpoint == endpoint &&
                        lastTorReachableElapsedMs > 0L &&
                        (nowElapsed - lastTorReachableElapsedMs) < TOR_REACHABLE_CACHE_MS
                    if (!recentlyVerified) {
                        if (!OrbotHelper.isTorProxyReachable(host, port)) {
                            lastTorReachableElapsedMs = 0L
                            throw IOException("Proxy Tor non raggiungibile su $host:$port (fail-closed).")
                        }
                        lastTorReachableEndpoint = endpoint
                        lastTorReachableElapsedMs = nowElapsed
                    }
                    try {
                        chain.proceed(chain.request())
                    } catch (e: IOException) {
                        lastTorReachableElapsedMs = 0L
                        throw e
                    }
                }
            } else {
                directAllowed = true
                builder.proxy(Proxy.NO_PROXY)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante la configurazione del proxy.", e)
            if (directAllowed) {
                builder.proxy(Proxy.NO_PROXY)
            } else {
                // FAIL-CLOSED: non fare MAI fallback a Proxy.NO_PROXY quando Tor è attivo o incerto
                builder.dns(Dns { hostname ->
                    throw UnknownHostException("DNS locale bloccato (fail-closed): $hostname")
                })
                builder.proxy(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 1)
                    )
                )
                builder.addInterceptor { _ ->
                    throw IOException("Configurazione proxy Tor fallita: traffico bloccato (fail-closed).", e)
                }
            }
        }

        return builder
    }

    private fun buildClient(context: Context): OkHttpClient {
        val builder = configureNetworkSecurity(OkHttpClient.Builder(), context)
            .readTimeout(10, TimeUnit.MINUTES)  // 10 minuti per la ricezione di file grandi
            .writeTimeout(10, TimeUnit.MINUTES) // 10 minuti per l'invio
            .connectTimeout(60, TimeUnit.SECONDS)


        // --- SISTEMA DI AUTO-MIGRAZIONE E AUTH TOKEN ---
        builder.addInterceptor { chain ->
            val request = chain.request()
            ensureAuthCacheRegistered(context)
            var token = cachedAuthToken
            val userId = cachedUserId

            // Se l'utente è loggato localmente, ma non ha un token, effettuiamo
            // la migrazione invisibile bloccando la richiesta in corso per un istante.
            if (token.isNullOrEmpty() && userId != -1) {
                synchronized(this) {
                    val prefs = SecurePrefs.get(context)
                    token = prefs.getString("auth_token", null) // Ricontrolliamo per thread safety
                    cachedAuthToken = token
                    if (token.isNullOrEmpty()) {
                        Log.i(TAG, "Token mancante. Avvio procedura di auto-migrazione silenziosa...")

                        try {
                            val deviceId = DeviceUtils.getDeviceId(context)

                            val migrationBody = FormBody.Builder()
                                .add("action", "auto_migrate_token")
                                .add("user_id", userId.toString())
                                .add("device_id", deviceId)
                                .build()

                            val migrationRequest = Request.Builder()
                                .url(BASE_URL + "api.php")
                                .post(migrationBody)
                                .build()

                            // Usiamo un client pulito per evitare loop infiniti nell'interceptor,
                            // ereditando comunque proxy SOCKS5, anti-DNS-leak, fail-closed, pinning e User-Agent
                            val cleanClient = configureNetworkSecurity(OkHttpClient.Builder(), context)
                                .connectTimeout(15, TimeUnit.SECONDS)
                                .build()

                            cleanClient.newCall(migrationRequest).execute().use { response ->
                                if (response.isSuccessful) {
                                    val responseString = response.body?.string()
                                    if (responseString != null && responseString.contains("\"status\":\"success\"")) {
                                        val jsonObj = JSONObject(responseString)
                                        val dataObj = jsonObj.getJSONObject("data")
                                        token = dataObj.getString("token")
                                        cachedAuthToken = token

                                        // Salviamo il token
                                        prefs.edit().putString("auth_token", token).apply()
                                        Log.i(TAG, "Migrazione al sistema Token completata con successo!")
                                    } else {
                                        Log.e(TAG, "Migrazione rifiutata dal server: $responseString")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Errore di rete durante l'auto-migrazione", e)
                        }
                    }
                }
            }

            // Costruiamo la richiesta reale aggiungendo l'Header
            val requestBuilder = request.newBuilder()
            if (!token.isNullOrEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            val finalResponse = chain.proceed(requestBuilder.build())

            // Se il server risponde con 401 Unauthorized, la sessione è scaduta o il token è invalido.
            // Pulisce solo le credenziali di sessione (preservando chiavi E2EE, preferenze e DB locale)
            // ed evita redirect duplicati se più richieste falliscono contemporaneamente.
            if (finalResponse.code == 401) {
                val sentBearerToken = request.header("Authorization")
                    ?.removePrefix("Bearer ")
                    ?.trim()

                val shouldRedirect = synchronized(this@ApiClient) {
                    val prefs = SecurePrefs.get(context)
                    val currentUserId = prefs.getInt("user_id", -1)
                    val currentToken = prefs.getString("auth_token", null)
                    val tokenMatches = sentBearerToken.isNullOrEmpty() ||
                        currentToken.isNullOrEmpty() ||
                        sentBearerToken == currentToken
                    if (currentUserId != -1 && tokenMatches && isRedirectingToAuth.compareAndSet(false, true)) {
                        SessionLifecycleHelper.clearSessionCredentialsOnly(prefs, currentUserId)
                        cachedAuthToken = null
                        cachedUserId = -1
                        true
                    } else {
                        false
                    }
                }

                if (shouldRedirect) {
                    Handler(Looper.getMainLooper()).post {
                        Log.e(TAG, "Ricevuto 401 Unauthorized. Logout sessione senza formattazione locale.")
                        try { ServiceScheduler.stopServiceAndWatchdog(context) } catch (_: Exception) {}

                        val intent = Intent(context, AuthActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        context.startActivity(intent)
                        Handler(Looper.getMainLooper()).postDelayed({
                            isRedirectingToAuth.set(false)
                        }, 2000L)
                    }
                }
            }
            // ---------------------------------

            finalResponse
        }
        // --- FINE SISTEMA TOKEN ---

        return builder.build()
    }

    private fun buildRetrofit(client: OkHttpClient): ApiService {
        val gson = GsonBuilder()
            .setLenient()
            .create()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
    }
}