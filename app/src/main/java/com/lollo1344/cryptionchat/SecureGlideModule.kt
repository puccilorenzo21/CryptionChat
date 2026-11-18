package com.lollo1344.cryptionchat

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import okhttp3.Call
import java.io.InputStream

/**
 * Configura Glide per instradare tutte le richieste di rete attraverso [ApiClient.okHttpClient],
 * garantendo il rispetto del proxy Tor SOCKS5, blocco DNS locale, CertificatePinner e User-Agent uniforme.
 */
@GlideModule
class SecureGlideModule : AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val dynamicCallFactory = Call.Factory { request ->
            ApiClient.okHttpClient.newCall(request)
        }
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(dynamicCallFactory)
        )
    }

    override fun isManifestParsingEnabled(): Boolean = false
}
