package com.lobster.lanenav.box

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import android.util.Log
import com.lobster.lanenav.shared.LocationPacket
import com.lobster.lanenav.shared.WireProtocol
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.pow

/**
 * 盒子端中继服务:
 *  1. 连手机 TCP Server, 逐行读 JSON
 *  2. 每帧交给 MockLocationInjector 写入系统 GPS
 *  3. 断流超时告警 / 指数退避重连 / 熄火省电指令
 */
class MockRelayService : Service() {

    private lateinit var cfg: BoxConfig
    private lateinit var injector: MockLocationInjector
    private lateinit var sourceMonitor: SourceMonitor
    private var bleClient: BleGattClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var out: BufferedWriter? = null

    private var consecutiveFail = 0

    override fun onCreate() {
        super.onCreate()
        cfg = BoxConfig(this)
        injector = MockLocationInjector(this)
        sourceMonitor = SourceMonitor(this)
        sourceMonitor.onStatus = { st ->
            srcLabel = st.source.label
            srcSats = "${st.satsUsed}/${st.satsTotal}"
            srcAgeSec = if (st.ageMs < 0) -1 else st.ageMs / 1000
        }
        sourceMonitor.start()
        running = true
        ServiceCompat.startForeground(
            this,
            NOTI_ID,
            buildNotification("启动中…"),
            if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )
        acquireWakeLock()
        val ch = cfg.channel
        channelName = ch
        if (ch == "ble" || ch == "both") {
            startBle()
        }
        if (ch == "tcp" || ch == "both") {
            thread(name = "relay-client", isDaemon = true) { connectLoop() }
        }
        if (ch == "ble") {
            thread(name = "ble-watchdog", isDaemon = true) { bleWatchdog() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ---------------- BLE 通道 ----------------

    private fun startBle() {
        val c = BleGattClient(this)
        c.onPacket = { pkt -> handlePacket(pkt, fromBle = true) }
        c.onReady = {
            onConnected()
            bleReady = true
            updateNoti("BLE 已连接 · 正在注入定位")
        }
        c.onDisconnected = {
            bleReady = false
        }
        bleClient = c
        if (!c.startScan()) {
            bleError = c.lastError ?: "BLE 启动失败"
            Log.e(TAG, "BLE 启动失败: " + bleError)
            updateNoti("BLE 启动失败: " + bleError)
        } else {
            Log.i(TAG, "BLE 通道已启动，扫描中")
            updateNoti("BLE 扫描中 · 等待手机广播")
        }
    }

    private fun bleWatchdog() {
        while (running) {
            sleepQuiet(3000)
            if (!running) break
            val c = bleClient ?: continue
            val silentMs = if (lastPacketAt > 0)
                System.currentTimeMillis() - lastPacketAt else Long.MAX_VALUE

            if (!c.isReady() || silentMs > 8000) {
                if (c.isReady()) {
                    Log.i(TAG, "BLE 超 8s 无数据，重连")
                    c.close()
                    bleReady = false
                }
                val (ok, msg) = injector.prepare()
                if (!ok) {
                    state = State.NO_MOCK_PERMISSION
                    updateNoti(msg)
                    sleepQuiet(5000)
                    continue
                }
                state = State.CONNECTING
                updateNoti("BLE 扫描中 · 等待手机广播")
                if (!c.startScan()) {
                    bleError = c.lastError ?: "BLE 扫描失败"
                    updateNoti("BLE 扫描失败: " + bleError)
                }
            }
        }
    }

    // ---------------- 统一数据包处理 ----------------

    /** 收到一帧定位（TCP 或 BLE 共用）。 */
    private fun handlePacket(pkt: LocationPacket, fromBle: Boolean = false) {
        val now = System.currentTimeMillis()

        injector.inject(pkt)
        lastPacketAt = now
        lastFixText = String.format(
            "%.6f, %.6f · ±%.1fm · %s · %d/%d星",
            pkt.lat, pkt.lon, pkt.acc, pkt.fix.tag, pkt.sats,
            if (pkt.satsTotal > 0) pkt.satsTotal else pkt.sats
        )
        phoneSatsUsed = pkt.sats
        phoneSatsTotal = if (pkt.satsTotal > 0) pkt.satsTotal else pkt.sats
        lastLatencyMs = now - pkt.ts
        rxCount++
        lastChannel = if (fromBle) "BLE" else "TCP"

        if (cfg.ecoMode) maybeSendEco(pkt, now)
    }

    // ---------------- 连接循环 ----------------

    private fun connectLoop() {
        while (running) {
            val ip = cfg.phoneIp
            if (ip.isBlank()) {
                state = State.WAIT_CONFIG
                updateNoti("未配置手机 IP · 请打开应用设置")
                sleepQuiet(3000)
                continue
            }

            state = State.CONNECTING
            updateNoti("正在连接 $ip:${cfg.port} …")

            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ip, cfg.port), 3000)
                socket = s

                val (ok, msg) = injector.prepare()
                if (!ok) {
                    state = State.NO_MOCK_PERMISSION
                    updateNoti(msg)
                    Log.e(TAG, msg)
                    runCatching { s.close() }
                    socket = null
                    sleepQuiet(10_000)
                    continue
                }

                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
                out = w
                consecutiveFail = 0
                onConnected()
                updateNoti("已连接 $ip · 正在注入定位")

                // 阻塞读, 直到断开
                BufferedReader(s.getInputStream().reader()).useLines { lines ->
                    var lastRxMs = System.currentTimeMillis()
                    var statCount = 0
                    var statBad = 0
                    var statSumDelay = 0L

                    for (line in lines) {
                        val pkt = LocationPacket.fromJson(line)
                        if (pkt == null) { statBad++; continue }

                        val now = System.currentTimeMillis()
                        lastRxMs = now
                        statCount++
                        statSumDelay += (now - pkt.ts).coerceIn(0, 5000)

                        handlePacket(pkt, fromBle = false)

                        if (statCount % 30 == 0) {
                            avgLatency = statSumDelay / statCount
                            Log.i(TAG, "rx=$statCount bad=$statBad avgDelay=${avgLatency}ms")
                        }
                    }
                    Log.i(TAG, "连接结束 rx=$statCount bad=$statBad")
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "连接失败/断开: ${lastError}")
            } finally {
                state = State.DISCONNECTED
                runCatching { out?.close() }
                runCatching { socket?.close() }
                out = null
                socket = null
            }

            if (!running) break
            consecutiveFail++
            updateNoti("已断开 · ${backoff(consecutiveFail)}s 后重连")
            sleepQuiet(backoff(consecutiveFail) * 1000L)
        }
    }

