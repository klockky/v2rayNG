package com.v2ray.ang.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.databinding.ItemRecyclerFirewallLogBinding
import com.v2ray.ang.service.TunFirewallLog

class FirewallLogAdapter(
    private var items: List<TunFirewallLog.Entry> = emptyList(),
) : RecyclerView.Adapter<FirewallLogAdapter.ViewHolder>() {

    fun update(newItems: List<TunFirewallLog.Entry>) {
        items = newItems
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemRecyclerFirewallLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        val ctx = holder.itemView.context
        val pm = ctx.packageManager

        // App icon + name
        val label = if (entry.packageName != null) {
            try {
                val ai = pm.getApplicationInfo(entry.packageName, 0)
                holder.binding.appIcon.setImageDrawable(pm.getApplicationIcon(ai))
                pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                holder.binding.appIcon.setImageDrawable(null)
                entry.packageName
            }
        } else {
            holder.binding.appIcon.setImageDrawable(null)
            "UID ${entry.uid}"
        }
        holder.binding.appName.text = label

        // Protocol + destination
        val proto = when (entry.protocol) {
            6 -> "TCP"
            17 -> "UDP"
            else -> "proto=${entry.protocol}"
        }
        holder.binding.dropDetails.text = "$proto \u2192 ${entry.lastDstIp}:${entry.lastDstPort}"

        // Drop count
        holder.binding.dropCount.text = entry.count.toString()
    }

    class ViewHolder(val binding: ItemRecyclerFirewallLogBinding) : RecyclerView.ViewHolder(binding.root)
}
