package com.lollo1344.cryptionchat

import android.content.Context
import android.graphics.Typeface
import android.os.Build
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
import com.google.android.material.imageview.ShapeableImageView
import org.json.JSONObject
import com.google.android.material.R as MaterialR

sealed class GroupListItem {
    data class Header(val title: String) : GroupListItem()
    data class GroupData(val group: Group, val isPinned: Boolean) : GroupListItem()
}

class GroupAdapter(
    private var items: List<GroupListItem> = emptyList(),
    private var currentUsername: String,
    private val onGroupClicked: (Group) -> Unit,
    private val onGroupLongClicked: (Group) -> Unit,
    private val onGroupIconClicked: (Group) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_GROUP = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is GroupListItem.Header -> TYPE_HEADER
            is GroupListItem.GroupData -> TYPE_GROUP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_group_header, parent, false))
            else -> GroupViewHolder(inflater.inflate(R.layout.item_group, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is GroupListItem.Header -> (holder as HeaderViewHolder).bind(item.title)
            is GroupListItem.GroupData -> {
                val groupHolder = holder as GroupViewHolder
                groupHolder.bind(item.group, currentUsername, item.isPinned, onGroupIconClicked, onGroupLongClicked)
                groupHolder.itemView.setOnClickListener { onGroupClicked(item.group) }
                groupHolder.itemView.setOnLongClickListener {
                    performVibration(it.context)
                    onGroupLongClicked(item.group)
                    true
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<GroupListItem>, newUsername: String) {
        this.items = newItems
        this.currentUsername = newUsername
        notifyDataSetChanged()
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

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val headerText: TextView = itemView.findViewById(R.id.text_header)
        fun bind(title: String) { headerText.text = title }
    }

    class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val groupNameTextView: TextView = itemView.findViewById(R.id.textViewGroupName)
        private val groupCodeTextView: TextView = itemView.findViewById(R.id.textViewGroupCode)
        private val timeTextView: TextView = itemView.findViewById(R.id.textViewTime)
        private val unreadCountTextView: TextView = itemView.findViewById(R.id.textViewUnreadCount)
        private val groupIconImageView: ShapeableImageView = itemView.findViewById(R.id.group_icon)
        private val onlineDot: View = itemView.findViewById(R.id.online_dot)
        private val pinIcon: ImageView = itemView.findViewById(R.id.pin_icon)

        fun bind(group: Group, currentUsername: String, isPinned: Boolean, onGroupIconClicked: (Group) -> Unit, onGroupLongClicked: (Group) -> Unit) {
            val displayName = if (group.isPrivateChat) {
                val parts = group.groupName.split("&").map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    parts.firstOrNull { !it.equals(currentUsername.trim(), ignoreCase = true) } ?: parts.first()
                } else {
                    group.groupName.replace(currentUsername, "", ignoreCase = true).replace("&", "").trim()
                }
            } else group.groupName
            groupNameTextView.text = displayName

            val prefs = SecurePrefs.get(itemView.context)
            val draftText = prefs.getString("draft_group_${group.id}", null)
            val typedValue = TypedValue()

            // --- CONFIGURAZIONE DINAMICA GLASSMORPHISM PERSONALIZZATA ---
            val cardView = itemView as? com.google.android.material.card.MaterialCardView
            if (cardView != null) {
                val cardTransparency = prefs.getInt("home_card_transparency", 75)
                val reflectionPercent = prefs.getInt("chat_card_reflection", 100)
                val strokeWidthDp = prefs.getInt("chat_card_stroke_width", 0)

                cardView.alpha = 1.0f

                val cardAlpha = ((100 - cardTransparency) * 255 / 100).coerceIn(0, 255)
                val reflectionAlpha = (reflectionPercent * 255 / 100).coerceIn(0, 255)

                itemView.context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true)
                val surfaceColor = typedValue.data
                val glassColor = androidx.core.graphics.ColorUtils.setAlphaComponent(surfaceColor, cardAlpha)
                cardView.setCardBackgroundColor(glassColor)

                if (cardTransparency > 0) {
                    cardView.cardElevation = 0f
                } else {
                    cardView.cardElevation = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        2f,
                        itemView.context.resources.displayMetrics
                    )
                }

                itemView.context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, typedValue, true)
                val strokeColor = androidx.core.graphics.ColorUtils.setAlphaComponent(typedValue.data, reflectionAlpha)
                cardView.strokeColor = strokeColor

                val strokeWidthPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    strokeWidthDp.toFloat(),
                    itemView.context.resources.displayMetrics
                ).toInt()
                cardView.strokeWidth = strokeWidthPx
            }

            var displayUnreadCount = group.unreadCount

            if (!group.lastMessage.isNullOrBlank()) {
                val isZk = prefs.getBoolean("is_zk_${group.id}", false)
                val key = if (isZk) prefs.getString("zk_key_${group.id}", null) else prefs.getString("key_${group.id}", null)
                val iv = if (isZk) prefs.getString("zk_iv_${group.id}", null) else prefs.getString("iv_${group.id}", null)

                val isE2eeV2Payload = group.lastMessage.startsWith(SenderKeyHelper.SK_PREFIX) ||
                        group.lastMessage.startsWith(DoubleRatchetHelper.DR_PREFIX)

                var decrypted = if ((key != null && iv != null) || isE2eeV2Payload) {
                    EncryptionHelper.decrypt(group.lastMessage, key ?: "", iv ?: "")
                } else null

                if (decrypted.isNullOrBlank() && group.lastMessage.startsWith(DoubleRatchetHelper.DR_PREFIX) && group.isPrivateChat) {
                    val peerId = listOf(
                        "private_peer_uid_${group.id}",
                        "peer_user_id_${group.id}",
                        "other_user_id_${group.id}",
                        "private_peer_${group.id}"
                    ).firstNotNullOfOrNull { prefKey ->
                        prefs.getInt(prefKey, -1).takeIf { it > 0 }
                    }
                    if (peerId != null) {
                        decrypted = DoubleRatchetHelper.decryptMessage(
                            DoubleRatchetHelper.pairwiseSessionKey(peerId),
                            group.lastMessage
                        )
                    }
                }

                if (decrypted.isNullOrBlank() && isZk) {
                    decrypted = EncryptionHelper.decrypt(group.lastMessage, prefs.getString("key_${group.id}", "") ?: "", prefs.getString("iv_${group.id}", "") ?: "")
                }

                // Verifica se l'ultimo messaggio è una notifica di sistema ZK
                val isSystemStatus = decrypted != null && (
                        decrypted.startsWith("[ZK_ECDH]:") ||
                        decrypted.startsWith("[ZK_EXCHANGE]:") ||
                                decrypted.contains("ha attivato la Chat Ultra Sicura") ||
                                decrypted.contains("Le chiavi della Chat Ultra Sicura sono state rinegoziate") ||
                                decrypted.contains("ha rigenerato la chiave") ||
                                decrypted.contains("ha disattivato la Chat Ultra Sicura")
                        )

                // Sottrae il messaggio di stato dal conteggio non letti locale
                if (isSystemStatus) {
                    if (displayUnreadCount == 1) {
                        displayUnreadCount = 0
                    } else if (displayUnreadCount > 1) {
                        displayUnreadCount -= 1
                    }
                }

                if (!draftText.isNullOrEmpty()) {
                    groupCodeTextView.text = "✏️ Bozza: $draftText"
                    itemView.context.theme.resolveAttribute(android.R.attr.colorError, typedValue, true)
                    groupCodeTextView.setTextColor(typedValue.data)
                    groupCodeTextView.setTypeface(null, Typeface.ITALIC)
                } else {
                    itemView.context.theme.resolveAttribute(MaterialR.attr.colorOnSurfaceVariant, typedValue, true)
                    groupCodeTextView.setTextColor(typedValue.data)
                    groupCodeTextView.setTypeface(null, Typeface.NORMAL)

                    groupCodeTextView.text = if (decrypted != null) {
                        formatLastMessagePreview(decrypted)
                    } else {
                        "🔒 Messaggio criptato"
                    }
                }
            } else {
                if (!draftText.isNullOrEmpty()) {
                    groupCodeTextView.text = "✏️ Bozza: $draftText"
                    itemView.context.theme.resolveAttribute(android.R.attr.colorError, typedValue, true)
                    groupCodeTextView.setTextColor(typedValue.data)
                    groupCodeTextView.setTypeface(null, Typeface.ITALIC)
                } else {
                    groupCodeTextView.text = if (!group.groupCode.isNullOrEmpty() && !group.isPrivateChat) "Codice: ${group.groupCode}" else "Nessun messaggio."
                }
            }

            timeTextView.text = group.lastMessageTimestamp?.let { TimeUtils.formatReadTimestamp(it) } ?: ""
            unreadCountTextView.visibility = if (displayUnreadCount > 0) View.VISIBLE else View.GONE
            unreadCountTextView.text = displayUnreadCount.toString()

            val placeholder = if (group.isPrivateChat) R.drawable.ic_person else R.drawable.ic_group
            val url = if (group.isPrivateChat) group.profilePictureUrl else group.groupPictureUrl

            if (!url.isNullOrBlank()) {
                Glide.with(itemView.context).load(ApiClient.BASE_URL + url).placeholder(placeholder).error(placeholder).into(groupIconImageView)
            } else {
                groupIconImageView.setImageResource(placeholder)
            }

            pinIcon.visibility = if (isPinned) View.VISIBLE else View.GONE
            onlineDot.visibility = if (group.isOnline == true) View.VISIBLE else View.GONE
            groupIconImageView.setOnClickListener { onGroupIconClicked(group) }
        }

        private fun formatLastMessagePreview(decrypted: String): String {
            val trimmed = decrypted.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}") &&
                (trimmed.contains("\"mime\"") || trimmed.contains("\"msg_type\"") || trimmed.contains("\"file_key\""))
            ) {
                try {
                    val json = JSONObject(trimmed)
                    val msgType = json.optString("msg_type", "")
                    val mime = json.optString("mime", "")
                    val name = json.optString("name", "")
                    val caption = json.optString("caption", "")

                    val prefix = when {
                        msgType == "image" || mime.startsWith("image/") -> "📷 Foto"
                        msgType == "video" || mime.startsWith("video/") -> "🎥 Video"
                        msgType == "audio" || mime.startsWith("audio/") -> "🎵 Messaggio vocale"
                        else -> "📎"
                    }

                    return when {
                        prefix == "📎" && name.isNotEmpty() && caption.isNotEmpty() -> "📎 $name: $caption"
                        prefix == "📎" && name.isNotEmpty() -> "📎 $name"
                        prefix == "📎" && caption.isNotEmpty() -> "📎 File: $caption"
                        prefix == "📎" -> "📎 File"
                        caption.isNotEmpty() -> "$prefix: $caption"
                        name.isNotEmpty() && msgType != "audio" && !mime.startsWith("audio/") -> "$prefix: $name"
                        else -> prefix
                    }
                } catch (e: Exception) {
                }
            }

            return when {
                trimmed.contains("[REQ_LOC]") -> "📍 Richiesta posizione"
                trimmed.contains("https://maps.google.com/?q=") -> "📍 Posizione condivisa"
                trimmed.startsWith("[ZK_ECDH]:") || trimmed.startsWith("[ZK_EXCHANGE]:") -> "🔑 Sincronizzazione chiavi Ultra Sicura"
                trimmed.contains("La Chat Ultra Sicura è stata disattivata") -> "🔓 Chat Ultra Sicura disattivata"
                else -> trimmed
            }
        }
    }
}