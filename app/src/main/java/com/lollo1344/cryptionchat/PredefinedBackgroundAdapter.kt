package com.lollo1344.cryptionchat

import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.imageview.ShapeableImageView
import java.util.Locale

class PredefinedBackgroundAdapter(
    private val imageUrls: List<String>,
    private val onImageSelected: (String) -> Unit
) : RecyclerView.Adapter<PredefinedBackgroundAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_predefined_background, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val url = imageUrls[position]
        holder.bind(url)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cleanup() // Ferma il video quando la cella esce dallo schermo
    }

    override fun getItemCount(): Int = imageUrls.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ShapeableImageView = itemView.findViewById(R.id.bg_image)
        private val videoView: VideoView = itemView.findViewById(R.id.bg_video)
        private val liveBadge: TextView = itemView.findViewById(R.id.live_badge)
        private val liveGradient: View = itemView.findViewById(R.id.live_gradient)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
        private val clickOverlay: View = itemView.findViewById(R.id.click_overlay)

        fun bind(url: String) {
            progressBar.visibility = View.VISIBLE

            val isVideo = isVideoUrl(url)
            val isGif = isGifUrl(url)
            val isLive = isVideo || isGif

            // Listener per il click (su tutta la cella)
            clickOverlay.setOnClickListener {
                onImageSelected(url)
            }

            // Mostra il badge LIVE sia per i video che per le GIF
            if (isLive) {
                liveBadge.visibility = View.VISIBLE
                liveGradient.visibility = View.VISIBLE
            } else {
                liveBadge.visibility = View.GONE
                liveGradient.visibility = View.GONE
            }

            if (isVideo) {
                setupVideo(url)
            } else {
                // Glide gestisce automaticamente l'animazione delle GIF!
                setupImage(url)
            }
        }

        private fun setupVideo(url: String) {
            imageView.visibility = View.GONE
            videoView.visibility = View.VISIBLE

            try {
                val uri = Uri.parse(url)
                videoView.setVideoURI(uri)

                videoView.setOnPreparedListener { mp ->
                    progressBar.visibility = View.GONE
                    mp.isLooping = true
                    mp.setVolume(0f, 0f) // Muto assoluto

                    // Adatta il video per riempire la cella (Center Crop simulato)
                    val videoRatio = mp.videoWidth / mp.videoHeight.toFloat()
                    val screenRatio = videoView.width / videoView.height.toFloat()
                    val scaleX = videoRatio / screenRatio
                    if (scaleX >= 1f) {
                        videoView.scaleX = scaleX
                    } else {
                        videoView.scaleY = 1f / scaleX
                    }

                    videoView.start()
                }

                videoView.setOnErrorListener { _, _, _ ->
                    progressBar.visibility = View.GONE
                    false
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
            }
        }

        private fun setupImage(url: String) {
            imageView.visibility = View.VISIBLE
            videoView.visibility = View.GONE
            videoView.stopPlayback() // Sicurezza

            Glide.with(itemView.context)
                .load(url)
                .centerCrop()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        progressBar.visibility = View.GONE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        progressBar.visibility = View.GONE
                        return false
                    }
                })
                .into(imageView)
        }

        fun cleanup() {
            videoView.stopPlayback()
        }

        private fun isVideoUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            return lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") || lower.contains(".mov")
        }

        private fun isGifUrl(url: String): Boolean {
            return url.lowercase(Locale.ROOT).contains(".gif")
        }
    }
}