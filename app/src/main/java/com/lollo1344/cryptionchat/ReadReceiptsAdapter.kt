package com.lollo1344.cryptionchat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ReadReceiptsAdapter(
    private val receipts: List<ReadReceiptInfo>
) : RecyclerView.Adapter<ReadReceiptsAdapter.ReceiptViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReceiptViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_read_receipt, parent, false)
        return ReceiptViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReceiptViewHolder, position: Int) {
        holder.bind(receipts[position])
    }

    override fun getItemCount(): Int = receipts.size

    class ReceiptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val usernameText: TextView = itemView.findViewById(R.id.username_text)
        private val readAtText: TextView = itemView.findViewById(R.id.read_at_text)

        fun bind(receipt: ReadReceiptInfo) {
            usernameText.text = receipt.username
            readAtText.text = TimeUtils.formatReadTimestamp(receipt.readAt)
        }
    }
}