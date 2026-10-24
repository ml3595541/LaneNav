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
import android.net.wifi.WifiManager
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
    /**
     * WiFi 高性能锁。
     *
     * 盒子连的是手机热点, 屏幕一黑系统就会让 WiFi 进省电模式: 收包变慢、
     * TCP 心跳超时、甚至直接断。这是 WiFi 通道「数据迟滞 + 断连」的主因之一。
     */
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var out: BufferedWriter? = null

    private var consecutiveFail = 0
    /** 断流起始时刻, 用于回差判定 */
    private var silentSince = 0L
    /** 重新接管所需的连续帧计数 */
    private var reacquireCount = 0
    private var reacquireLastMs = 0L

    /** 已发出但未收到回包的 ping 时刻，用于计算真实 RTT */
    private val pendingPings = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    /**
     * 断流期间是否已主动交还真实 GPS。
     *
     * 背景: 注入用的 provider 名就是系统真实 GPS 名("gps"), 一旦注册就会
     * **遮蔽盒子自带 GPS**。如果手机断连/被带走而提供器没释放, 高德就再也
     * 收不到新的定位, 画面永远停在最后一次坐标上 —— 开车时会误以为导航正常。
     * 因此断流超时后主动 release, 让真实 GPS 接管; 手机重新发来数据再重新接管。
     */
    @Volatile private var releasedForSilence = false

    /**
     * 已注入的最新帧时间戳，用于双通道去重。
     *
     * both 模式下同一条定位会同时经 TCP 和 BLE 到达，不去重就会被注入两次，
     * 白耗一半 CPU 且可能造成坐标微抖动。
     */
    @Volatile private var lastInjectedTs: Long = 0L

    /** 服务启动时间, 用于「从未收到数据」时的断流基准 */
    private val serviceStartedAt = System.currentTimeMillis()

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
        // 只要正在持续注入手机坐标, 使用者看到的就是手机 GPS。
        // 比只看 provider 的 mock 标志可靠: 注入期间系统层面还会冒出别的定位
        // (基站定位、提供器重建瞬间的真实 GPS), 光看标志会反复跳。
        sourceMonitor.injectingFresh = {
            injector.isReady && lastPacketAt > 0 &&
                System.currentTimeMillis() - lastPacketAt < 5000
        }
        sourceMonitor.start()
        running = true

        // ── 前台服务启动保护 ──
        // Android 14 (API 34) 起，声明为 connectedDevice 的前台服务在
        // startForeground() 时要求 BLUETOOTH_CONNECT / SCAN / ADVERTISE
        // 至少一个已授予，否则直接抛 SecurityException。
        // 首次安装时权限弹窗还没响应，onResume 就已经把服务拉起来 ——
        // 异常从 onCreate 抛出，进程被杀，表现为「第一次第二次打开闪退，
        // 权限给了以后就不闪」。所以这里必须兜住，并且优雅降级。
        val fgsType = if (Build.VERSION.SDK_INT >= 29 &&
            hasBtRuntimePermission()
        ) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(
                this,
                NOTI_ID,
                buildNotification("启动中…"),
                fgsType
            )
        } catch (e: Exception) {
            // 没有前台服务权限时不能继续跑：系统会在 5 秒内杀掉服务
            lastError = "前台服务启动失败: ${e.message}"
            Log.e(TAG, "startForeground 失败: ${e.message}", e)
            running = false
            state = State.STOPPED
            runCatching { stopSelf() }
            return
        }
        acquireWakeLock()
        val ch = cfg.channel
        channelName = ch
        if (ch == "ble" || ch == "both") {
            startBle()
        }
        if (ch == "tcp" || ch == "both") {
            thread(name = "relay-client", isDaemon = true) { connectLoop() }
        }
        if (ch == "ble" || ch == "both") {
            // both 模式原来不启动 BLE 看门狗 —— BLE 一旦扫描失败或断开,
            // 就再也不会重试, 只能重开应用。
            thread(name = "ble-watchdog", isDaemon = true) { bleWatchdog() }
        }
        thread(name = "silence-watchdog", isDaemon = true) { silenceWatchdog() }
        thread(name = "rtt-probe", isDaemon = true) { rttProbe() }
    }

    /** Android 12+ 是否已授予蓝牙运行时权限（前台服务 type 判定用） */
    private fun hasBtRuntimePermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return true
        return try {
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- 真实链路延时 ----------------

    /**
     * 单时钟 RTT 探针。
     *
     * 原来的「延迟」= 盒子时钟 - 手机GPS时刻，两端从未对时，
     * 钟差 2-3 秒就直接显示 2-3 秒，跟链路快慢无关。
     * 现在发 ping 带上盒子自己的时刻，手机原样回 pong，
     * 收到后用同一个时钟相减，得到的才是真实往返延迟。
     */
    private fun rttProbe() {
        while (running) {
            sleepQuiet(1000)
            if (!running) break
            if (!bleReady && out == null) continue
            val t = System.currentTimeMillis()
            pendingPings[t] = true
            // 只保留最近几笔，避免永远收不到回包时无上限增长
            if (pendingPings.size > 8) {
                pendingPings.keys.minOrNull()?.let { pendingPings.remove(it) }
            }
            sendCommand(WireProtocol.ping(t))
        }
    }

    private fun onPong(sentAt: Long, fromBle: Boolean) {
        if (pendingPings.remove(sentAt) != true) return
        val rtt = System.currentTimeMillis() - sentAt
        if (rtt in 0..5000) {
            lastLatencyMs = rtt
            lastChannel = if (fromBle) "BLE" else "TCP"
            // 延时只有盒子能算（单时钟），回传给手机供界面显示
            sendRaw(WireProtocol.rtt(rtt), useBle = fromBle)
        }
    }

    /** 发一行原始 JSON（ping/pong/rtt 这类非命令报文） */
    private fun sendRaw(text: String, useBle: Boolean = false) {
        if (useBle) {
            if (bleClient?.sendCommand(text) == true) return
        }
        val w = out ?: return
        runCatching { w.write(text + "\n"); w.flush() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ---------------- BLE 通道 ----------------

    private fun startBle() {
        val c = BleGattClient(this)
        c.onPacket = { pkt -> handlePacket(pkt, fromBle = true) }
        c.onPong = { sentAt -> onPong(sentAt, fromBle = true) }
        c.onReady = {
            onConnected()
            bleReady = true
            // 真的连上了，之前的任何 BLE 错误都失效
            bleError = null
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
            // 关键: 扫描启动成功就把上次的错误清掉。
            // 原来 bleError 一旦被设置就永不清除 —— 首次安装时权限还没给,
            // startScan 抛 SecurityException 写入 "缺少蓝牙权限";
            // 之后权限给了、BLE 也真的连上了, 界面却一直挂着这条假警告。
            bleError = null
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

        // 双通道去重: 同一帧（时间戳不新）直接丢弃
        if (pkt.ts > 0 && pkt.ts <= lastInjectedTs) return
        if (pkt.ts > 0) lastInjectedTs = pkt.ts

        // 断流期间已交还真实 GPS，手机数据回来后重新接管。
        // 收回也要回差: 只收到一两帧就立刻收回, 手机稍后又停更,
        // 就变成「归还 → 收回 → 归还」的来回抽搐。要求连续稳定若干帧。
        if (releasedForSilence) {
            val gap = now - reacquireLastMs
            if (reacquireLastMs == 0L || gap > REACQUIRE_GAP_MS) {
                reacquireCount = 0
            }
            reacquireLastMs = now
            reacquireCount++

            if (reacquireCount >= REACQUIRE_FRAMES) {
                releasedForSilence = false
                reacquireCount = 0
                reacquireLastMs = 0L
                Log.i(TAG, "手机数据连续恢复，重新接管系统定位")
                updateNoti("手机数据恢复 · 重新接管定位")
            }
        } else {
            reacquireCount = 0
            reacquireLastMs = 0L
        }

        injector.inject(pkt)
        lastPacketAt = now
        lastFixText = String.format(
            "%.6f, %.6f · ±%.1fm · %s · %d/%d星",
            pkt.lat, pkt.lon, pkt.acc, pkt.fix.tag, pkt.sats,
            if (pkt.satsTotal > 0) pkt.satsTotal else pkt.sats
        )
        phoneSatsUsed = pkt.sats
        phoneSatsTotal = if (pkt.satsTotal > 0) pkt.satsTotal else pkt.sats
        lastBsrc = pkt.bsrc
        lastBearing = pkt.bearing
        // 不再用 pkt.ts 算延迟（那是两台设备的钟差）。
        // 真实延时由 ping/pong 探针写入 lastLatencyMs。
        rxCount++
        lastChannel = if (fromBle) "BLE" else "TCP"

        if (cfg.ecoMode) maybeSendEco(pkt, now)
    }

    // ---------------- 连接循环 ----------------

    /**
     * 断流保护。
     *
     * 注入用的 provider 名就是系统真实 GPS 名（"gps"），注册后会把盒子自带天线
     * 遮蔽掉。手机断连、被带下车、或进程被系统回收后服务重建时，若提供器仍在，
     * 高德会永远停在最后一次坐标 —— 开车时会误以为导航正常。
     *
     * 因此超过 SILENCE_RELEASE_MS 没有新数据就主动 release，让真实 GPS 接管。
     */
    private fun silenceWatchdog() {
        while (running) {
            sleepQuiet(1000)
            if (!running) break
            val now = System.currentTimeMillis()
            val ref = if (lastPacketAt > 0) lastPacketAt else serviceStartedAt
            val silentMs = now - ref

            // 省电模式下手机降到 0.1Hz（10s 一帧），阈值必须放宽，
            // 否则两帧之间就会被误判成断流。正常 5Hz 按 15s 判。
            val threshold = if (ecoSent) SILENCE_RELEASE_ECO_MS else SILENCE_RELEASE_MS

            if (silentMs > threshold && injector.isReady && !releasedForSilence) {
                // 回差: 必须「持续」断流才归还。
                // 手机切后台/息屏时 GPS 常短暂停更几秒, 一超阈值就交还、
                // 恢复后又立刻收回, 界面就会在「手机 GPS / 盒子自带 GPS」之间
                // 反复跳, 导航画面也跟着抽搯。
                if (silentSince == 0L) silentSince = now
                if (now - silentSince < RELEASE_HOLD_MS) continue

                releasedForSilence = true
                silentSince = 0L
                runCatching { injector.release() }
                state = State.DISCONNECTED
                updateNoti("手机 ${silentMs / 1000}s 无数据 · 已交还真实 GPS")
                Log.w(TAG, "断流 ${silentMs}ms(阈值${threshold}ms)，已释放模拟提供器让真实 GPS 接管")
            } else if (silentMs <= threshold) {
                silentSince = 0L
            }
        }
    }

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
                // 读超时：手机热点掉线、手机休眠或路由消失时，连接会「假死」——
                // 它仍然看起来是已连接，但再也没有数据。原来没有超时，
                // BufferedReader 会永久阻塞，直到进程被杀，所以断连后永远不会重连。
                s.soTimeout = TCP_READ_TIMEOUT_MS
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
                        // 测速回包优先处理：pong 不是定位 JSON
                        val pongTs = WireProtocol.parseTs(line, WireProtocol.CMD_PONG)
                        if (pongTs != null) {
                            onPong(pongTs, fromBle = false)
                            continue
                        }

                        val pkt = LocationPacket.fromJson(line)
                        if (pkt == null) { statBad++; continue }

                        val now = System.currentTimeMillis()
                        lastRxMs = now
                        statCount++

                        handlePacket(pkt, fromBle = false)

                        if (statCount % 30 == 0) {
                            // 真延时由 ping/pong 写入，不再用两台设备的钟差
                            avgLatency = lastLatencyMs
                            Log.i(TAG, "rx=$statCount bad=$statBad rtt=${lastLatencyMs}ms")
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

        // WiFi 高性能锁: 盒子熄屏后若不加此锁, 系统会让 WiFi 进省电模式,
        // 导致收包延迟、TCP 连接被回收。
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LaneNav::relay-wifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "WifiLock 获取失败: ${it.message}") }
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
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
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
        /** 多少毫秒无数据后交还真实 GPS（正常 1Hz） */
        private const val SILENCE_RELEASE_MS = 15_000L
        /** 断流需持续多久才真正交还真实 GPS(回差) */
        private const val RELEASE_HOLD_MS = 4_000L
        /** 重新接管需要连续多少帧 */
        private const val REACQUIRE_FRAMES = 3
        /** 帧间隔超过此值即认为「不连续」, 计数归零 */
        private const val REACQUIRE_GAP_MS = 3_000L
        /** 省电模式（0.1Hz）下的断流阈值，需大于手机 10s 的推送间隔 */
        private const val SILENCE_RELEASE_ECO_MS = 40_000L
        /** TCP 读超时：超过此时长无数据即认定连接假死，主动断开重连 */
        private const val TCP_READ_TIMEOUT_MS = 30_000

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
        /** 手机上报的航向来源: sensor / gnss / none */
        @Volatile var lastBsrc: String = "none"
        /** 航向角度，0-360 */
        @Volatile var lastBearing: Float = 0f

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