    private fun backoff(n: Int): Long {
        val s = (2.0.pow(n.coerceAtMost(4))).toLong() // 2,4,8,16
        return s.coerceAtMost(15)
    }

    // ---------------- 省电协调 ----------------

    private var stillSince = 0L
    private var ecoSent = false

    private fun maybeSendEco(pkt: LocationPacket, now: Long) {
        if (pkt.speed < 0.5f) {
            if (stillSince == 0L) stillSince = now
            if (!ecoSent && now - stillSince > 60_000) {
                sendCommand(WireProtocol.CMD_SLEEP)
                ecoSent = true
                Log.i(TAG, "静止 60s, 通知手机降频")
            }
        } else {
            stillSince = 0L
            if (ecoSent) {
                sendCommand(WireProtocol.CMD_WAKE)
                ecoSent = false
                Log.i(TAG, "开始移动, 通知手机恢复 10Hz")
            }
        }
    }

    private fun sendCommand(cmd: String) {
        val bleOk = bleClient?.sendCommand(cmd) ?: false
        if (!bleOk) {
            val w = out ?: return
            runCatching { w.write(WireProtocol.command(cmd) + "\n"); w.flush() }
        }
    }

    // ---------------- 生命周期 ----------------

    private fun onConnected() {
        consecutiveFail = 0
        connectedAt = System.currentTimeMillis()
        state = State.RUNNING
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LaneNav::relay").apply {
            setReferenceCounted(false)
            runCatching { acquire(12 * 60 * 60 * 1000L) }
        }
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    override fun onDestroy() {
        running = false
        runCatching { socket?.close() }
        runCatching { bleClient?.close() }
        runCatching { sourceMonitor.stop() }
        runCatching { injector.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        state = State.STOPPED
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- 通知 ----------------

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "定位注入", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("车道级导航 · 盒子端")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNoti(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTI_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    enum class State { STOPPED, WAIT_CONFIG, CONNECTING, DISCONNECTED, NO_MOCK_PERMISSION, RUNNING }

    companion object {
        private const val TAG = "LaneNavBox"
        private const val CHANNEL = "relay"
        private const val NOTI_ID = 2001

        @Volatile var state: State = State.STOPPED
        @Volatile var lastFixText: String = "无"
        @Volatile var lastPacketAt: Long = 0
        @Volatile var lastLatencyMs: Long = 0
        @Volatile var avgLatency: Long = 0
        @Volatile var rxCount: Long = 0
        @Volatile var lastError: String? = null
        @Volatile var connectedAt: Long = 0
        /** 系统实际定位来源: 手机 GPS / 盒子自带 GPS / 无定位 */
        @Volatile var srcLabel: String = "检测中"
        @Volatile var srcSats: String = "0/0"
        /** 手机端上报的卫星数: used / total */
        @Volatile var phoneSatsUsed: Int = 0
        @Volatile var phoneSatsTotal: Int = 0
        @Volatile var srcAgeSec: Long = -1
        @Volatile var bleReady = false
        @Volatile var bleError: String? = null
        @Volatile var lastChannel: String = "-"
        @Volatile var channelName: String = "both"

        fun isRunning(): Boolean = state != State.STOPPED

        /** 当前通道的中文显示名 */
        fun displayChannel(): String = when (channelName) {
            "ble" -> "BLE"
            "tcp" -> "TCP"
            else -> "TCP+BLE"
        }

        fun requestStop(ctx: Context) {
            ctx.stopService(Intent(ctx, MockRelayService::class.java))
        }
    }
}
