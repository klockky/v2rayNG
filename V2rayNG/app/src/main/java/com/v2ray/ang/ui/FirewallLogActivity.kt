package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityFirewallLogBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.service.TunFirewallLog

class FirewallLogActivity : BaseActivity(), SwipeRefreshLayout.OnRefreshListener {

    private val binding by lazy { ActivityFirewallLogBinding.inflate(layoutInflater) }
    private lateinit var adapter: FirewallLogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_firewall_log))

        adapter = FirewallLogAdapter()

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setOnRefreshListener(this)

        refreshData()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_firewall_log, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.clear_all -> {
            TunFirewallLog.clear()
            refreshData()
            toast(getString(R.string.logcat_clear))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onRefresh() {
        binding.refreshLayout.isRefreshing = false
        refreshData()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshData() {
        adapter.update(TunFirewallLog.entries)
    }
}
