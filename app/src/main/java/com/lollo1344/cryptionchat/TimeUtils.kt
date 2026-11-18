package com.lollo1344.cryptionchat

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object TimeUtils {

    /**
     * Formatta lo stato di un utente basandosi su isOnline e un timestamp UTC.
     * @param isOnline Se l'utente è attualmente online.
     * @param utcTimestamp L'ultimo timestamp di attività (es. "2025-10-26 18:30:00").
     * @return "Online", "Ultimo accesso...", o null.
     */
    fun formatUserStatus(isOnline: Boolean, utcTimestamp: String?): String? {
        if (isOnline) {
            return "Online"
        }

        if (utcTimestamp.isNullOrBlank()) {
            return null // O potresti restituire "Offline" se preferisci
        }

        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val lastSeenDate = sdf.parse(utcTimestamp) ?: return null

            val today = Calendar.getInstance()
            val lastSeenCal = Calendar.getInstance().apply { time = lastSeenDate }

            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

            // Se l'ultimo accesso è stato oggi
            if (today.get(Calendar.YEAR) == lastSeenCal.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == lastSeenCal.get(Calendar.DAY_OF_YEAR)) {
                return "oggi alle ${timeFormat.format(lastSeenDate)}"
            }

            // Se l'ultimo accesso è stato ieri
            today.add(Calendar.DAY_OF_YEAR, -1)
            if (today.get(Calendar.YEAR) == lastSeenCal.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == lastSeenCal.get(Calendar.DAY_OF_YEAR)) {
                return "ieri alle ${timeFormat.format(lastSeenDate)}"
            }

            // Altrimenti, mostra la data completa
            val dateFormat = SimpleDateFormat("dd/MM/yy 'alle' HH:mm", Locale.getDefault())
            return "Ultimo il ${dateFormat.format(lastSeenDate)}"

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // --- NUOVA FUNZIONE ---
    /**
     * Formatta un timestamp UTC per la visualizzazione delle conferme di lettura.
     * @param utcTimestamp Il timestamp di lettura (es. "2025-10-26 18:30:00").
     * @return Una stringa formattata come "Oggi alle 18:30", "Ieri alle 10:15", ecc.
     */
    fun formatReadTimestamp(utcTimestamp: String?): String {
        if (utcTimestamp.isNullOrBlank()) {
            return "N/D"
        }

        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val readDate = sdf.parse(utcTimestamp) ?: return "N/D"

            val today = Calendar.getInstance()
            val readCal = Calendar.getInstance().apply { time = readDate }

            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

            if (today.get(Calendar.YEAR) == readCal.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == readCal.get(Calendar.DAY_OF_YEAR)) {
                return "Oggi alle ${timeFormat.format(readDate)}"
            }

            today.add(Calendar.DAY_OF_YEAR, -1)
            if (today.get(Calendar.YEAR) == readCal.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == readCal.get(Calendar.DAY_OF_YEAR)) {
                return "Ieri alle ${timeFormat.format(readDate)}"
            }

            val dateFormat = SimpleDateFormat("dd/MM/yy 'alle' HH:mm", Locale.getDefault())
            return dateFormat.format(readDate)

        } catch (e: Exception) {
            e.printStackTrace()
            return "N/D"
        }
    }
    // --- FINE NUOVA FUNZIONE ---
}