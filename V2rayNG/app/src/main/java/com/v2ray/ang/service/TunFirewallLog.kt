package com.v2ray.ang.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Shared singleton that collects records of packets dropped by [TunPacketFilter].
 * The UI ([com.v2ray.ang.ui.FirewallLogActivity]) reads from [entries] to display
 * which apps attempted to probe the VPN interface.
 */
object TunFirewallLog {

    data class Entry(
        val uid: Int,
        val packageName: String?,
        val lastDstIp: String,
        val lastDstPort: Int,
        val protocol: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val count: Long,
    )

    private val map = ConcurrentHashMap<Int, Entry>()

    /** Snapshot of current entries, sorted by most-recent first. */
    val entries: List<Entry>
        get() = map.values.sortedByDescending { it.lastSeen }

    fun record(uid: Int, packageName: String?, dstIp: String, dstPort: Int, protocol: Int) {
        val now = System.currentTimeMillis()
        map.compute(uid) { _, old ->
            if (old != null) {
                old.copy(
                    lastDstIp = dstIp,
                    lastDstPort = dstPort,
                    lastSeen = now,
                    count = old.count + 1,
                )
            } else {
                Entry(uid, packageName, dstIp, dstPort, protocol, firstSeen = now, lastSeen = now, count = 1)
            }
        }
    }

    fun clear() = map.clear()
}
