package com.lobster.lanenav.box

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 定位来源检测器。
 *
 * 原理: 监听盒子系统 GPS provider 实际下发的 Location, 读取其 mock 标志。
 *   - 系统把测试提供器写入的坐标标记为 mock=true  → 当前用的是手机 GPS
 *   - 非 mock, 且持续有更新                        → 当前用的是盒子自带 GNSS
 *   - 长时间无更新                                 → 无定位
 *
 * ── 两个曾经导致「来源反复跳转」的坑 ──
 *
 * 1) NETWORK_PROVIDER 也是非 mock。
 *    原来两个 provider 共用一个 listener, 基站/WiFi 定位(约 2s 一次)会被
 *    记成「盒子自带 GPS」。手机数据是 5Hz, 网络定位每 2s 插一脚 ——
 *    插入那一瞬间 lastRealMs > lastMockMs, 来源立刻翻成盒子 GPS,
 *    下一帧手机数据又翻回来。界面 1s 轮询随机采样, 就看到来回跳。
 *    → 现在只认 GPS_PROVIDER, 网络定位完全不参与来源判定。
 *
 * 2) 没有回差(hysteresis)。
 *    mock 帧之间只要有几十毫秒空档, 判定就会抖。
 *    → 现在候选来源必须连续维持 SWITCH_HOLD_MS 才真正切换标签。
 */
class SourceMonitor(private val ctx: Context) {

    enum class Source(val label: String) {
        MOCK("手机 GPS"),
        REAL("盒子自带 GPS"),
        NONE("无定位"),
        UNKNOWN("检测中")
    }

    data class Status(
        val source: Source,
        val lastMockMs: Long,   // 最近一次收到 mock 定位的时间
        val lastRealMs: Long,   // 最近一次收到盒子真实定位的时间
        val ageMs: Long,        // 距最近一次定位的毫秒数
        val satsUsed: Int,      // 盒子硬件天线锁定的卫星数
        val satsTotal: Int,
        val provider: String
    )

    var onStatus: ((Status) -> Unit)? = null

    /**
     * 盒子正在持续注入手机坐标时返回 true。
     *
     * 这比只看 provider 的 mock 标志可靠得多: 注入期间不管系统层面还能
     * 收到什么别的定位, 使用者看到的坐标就是手机的。
     */
    var injectingFresh: (() -> Boolean)? = null

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var lastMockMs = 0L
    private var lastRealMs = 0L
    private var lastLocMs = 0L
    private var lastProvider = "-"
    private var satsUsed = 0
    private var satsTotal = 0
    private var running = false

    /** 已经稳定显示中的来源 */
    private var stable: Source = Source.UNKNOWN
    /** 正在考察中的候选来源 */
    private var candidate: Source = Source.UNKNOWN
    private var candidateSince = 0L

    private val listener = LocationListener { loc -> onLocation(loc) }

    private val gnssCb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            satsUsed = used
            satsTotal = status.satelliteCount
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.w(TAG, "监听 GPS provider 失败: ${e.message}")
        }
        // 网络定位只用来判断「有没有定位」, 不参与来源判定, 因此挂在同一个
        // listener 上但被 onLocation 过滤掉。保留注册是为了 ageMs 有意义。
        try {
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.w(TAG, "监听 NETWORK provider 失败: ${e.message}")
        }
        runCatching { lm.registerGnssStatusCallback(gnssCb, handler) }
        handler.post(ticker)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        runCatching { lm.removeUpdates(listener) }
        runCatching { lm.unregisterGnssStatusCallback(gnssCb) }
    }

    private fun onLocation(loc: Location) {
        val now = System.currentTimeMillis()
        lastLocMs = now
        lastProvider = loc.provider ?: "-"

        // 关键: 只认 GPS provider。
        // NETWORK_PROVIDER(基站/WiFi 定位)不是盒子自带 GNSS, 但它同样是
        // 非 mock —— 一旦算进 lastRealMs, 每 2 秒就会把来源标签顶到
        // 「盒子自带 GPS」, 造成反复跳转。
        if (loc.provider != LocationManager.GPS_PROVIDER) return

        val isMock = if (Build.VERSION.SDK_INT >= 31) loc.isMock
        else @Suppress("DEPRECATION") loc.isFromMockProvider

        if (isMock) {
            lastMockMs = now
        } else {
            lastRealMs = now
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            val age = if (lastLocMs > 0) now - lastLocMs else Long.MAX_VALUE
            val injecting = injectingFresh?.invoke() == true

            // 瞬时判定
            val raw = when {
                // 正在注入手机坐标 —— 使用者看到的就是手机 GPS
                injecting -> Source.MOCK
                age > 12_000 -> Source.NONE
                lastMockMs > 0 && now - lastMockMs < 5_000 -> Source.MOCK
                lastRealMs > 0 && now - lastRealMs < 8_000 -> Source.REAL
                else -> Source.NONE
            }

            // 回差: 候选来源要连续维持一段时间才切换, 避免瞬时抖动造成跳字
            if (raw != stable) {
                if (raw == candidate) {
                    if (now - candidateSince >= SWITCH_HOLD_MS) {
                        Log.i(TAG, "定位来源切换: $stable -> $raw")
                        stable = raw
                        candidate = Source.UNKNOWN
                        candidateSince = 0L
                    }
                } else {
                    candidate = raw
                    candidateSince = now
                }
            } else {
                candidate = Source.UNKNOWN
                candidateSince = 0L
            }

            onStatus?.invoke(
                Status(
                    source = stable,
                    lastMockMs = lastMockMs,
                    lastRealMs = lastRealMs,
                    ageMs = if (age == Long.MAX_VALUE) -1 else age,
                    satsUsed = satsUsed,
                    satsTotal = satsTotal,
                    provider = lastProvider
                )
            )
            handler.postDelayed(this, 1000L)
        }
    }

    companion object {
        private const val TAG = "LaneNavBox"
        /** 候选来源需连续维持多久才切换标签 */
        private const val SWITCH_HOLD_MS = 3_000L
    }
}
