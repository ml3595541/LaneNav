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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
import java.util.concurrent.LinkedBlockingQueue
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
    /** 手机端传感器航向: 停车/低速时 GNSS 的 bearing 恒为 0, 靠它补车头方向 */
    private var heading: SensorHeading? = null
    /** 传输通道: tcp / ble / both */
    @Volatile private var channel: String = "both"
    private var server: ServerSocket? = null

    /**
     * 一条 TCP 连接（socket + 写入器）绑定为一个对象。
     *
     * 原来分成 clients / writers 两个平行列表，断连时只能从 writers 里删，
     * 对应的 socket 留在 clients 里泄漏 —— 枚举次数越算越多，
     * 而且再也无法单独关闭那条死连接。
     */
    private class Conn(val sock: Socket, var w: BufferedWriter? = null)

    private val conns = CopyOnWriteArrayList<Conn>()

    /** 盒子回传的链路延时（手机界面显示） */
    @Volatile var lastRttMs: Long = 0
        private set

    /**
     * 发送队列。
     *
     * 定位回调跑在主线程上, 原来直接在回调里写 socket —— 一旦 WiFi 卡顿,
     * 主线程会被阻塞, 造成界面卡死、回调延迟, 且多个连接并发写同一个
     * BufferedWriter 会拆包(JSON 行被截断), 盒子端解析失败就丢帧。
     *
     * 改为单一发送线程串行写, 队列满时丢弃旧帧保新帧, 避免延迟累积。
     */
    private val txQueue = LinkedBlockingQueue<String>(32)

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var current: LocationPacket? = null
    @Volatile private var sats = 0
    @Volatile private var satsTotal = 0
    @Volatile private var parked = false

    private var lastPushMs = 0L
    /** 上次刷新通知的时刻，用于限频 */
    private var lastNotiMs = 0L
    // 5Hz 采样。原来 1Hz + 限流 1000ms 会因抖动一次跳过一帧，
    // 实际退化成 0.5Hz —— 这是 2s 视觉延迟的主因。
    private val minIntervalMs = 200L
    private val parkedIntervalMs = 10_000L // 停车省电模式

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val list = ArrayList<SatInfo>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                val u = status.usedInFix(i)
                if (u) used++
                list += SatInfo(
                    svid = status.getSvid(i),
                    constellation = status.getConstellationType(i),
                    cn0 = status.getCn0DbHz(i),
                    azimuth = status.getAzimuthDegrees(i),
                    elevation = status.getElevationDegrees(i),
                    used = u
                )
            }
            sats = used
            satsTotal = status.satelliteCount
            // 界面用: 完整卫星列表(星座/方位/仰角/信噪比)
            GnssStore.update(list)
        }
    }

    private val listener = LocationListener { loc -> handleLocation(loc) }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        LogStore.add("SVC", "服务启动")
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 前台服务启动保护:
        // foregroundServiceType=location 在 Android 14 上要求 ACCESS_FINE_LOCATION
        // 已授予, 否则 startForeground 直接抛 SecurityException, 进程被杀。
        // 首次安装权限弹窗还没响应时服务被拉起就会闪退 —— 必须兜住。
        try {
            ServiceCompat.startForeground(
                this,
                NOTI_ID,
                buildNotification("正在转发定位 · 等待盒子连接"),
                if (Build.VERSION.SDK_INT >= 29)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败: ${e.message}", e)
            isRunning = false
            LogStore.add("ERR", "前台服务启动失败: ${e.message}")
            runCatching { stopSelf() }
            return
        }
        channel = getSharedPreferences("lanenav", MODE_PRIVATE)
            .getString("channel", "both") ?: "both"

        if (channel == "tcp" || channel == "both") {
            startServer()
            startTxLoop()
        }
        if (channel == "ble" || channel == "both") {
            startBle()
        }
        acquireLocks()
        startHeading()
        startLocationUpdates()
        startHeartbeat()
    }

    /**
     * 心跳线程：无论有没有 GPS 都维持链路活动。
     *
     * 隧道里 GNSS 完全无信号，手机自然没有定位可发。原本完全静默，
     * 盒子 15s 后就判定断流并交还真实 GPS，出隧道时状态还停在「已断开」。
     * 心跳让「无 GPS」和「链路断开」成为两件可分辨的事。
     */
    private fun startHeartbeat() {
        thread(name = "relay-hb", isDaemon = true) {
            while (isRunning) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                } catch (e: InterruptedException) {
                    break
                }
                if (!isRunning) break
                if (conns.isEmpty() && (bleServer?.clientCount ?: 0) == 0) continue

                val hb = WireProtocol.heartbeat(System.currentTimeMillis())
                if (conns.isNotEmpty()) {
                    while (!txQueue.offer(hb)) txQueue.poll()
                }
                runCatching { bleServer?.sendRaw(hb) }
            }
        }
    }

    /**
     * 拿住 WiFi 和 CPU 锁。
     *
     * 这两把锁是 WiFi 通道卡顿/断连的主因:
     *  - 没有 WifiLock: 屏幕一黑, 系统就让 WiFi 进省电模式, 发包变慢甚至断
     *  - 没有 WakeLock: 手机休眠时 CPU 降频/停核, 定时器和写线程都被挂起
     * 双通道时 BLE 和 WiFi 共用2.4G 射频, 也需要它们都保持活跃才稳。
     */
    private fun acquireLocks() {
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LaneNav::wifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "WifiLock 获取失败: ${it.message}") }

        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LaneNav::phone").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }.onFailure { Log.w(TAG, "WakeLock 获取失败: ${it.message}") }
    }

    private fun releaseLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun startHeading() {
        val h = SensorHeading(this)
        if (h.start()) {
            heading = h
            Log.i(TAG, "航向传感器已启动: ${h.source}")
        } else {
            Log.w(TAG, "无可用航向传感器, 仅能依赖 GNSS bearing")
        }
    }

    private fun startBle() {
        val ble = BleGattServer(this)
        LogStore.add("BLE", "广播已启动，等待盒子连接")
        ble.onCommand = { text ->
            val pingTs = WireProtocol.parseTs(text, WireProtocol.CMD_PING)
            if (pingTs != null) {
                // 回 pong 走数据通道，盒子用自己的时钟算 RTT
                runCatching { ble.sendRaw(WireProtocol.pong(pingTs)) }
            } else {
                when (WireProtocol.parseCommand(text)) {
                    WireProtocol.CMD_SLEEP -> parked = true
                    WireProtocol.CMD_WAKE -> parked = false
                }
            }
        }
        if (ble.start()) {
            bleServer = ble
            Log.i(TAG, "BLE 通道已启动")
        } else {
            bleError = ble.lastError ?: "BLE 启动失败"
            LogStore.add("ERR", "BLE 启动失败: " + bleError)
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
                LocationManager.GPS_PROVIDER, 200L, 0f, listener, mainLooper
            )
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, mainLooper
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

        val (bearing, bsrc) = resolveBearing(loc)

        val pkt = LocationPacket(
            ts = if (loc.time > 0) loc.time else now,
            lat = loc.latitude,
            lon = loc.longitude,
            alt = if (loc.hasAltitude()) loc.altitude else 0.0,
            bearing = bearing,
            speed = if (loc.hasSpeed()) loc.speed else 0f,
            acc = acc,
            vacc = if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy())
                loc.verticalAccuracyMeters else acc * 2,
            fix = fix,
            sats = sats,
            satsTotal = satsTotal,
            bsrc = bsrc
        )
        current = pkt
        lastPushAt = now
        updateHeadingSummary(bearing, bsrc)
        lastAccuracy = acc
        lastFixSummary = String.format(
            "%.6f,%.6f ±%.1fm %s %s", pkt.lat, pkt.lon, pkt.acc, pkt.fix.tag, headingTag()
        )
        push(pkt)
        bleServer?.push(pkt)
        bleClientCount = bleServer?.clientCount ?: 0
        // 5Hz 下每秒刷 5 次通知太浪费（跨进程 Binder 调用，主线程上执行）。
        // 限制到最多 1 秒一次。
        val nowNoti = System.currentTimeMillis()
        if (nowNoti - lastNotiMs >= 1000L) {
            lastNotiMs = nowNoti
            updateNoti(conns.size + bleClientCount)
        }
    }

    // ---------------- 航向 ----------------

    /**
     * 决定这一帧用哪个航向:
     *  - 车速够快且有 GNSS 航向 → 用 GNSS(方向准确, 不受车身磁场干扰)
     *  - 低速/停车/无 GNSS 航向 → 用手机传感器(陀螺仪+磁力计), 否则车头会卡在正北
     */
    /** 航向值 + 来源，供界面和车机端显示 */
    private fun resolveBearing(loc: Location): Pair<Float, String> {
        val h = heading
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        val gnssOk = loc.hasBearing() && speed >= GNSS_BEARING_MIN_SPEED

        if (gnssOk) return loc.bearing to "gnss"

        // 低速: 优先传感器
        if (h != null && h.isValid()) {
            h.setLocation(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else 0.0)
            return h.headingDeg to "sensor"
        }

        // 传感器不可用但 GNSS 有航向值(即使低速), 也用上
        return if (loc.hasBearing()) loc.bearing to "gnss" else 0f to "none"
    }

    /** UI 上显示航向来源, 便于现场判断到底在用哪一路。 */
    private fun headingTag(): String {
        val h = heading ?: return "无航向传感器"
        return if (h.isValid()) String.format("航向%.0f°(传感器)", h.headingDeg)
        else "航向无(传感器未就绪)"
    }

    /** 把当前航向与来源写进静态字段，供 UI 直接读 */
    private fun updateHeadingSummary(bearing: Float, bsrc: String) {
        lastHeadingSummary = when (bsrc) {
            "sensor" -> String.format("%.0f° · 传感器", bearing)
            "gnss" -> String.format("%.0f° · GNSS", bearing)
            else -> "无"
        }
        lastRtt = lastRttMs
    }

    // ---------------- TCP Server ----------------

    /**
     * 监听线程。
     *
     * 关键教训: 原来整个 accept 循环包在一个 try 里, 循环体内任何一次异常
     * (比如打电话/切应用时系统拒绝 Binder 通知调用) 都会让线程永久退出。
     * 但 ServerSocket 仍然在监听, 操作系统的连接队列照样完成 TCP 握手 ——
     * 盒子能「连上」却永远收不到数据, 界面永远卡在「连接中」,
     * 只有停止服务重建线程才能恢复。
     *
     * 现在: 单个连接的错误完全隔离在 setupClient 内部,
     * accept 外层再套一层重建循环, 任何情况下都能自愈。
     */
    private fun startServer() {
        thread(name = "relay-server", isDaemon = true) {
            while (isRunning) {
                var ss: ServerSocket? = null
                try {
                    ss = ServerSocket().apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(WireProtocol.DEFAULT_PORT))
                    }
                    server = ss
                    Log.i(TAG, "服务端已启动 :${WireProtocol.DEFAULT_PORT}")

                    while (isRunning) {
                        val sock = try {
                            ss.accept()
                        } catch (e: Exception) {
                            Log.w(TAG, "accept 中断: ${e.message}")
                            break
                        }
                        // 单连接的任何异常都不得影响 accept 循环
                        try {
                            setupClient(sock)
                        } catch (e: Exception) {
                            Log.w(TAG, "接入处理异常: ${e.message}")
                            runCatching { sock.close() }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "服务端异常, 1s 后重建: ${e.message}")
                } finally {
                    runCatching { ss?.close() }
                    if (server === ss) server = null
                }
                if (isRunning) runCatching { Thread.sleep(1000) }
            }
        }
    }

    private fun setupClient(sock: Socket) {
        sock.soTimeout = 0
        sock.tcpNoDelay = true
        // 开 TCP 保活: 盒子被带离/手机热点掉线时, 死连接能被及时发现并回收
        runCatching { sock.keepAlive = true }
        val c = Conn(sock)
        conns += c
        LogStore.add("TCP", "盒子已连接 " + (sock.inetAddress?.hostAddress ?: "?"))
        clientCount = conns.size
        updateNoti(clientCount)
        Log.i(TAG, "盒子已接入: ${sock.inetAddress?.hostAddress}")

        thread(name = "relay-writer", isDaemon = true) {
            val w = try {
                BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
            } catch (e: Exception) {
                removeClient(c)
                return@thread
            }
            c.w = w
            // 立刻补一帧, 盒子不用等下一秒
            current?.let { writeLine(w, it.toJson() + "\n") }

            runCatching {
                sock.getInputStream().bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val pingTs = WireProtocol.parseTs(line, WireProtocol.CMD_PING)
                        if (pingTs != null) {
                            writeLine(w, WireProtocol.pong(pingTs) + "\n")
                        } else {
                            val rtt = WireProtocol.parseTs(line, WireProtocol.CMD_RTT)
                            if (rtt != null) {
                                lastRttMs = rtt
                            } else when (WireProtocol.parseCommand(line)) {
                                WireProtocol.CMD_SLEEP -> parked = true
                                WireProtocol.CMD_WAKE -> parked = false
                            }
                        }
                    }
                }
            }
            removeClient(c)
        }
    }

    /** 所有写 socket 都必须过这把锁：读写线程会并发写同一条连接。 */
    private val writeLock = Any()

    private fun writeLine(w: BufferedWriter, text: String): Boolean = synchronized(writeLock) {
        try {
            w.write(text)
            w.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 投递一帧。只入队不阻塞 —— 定位回调在主线程上, 不能在这里写 socket。
     * 队列满时丢最旧的一帧: 宁可少一帧, 也不要让延迟不断累积。
     */
    private fun push(pkt: LocationPacket) {
        val line = pkt.toJson() + "\n"
        while (!txQueue.offer(line)) {
            txQueue.poll()
        }
    }

    /** 单一发送线程: 串行写所有连接, 避免多线程拆包。 */
    private fun startTxLoop() {
        thread(name = "relay-tx", isDaemon = true) {
            while (true) {
                val line = try {
                    txQueue.take()
                } catch (e: InterruptedException) {
                    break
                }
                val dead = mutableListOf<Conn>()
                conns.forEach { c ->
                    val w = c.w ?: return@forEach
                    if (!writeLine(w, line)) dead += c
                }
                if (dead.isNotEmpty()) {
                    dead.forEach { removeClient(it, updateNoti = false) }
                    clientCount = conns.size
                    updateNoti(clientCount)
                }
            }
        }
    }

    private fun removeClient(c: Conn, updateNoti: Boolean = true) {
        conns -= c
        LogStore.add("TCP", "盒子断开，剩余 " + conns.size)
        runCatching { c.w?.close() }
        runCatching { c.sock.close() }
        c.w = null
        clientCount = conns.size
        if (updateNoti) updateNoti(clientCount)
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
        heading?.stop()
        heading = null
        if (Build.VERSION.SDK_INT >= 24) runCatching { lm.unregisterGnssStatusCallback(gnssCallback) }
        runCatching { bleServer?.stop() }
        conns.forEach { c ->
            runCatching { c.w?.close() }
            runCatching { c.sock.close() }
        }
        conns.clear()
        runCatching { server?.close() }
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LaneNavPhone"
        private const val CHANNEL = "relay"
        private const val NOTI_ID = 1001

        /** 车速高于此值(m/s)且 GNSS 有航向时优先用 GNSS; 低于则改用传感器。约 13km/h */
        private const val GNSS_BEARING_MIN_SPEED = 3.6f

        /** 心跳间隔。盒子 8s 没收到心跳就判定链路中断，2s 留足冗余。 */
        private const val HEARTBEAT_MS = 2_000L

        @Volatile var isRunning = false
        @Volatile var clientCount = 0
        @Volatile var bleClientCount = 0
        @Volatile var bleError: String? = null
        @Volatile var lastFixSummary: String = "无"
        /** 盒子回传的链路延时 */
        @Volatile var lastRtt: Long = 0
        /** 航向摘要（值 + 来源），供界面显示 */
        @Volatile var lastHeadingSummary: String = "无"
        /** 最近一次定位精度（米），-1 = 无 */
        @Volatile var lastAccuracy: Float = -1f
        /** 最近一次产出定位包的时刻，用于界面判断「是否在等 GPS」 */
        @Volatile var lastPushAt: Long = 0
    }
}
