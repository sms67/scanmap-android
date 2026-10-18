package com.scanmap.org

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Real Wi-Fi scanning engine (WifiManager). Every tick delivers the cached
 * scan list; per-BSSID (position, RSSI) samples feed a least-squares grid
 * estimator that triangulates AP positions from the walked path — the same
 * model as the reference survey tool: d = 10^((tx1m − rssi) / (10·n)).
 */
class WifiEngine(private val ctx: Context) {

    class ScanRes(val bssid: String, val ssid: String, val rssi: Int, val freq: Int, val caps: String)

    interface Listener {
        fun onScan(list: List<ScanRes>, atMs: Long)
    }

    var listener: Listener? = null
    var lastScanAt = 0L
    var lastResults: List<ScanRes> = emptyList()
        private set

    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = deliver()
    }

    private fun deliver() {
        val list = ArrayList<ScanRes>()
        try {
            for (r in wm.scanResults) {
                val b = r.BSSID ?: continue
                if (b == "02:00:00:00:00:00") continue
                list.add(ScanRes(b, r.SSID ?: "", r.level, r.frequency, r.capabilities ?: ""))
            }
        } catch (_: SecurityException) {
        }
        list.sortByDescending { it.rssi }
        lastResults = list
        lastScanAt = System.currentTimeMillis()
        listener?.onScan(list, lastScanAt)
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                @Suppress("DEPRECATION")
                wm.startScan()
            } catch (_: Exception) {
            }
            deliver()
            handler.postDelayed(this, 4000)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun start() {
        if (running) return
        running = true
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, f)
            }
        } catch (_: Exception) {
        }
        handler.post(loop)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(loop)
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
