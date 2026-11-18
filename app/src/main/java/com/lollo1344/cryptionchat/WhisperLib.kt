package com.lollo1344.cryptionchat

import android.util.Log

object WhisperLib {

    private const val TAG = "WhisperLib"
    private var isLibraryLoaded = false

    init {
        try {
            System.loadLibrary("cryptionchat_native")
            isLibraryLoaded = true
            Log.i(TAG, "Libreria nativa caricata con successo.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Impossibile caricare cryptionchat_native: ${e.message}")
            isLibraryLoaded = false
        }
    }

    fun isAvailable(): Boolean = isLibraryLoaded

    /**
     * Inizializza il contesto Whisper caricando il modello dal percorso specificato.
     * @return Un puntatore (Long) al contesto nativo, o 0 se fallisce.
     */
    external fun init(modelPath: String): Long

    /**
     * Libera la memoria del contesto.
     */
    external fun free(contextPtr: Long)

    /**
     * Esegue la trascrizione sull'array di float (PCM 16kHz mono normalizzato).
     */
    external fun transcribe(contextPtr: Long, samples: FloatArray): String
}