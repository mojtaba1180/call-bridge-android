package com.example.callbridge.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.callbridge.core.BridgeLogEntry
import com.example.callbridge.core.LogLevel
import com.example.databinding.ItemEventLogBinding

/**
 * High-efficiency RecyclerView ListAdapter rendering sanitized live bridge event logs.
 */
class EventLogAdapter : ListAdapter<BridgeLogEntry, EventLogAdapter.LogViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemEventLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(
        private val binding: ItemEventLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: BridgeLogEntry) {
            binding.tvLogTime.text = entry.formattedTime()
            binding.tvLogCategory.text = "[${entry.category}]"
            binding.tvLogMessage.text = entry.message

            val categoryColor = when (entry.level) {
                LogLevel.DEBUG -> Color.parseColor("#94A3B8")
                LogLevel.INFO -> Color.parseColor("#38BDF8")
                LogLevel.WARN -> Color.parseColor("#FBBF24")
                LogLevel.ERROR -> Color.parseColor("#F87171")
            }
            binding.tvLogCategory.setTextColor(categoryColor)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<BridgeLogEntry>() {
        override fun areItemsTheSame(oldItem: BridgeLogEntry, newItem: BridgeLogEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: BridgeLogEntry, newItem: BridgeLogEntry): Boolean {
            return oldItem == newItem
        }
    }
}
