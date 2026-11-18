package com.lollo1344.cryptionchat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView

class ReactionDetailAdapter(
    private val reactions: List<Reaction>
) : RecyclerView.Adapter<ReactionDetailAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reaction_detail, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(reactions[position])
    }

    override fun getItemCount(): Int = reactions.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val profileImage: ShapeableImageView = itemView.findViewById(R.id.user_profile_image)
        private val usernameText: TextView = itemView.findViewById(R.id.user_name_text)
        private val emojiText: TextView = itemView.findViewById(R.id.reaction_emoji_text)

        fun bind(reaction: Reaction) {
            usernameText.text = reaction.username
            emojiText.text = reaction.reactionEmoji

            // MODIFICA: Ora usiamo Glide per caricare l'immagine reale
            if (!reaction.profilePictureUrl.isNullOrBlank()) {
                Glide.with(itemView.context)
                    .load(ApiClient.BASE_URL + reaction.profilePictureUrl)
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(profileImage)
            } else {
                profileImage.setImageResource(R.drawable.ic_person)
            }
        }
    }
}