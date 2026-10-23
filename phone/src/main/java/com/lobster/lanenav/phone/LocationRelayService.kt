package com.lobster.lanenav.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.lobster.lanenav.shared.LocationPacket
import com.lobster.lanenav.shared.WireProtocol
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 手机端定位转发服务。
 *
 * 职责:
 *  1. 前台服务常驻(foregroundServiceType=location), 持续采集 GNSS
 *  2. TCP Server 监听 19800, 每个连上的盒子各开一条写线程
 *  3. 收到盒子 sleep 命令则降频到 0.1Hz, wake 恢复
 */
class LocationRelayService : Service() {

    private lateinit var lm: LocationManager
    private var bleServer: BleGattServer? = null
    /** 传输通道: tcp / ble / both */
    @Volatile private var channel: String = "both"
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val writers = CopyOnWriteArrayList<BufferedWriter>()

    @Volatile private var current: LocationPacket? = null
    @Volatile private var sats = 0
    @Volatile private var parked = false

    private var lastPushMs = 0L
    private val minIntervalMs = 1000L      // 正常 1Hz
    private val parkedIntervalMs = 10_000L // 停车省电模式

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            sats = used
        }
    }

    private val listener = LocationListener { loc -> handleLocation(loc) }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ServiceCompat.startForeground(
            this,
            NOTI_ID,
            buildNotification("正在转发定位 · 等待盒子连接"),
            if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        channel = getSharedPreferences("lanenav", MODE_PRIVATE)
            .getString("channel", "both") ?: "both"

        if (channel == "tcp" || channel == "both") {
            startServer()
        }
        if (channel == "ble" || channel == "both") {
            startBle()
        }
        startLocationUpdates()
    }

    private fun startBle() {
        val ble = BleGattServer(this)
        ble.onCommand = { text ->
            when (WireProtocol.parseCommand(text)) {
                WireProtocol.CMD_SLEEP -> parked = true
                WireProtocol.CMD_WAKE -> parked = false
            }
        }
        if (ble.start()) {
            bleServer = ble
            Log.i(TAG, "BLE 通道已启动")
        } else {
            bleError = ble.lastError ?: "BLE 启动失败"
            Log.e(TAG, "BLE 启动失败: $bleError")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被系统杀掉后自动重建
        return START_STICKY
    }

    // ---------------- 定位 ----------------

    private fun startLocationUpdates() {
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, listener, mainLooper
            )
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 2000L, 0f, listener, mainLooper
            )
            if (Build.VERSION.SDK_INT >= 24) {
                lm.registerGnssStatusCallback(gnssCallback, null)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "缺少定位权限", e)
        }
    }

    private fun handleLocation(loc: Location) {
        val now = System.currentTimeMillis()
        val interval = if (parked) parkedIntervalMs else minIntervalMs
        if (now - lastPushMs < interval) return
        lastPushMs = now

        val fix = when {
            loc.isFromMockProvider -> LocationPacket.FixType.IMU
            loc.provider == LocationManager.GPS_PROVIDER -> LocationPacket.FixType.GNSS
            else -> LocationPacket.FixType.FUSED
        }

        // GPS 丢失/高精度失效时放大精度值, 让导航 SDK 自己走惯导
        var acc = if (loc.hasAccuracy()) loc.accuracy else 20f
        if (fix == LocationPacket.FixType.IMU) acc = maxOf(acc, 50f)
        if (loc.provider == LocationManager.NETWORK_PROVIDER) acc = maxOf(acc, 30f)

        val pkt = LocationPacket(
            ts = if (loc.time > 0) loc.time else now,
            lat = loc.latitude,
            lon = loc.longitude,
            alt = if (loc.hasAltitude()) loc.altitude else 0.0,
            bearing = if (loc.hasBearing()) loc.bearing else 0f,
            speed = if (loc.hasSpeed()) loc.speed else 0f,
            acc = acc,
            vacc = if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy())
                loc.verticalAccuracyMeters else acc * 2,
            fix = fix,
            sats = sats
        )
        current = pkt
        lastFixSummary = String.format(
            "%.6f,%.6f ±%.1fm %s", pkt.lat, pkt.lon, pkt.acc, pkt.fix.tag
        )
        push(pkt)
        bleServer?.push(pkt)
        bleClientCount = bleServer?.clientCount ?: 0
        updateNoti(clients.size + bleClientCount)
    }

    // ---------------- TCP Server ----------------

    private fun startServer() {
        thread(name = "relay-server", isDaemon = true) {
            try {
                server = ServerSocket(WireProtocol.DEFAULT_PORT).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "服务端已启动 :${WireProtocol.DEFAULT_PORT}")
                while (true) {
                    val sock = server?.accept() ?: break
                    sock.soTimeout = 0
                    sock.tcpNoDelay = true
                    clients += sock
                    clientCount = clients.size
                    updateNoti(clientCount)
                    Log.i(TAG, "盒子已接入: ${sock.inetAddress.hostAddress}")

                    // 每连接一个写线程
                    thread(name = "relay-writer", isDaemon = true) {
                        val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
                        writers += w
                        // 立刻补一帧, 盒子不用等下一秒
                        current?.let { runCatching { w.write(it.toJson() + "\n"); w.flush() } }
                        // 读线程: 收盒子命令
                        runCatching {
                            sock.getInputStream().bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    when (WireProtocol.parseCommand(line)) {
                                        WireProtocol.CMD_SLEEP -> parked = true
                                        WireProtocol.CMD_WAKE -> parked = false
                                    }
                                }
                            }
                        }
                        removeClient(sock, w)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "服务端异常", e)
            }
        }
    }

    private fun push(pkt: LocationPacket) {
        val line = pkt.toJson() + "\n"
        val dead = mutableListOf<BufferedWriter>()
        writers.forEach { w ->
            try {
                w.write(line); w.flush()
            } catch (e: Exception) {
                dead += w
            }
        }
        if (dead.isNotEmpty()) {
            writers.removeAll(dead)
            clientCount = clients.size
        }
    }

    private fun removeClient(sock: Socket, w: BufferedWriter) {
        writers -= w
        clients -= sock
        runCatching { sock.close() }
        clientCount = clients.size
        updateNoti(clientCount)
        Log.i(TAG, "盒子断开, 剩余 $clientCount")
    }

    // ---------------- 通知 ----------------

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "定位转发", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("车道级导航 · 定位转发")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun channelName(): String = when (channel) {
        "tcp" -> "TCP"
        "ble" -> "BLE"
        else -> "TCP+BLE"
    }

    private fun updateNoti(count: Int) {
        val text = when {
            count > 0 -> "已连接盒子 ${count} 台 · ${channelName()} · 推送中"
            channel.contains("ble") && bleError != null ->
                "BLE 不可用: $bleError"
            else -> "等待连接 · ${channelName()} · 端口 ${WireProtocol.DEFAULT_PORT}"
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTI_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { lm.removeUpdates(listener) }
        if (Build.VERSION.SDK_INT >= 24) runCatching { lm.unregisterGnssStatusCallback(gnssCallback) }
        runCatching { bleServer?.stop() }
        writers.forEach { runCatching { it.close() } }
        clients.forEach { runCatching { it.close() } }
        runCatching { server?.close() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LaneNavPhone"
        private const val CHANNEL = "relay"
        private const val NOTI_ID = 1001

        @Volatile var isRunning = false
        @Volatile var clientCount = 0
        @Volatile var bleClientCount = 0
        @Volatile var bleError: String? = null
        @Volatile var lastFixSummary: String = "无"
    }
}
