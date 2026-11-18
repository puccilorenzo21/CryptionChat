package com.lollo1344.cryptionchat

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import java.io.File

sealed class StickerListItem {
    data class Header(val title: String) : StickerListItem()
    data class StickerItem(val file: File, val isRecent: Boolean = false) : StickerListItem()
}

class StickerAdapter(
    private var items: List<StickerListItem>,
    private val onStickerClicked: (File) -> Unit,
    private val onStickerLongClicked: ((File) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_STICKER = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is StickerListItem.Header -> TYPE_HEADER
            is StickerListItem.StickerItem -> TYPE_STICKER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_group_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_sticker, parent, false)
            StickerViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is StickerListItem.Header -> (holder as HeaderViewHolder).bind(item.title)
            is StickerListItem.StickerItem -> (holder as StickerViewHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<StickerListItem>) {
        this.items = newItems.toMutableList()
        notifyDataSetChanged()
    }

    fun removeItemAt(position: Int) {
        if (position in 0 until items.size) {
            val mutableList = items as? MutableList<StickerListItem>
            if (mutableList != null) {
                mutableList.removeAt(position)
                notifyItemRemoved(position)
            }
        }
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.text_header)
        fun bind(title: String) {
            titleView.text = title
        }
    }

    inner class StickerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.img_sticker)

        fun bind(item: StickerListItem.StickerItem) {
            val file = item.file

            itemView.visibility = View.VISIBLE
            val defaultHeight = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 90f, itemView.context.resources.displayMetrics
            ).toInt()
            val lp = itemView.layoutParams
            lp.height = defaultHeight
            itemView.layoutParams = lp

            Glide.with(itemView.context)
                .asDrawable()
                .load(file)
                .override(180, 180)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (file.exists()) {
                            file.delete()
                        }
                        mainHandler.post {
                            val currentPos = bindingAdapterPosition
                            if (currentPos != RecyclerView.NO_POSITION && currentPos < items.size) {
                                removeItemAt(currentPos)
                            }
                        }
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (resource is Animatable) {
                            resource.start()
                        }
                        return false
                    }
                })
                .into(imageView)

            itemView.setOnClickListener {
                onStickerClicked(file)
            }

            itemView.setOnLongClickListener {
                performVibration(itemView.context)
                onStickerLongClicked?.invoke(file)
                true
            }
        }

        private fun performVibration(context: Context) {
            try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (vibrator?.hasVibrator() == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    } else {
                        @Suppress("DEPRECATION") vibrator.vibrate(50)
                    }
                }
            } catch (e: Exception) {}
        }
    }
}