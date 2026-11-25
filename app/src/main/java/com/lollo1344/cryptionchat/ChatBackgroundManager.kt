package com.lollo1344.cryptionchat

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.VideoView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object ChatBackgroundManager {

    private const val PREF_BG_TYPE_HOME = "bg_type_home"
    private const val PREF_BG_PATH_HOME = "bg_path_home"
    private const val PREF_BG_TYPE_CHAT = "bg_type_chat" // Globale
    private const val PREF_BG_PATH_CHAT = "bg_path_chat" // Globale

    // Chiavi separate per l'opacità della Home e della Chat
    private const val PREF_BG_OPACITY_HOME = "home_bg_opacity"
    private const val PREF_BG_OPACITY_CHAT = "chat_bg_opacity"

    const val TYPE_NONE = "none"
    const val TYPE_CUSTOM = "custom"
    const val TYPE_URL = "url"

    const val TARGET_HOME = "home"
    const val TARGET_CHAT = "chat"
    const val TARGET_BOTH = "both"

    private fun getChatTypeKey(groupId: Int?) = if (groupId != null) "bg_type_chat_$groupId" else PREF_BG_TYPE_CHAT
    private fun getChatPathKey(groupId: Int?) = if (groupId != null) "bg_path_chat_$groupId" else PREF_BG_PATH_CHAT

    private fun getOpacityKey(target: String, groupId: Int? = null): String {
        return if (target == TARGET_HOME) {
            PREF_BG_OPACITY_HOME
        } else if (groupId != null) {
            "chat_bg_opacity_$groupId"
        } else {
            PREF_BG_OPACITY_CHAT
        }
    }

    /**
     * Recupera l'opacità specifica per la destinazione (Home o Chat/Gruppo).
     */
    fun getOpacity(context: Context, target: String = TARGET_CHAT, groupId: Int? = null): Float {
        val prefs = SecurePrefs.get(context)
        val key = getOpacityKey(target, groupId)

        if (prefs.contains(key)) {
            return prefs.getFloat(key, 0.6f)
        }

        // Fallback per chat specifica -> opacità chat globale -> default 0.6f
        if (target == TARGET_CHAT && groupId != null) {
            return prefs.getFloat(PREF_BG_OPACITY_CHAT, 0.6f)
        }

        return prefs.getFloat(PREF_BG_OPACITY_CHAT, 0.6f)
    }

    /**
     * Salva l'opacità in modo indipendente per Home o Chat/Gruppo.
     */
    fun setOpacity(context: Context, opacity: Float, target: String = TARGET_CHAT, groupId: Int? = null) {
        val editor = SecurePrefs.get(context).edit()
        if (target == TARGET_BOTH) {
            editor.putFloat(PREF_BG_OPACITY_HOME, opacity)
            editor.putFloat(PREF_BG_OPACITY_CHAT, opacity)
            if (groupId != null) {
                editor.putFloat("chat_bg_opacity_$groupId", opacity)
            }
        } else {
            editor.putFloat(getOpacityKey(target, groupId), opacity)
        }
        editor.apply()
    }

    fun applyBackground(
        activity: Activity,
        imageView: ImageView,
        videoView: VideoView,
        overlayView: View,
        target: String,
        groupId: Int? = null
    ) {
        val prefs = SecurePrefs.get(activity)

        val typeKey = if (target == TARGET_HOME) PREF_BG_TYPE_HOME else getChatTypeKey(groupId)
        val pathKey = if (target == TARGET_HOME) PREF_BG_PATH_HOME else getChatPathKey(groupId)

        var type = prefs.getString(typeKey, TYPE_NONE) ?: TYPE_NONE
        var path = prefs.getString(pathKey, "") ?: ""

        // FALLBACK: Se è una chat specifica ma non ha sfondo personalizzato, usa quello GLOBALE
        if (target == TARGET_CHAT && groupId != null && (type == TYPE_NONE || path.isEmpty())) {
            type = prefs.getString(PREF_BG_TYPE_CHAT, TYPE_NONE) ?: TYPE_NONE
            path = prefs.getString(PREF_BG_PATH_CHAT, "") ?: ""
        }

        // Recupera l'opacità specifica per questa schermata
        val opacityPref = getOpacity(activity, target, groupId)
        val overlayAlpha = 1.0f - opacityPref

        overlayView.alpha = overlayAlpha
        overlayView.visibility = View.VISIBLE

        if (type == TYPE_NONE || path.isEmpty()) {
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            videoView.stopPlayback()
            videoView.visibility = View.GONE
            overlayView.visibility = View.GONE
            return
        }

        val isVideo = isVideoFile(path)

        if (isVideo) {
            setupVideoBackground(activity, videoView, imageView, type, path)
        } else {
            setupImageBackground(activity, imageView, videoView, type, path)
        }
    }

    private fun setupVideoBackground(activity: Activity, videoView: VideoView, imageView: ImageView, type: String?, path: String?) {
        imageView.visibility = View.GONE
        videoView.visibility = View.VISIBLE

        try {
            if (activity.isFinishing || activity.isDestroyed) return

            if (type == TYPE_CUSTOM) {
                val file = File(activity.filesDir, path ?: "")
                if (file.exists()) {
                    videoView.setVideoURI(Uri.fromFile(file))
                } else {
                    videoView.visibility = View.GONE
                    return
                }
            } else {
                videoView.setVideoPath(path)
            }

            videoView.setOnPreparedListener { mp ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    mp.isLooping = true
                    mp.setVolume(0f, 0f)

                    val videoRatio = mp.videoWidth / mp.videoHeight.toFloat()
                    val screenRatio = videoView.width / videoView.height.toFloat()
                    val scaleX = videoRatio / screenRatio
                    if (scaleX >= 1f) videoView.scaleX = scaleX else videoView.scaleY = 1f / scaleX

                    mp.start()
                } else {
                    mp.release()
                }
            }

            videoView.setOnErrorListener { _, what, extra ->
                Log.e("ChatBG", "Errore VideoView: $what, $extra")
                videoView.visibility = View.GONE
                true
            }

        } catch (e: Exception) {
            Log.e("ChatBG", "Eccezione setup video", e)
            videoView.visibility = View.GONE
        }
    }

    private fun setupImageBackground(activity: Activity, imageView: ImageView, videoView: VideoView, type: String?, path: String?) {
        videoView.stopPlayback()
        videoView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        imageView.scaleX = 1f
        imageView.scaleY = 1f

        try {
            if (type == TYPE_CUSTOM) {
                val file = File(activity.filesDir, path ?: "")
                if (file.exists()) {
                    Glide.with(activity).load(file).transition(DrawableTransitionOptions.withCrossFade()).centerCrop().into(imageView)
                }
            } else if (type == TYPE_URL) {
                Glide.with(activity).load(path).transition(DrawableTransitionOptions.withCrossFade()).centerCrop().into(imageView)
            }
        } catch (e: Exception) { Log.e("ChatBG", "Errore caricamento immagine", e) }
    }

    private fun isVideoFile(path: String?): Boolean {
        if (path == null) return false
        val lowerPath = path.lowercase(Locale.ROOT)
        return lowerPath.endsWith(".mp4") || lowerPath.endsWith(".mkv") || lowerPath.endsWith(".webm")
    }

    fun removeBackground(context: Context, target: String, groupId: Int? = null) {
        val prefs = SecurePrefs.get(context)
        val editor = prefs.edit()

        fun deleteIfCustom(typeKey: String, pathKey: String, otherPathKey: String) {
            val type = prefs.getString(typeKey, TYPE_NONE) ?: TYPE_NONE
            val path = prefs.getString(pathKey, "") ?: ""
            val otherPath = prefs.getString(otherPathKey, "") ?: ""

            if (type == TYPE_CUSTOM && path.isNotEmpty() && path != otherPath) {
                val file = File(context.filesDir, path)
                if (file.exists()) file.delete()
            }
        }

        if (target == TARGET_HOME || target == TARGET_BOTH) {
            deleteIfCustom(PREF_BG_TYPE_HOME, PREF_BG_PATH_HOME, PREF_BG_PATH_CHAT)
            editor.putString(PREF_BG_TYPE_HOME, TYPE_NONE).putString(PREF_BG_PATH_HOME, "")
        }

        if (target == TARGET_CHAT || target == TARGET_BOTH) {
            val tKey = getChatTypeKey(groupId)
            val pKey = getChatPathKey(groupId)
            if (groupId == null) {
                deleteIfCustom(tKey, pKey, PREF_BG_PATH_HOME)
            }
            editor.putString(tKey, TYPE_NONE).putString(pKey, "")
        }

        editor.apply()
    }

    fun setPredefinedBackground(context: Context, url: String, target: String, groupId: Int? = null) {
        val editor = SecurePrefs.get(context).edit()

        if (target == TARGET_HOME || target == TARGET_BOTH) {
            removeBackground(context, TARGET_HOME)
            editor.putString(PREF_BG_TYPE_HOME, TYPE_URL).putString(PREF_BG_PATH_HOME, url)
        }
        if (target == TARGET_CHAT || target == TARGET_BOTH) {
            removeBackground(context, TARGET_CHAT, groupId)
            editor.putString(getChatTypeKey(groupId), TYPE_URL).putString(getChatPathKey(groupId), url)
        }
        editor.apply()
    }

    fun setCustomBackground(context: Context, uri: Uri, target: String, groupId: Int? = null): Boolean {
        try {
            val mime = context.contentResolver.getType(uri)
            val ext = when {
                mime?.startsWith("video/") == true -> ".mp4"
                mime == "image/gif" -> ".gif"
                else -> ".jpg"
            }

            val fileName = "custom_bg_${System.currentTimeMillis()}$ext"
            val file = File(context.filesDir, fileName)
            val copied = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                true
            } ?: false
            if (!copied) return false

            val editor = SecurePrefs.get(context).edit()

            if (target == TARGET_HOME || target == TARGET_BOTH) {
                removeBackground(context, TARGET_HOME)
                editor.putString(PREF_BG_TYPE_HOME, TYPE_CUSTOM).putString(PREF_BG_PATH_HOME, fileName)
            }
            if (target == TARGET_CHAT || target == TARGET_BOTH) {
                removeBackground(context, TARGET_CHAT, groupId)
                editor.putString(getChatTypeKey(groupId), TYPE_CUSTOM).putString(getChatPathKey(groupId), fileName)
            }

            editor.apply()
            return true
        } catch (e: Exception) {
            Log.e("ChatBG", "Errore salvataggio sfondo custom", e)
            return false
        }
    }

    fun setCustomBackgroundFromBitmap(context: Context, bitmap: Bitmap, target: String, groupId: Int? = null): Boolean {
        try {
            val fileName = "custom_bg_${System.currentTimeMillis()}.jpg"
            val file = File(context.filesDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            val editor = SecurePrefs.get(context).edit()

            if (target == TARGET_HOME || target == TARGET_BOTH) {
                removeBackground(context, TARGET_HOME)
                editor.putString(PREF_BG_TYPE_HOME, TYPE_CUSTOM).putString(PREF_BG_PATH_HOME, fileName)
            }
            if (target == TARGET_CHAT || target == TARGET_BOTH) {
                removeBackground(context, TARGET_CHAT, groupId)
                editor.putString(getChatTypeKey(groupId), TYPE_CUSTOM).putString(getChatPathKey(groupId), fileName)
            }

            editor.apply()
            return true
        } catch (e: Exception) {
            Log.e("ChatBG", "Errore salvataggio sfondo custom da bitmap", e)
            return false
        }
    }
}