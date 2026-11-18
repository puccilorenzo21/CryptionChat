package com.lollo1344.cryptionchat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView

class GroupMemberAdapter(
    private val members: List<GroupMember>,
    private val onMemberLongClicked: (GroupMember) -> Unit
) : RecyclerView.Adapter<GroupMemberAdapter.MemberViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group_member, parent, false)
        return MemberViewHolder(view)
    }

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        val member = members[position]
        holder.bind(member)
        holder.itemView.setOnLongClickListener {
            onMemberLongClicked(member)
            true
        }
    }

    override fun getItemCount(): Int = members.size

    class MemberViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val usernameTextView: TextView = itemView.findViewById(R.id.member_username)
        private val memberIcon: ShapeableImageView = itemView.findViewById(R.id.member_icon)
        private val adminIcon: ImageView = itemView.findViewById(R.id.admin_icon)
        private val statusTextView: TextView = itemView.findViewById(R.id.member_status)

        fun bind(member: GroupMember) {
            usernameTextView.text = member.username
            adminIcon.visibility = if (member.isAdmin) View.VISIBLE else View.GONE

            if (!member.profilePictureUrl.isNullOrBlank()) {
                Glide.with(itemView.context)
                    .load(ApiClient.BASE_URL + member.profilePictureUrl)
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(memberIcon)
            } else {
                memberIcon.setImageResource(R.drawable.ic_person)
            }

            val statusText = TimeUtils.formatUserStatus(member.isOnline, member.lastSeen)
            if (statusText.isNullOrEmpty()) {
                statusTextView.visibility = View.GONE
            } else {
                // MODIFICA CHIAVE: Duplica il testo per creare uno scorrimento fluido e infinito
                val marqueeText = "$statusText"
                statusTextView.text = marqueeText
                statusTextView.visibility = View.VISIBLE
                statusTextView.isSelected = true
            }
        }
    }
}