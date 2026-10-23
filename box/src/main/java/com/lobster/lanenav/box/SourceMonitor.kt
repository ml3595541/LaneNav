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
 * 这是唯一能 100% 确认"到底在用谁的 GPS"的方法, 比看 TCP 收发包可靠。
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
        val lastRealMs: Long,   // 最近一次收到真实定位的时间
        val ageMs: Long,        // 距最近一次定位的毫秒数
        val satsUsed: Int,      // 盒子硬件天线锁定的卫星数
        val satsTotal: Int,
        val provider: String
    )

    var onStatus: ((Status) -> Unit)? = null

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var lastMockMs = 0L
    private var lastRealMs = 0L
    private var lastLocMs = 0L
    private var lastProvider = "-"
    private var satsUsed = 0
    private var satsTotal = 0
    private var running = false

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

            val source = when {
                age > 10_000 -> Source.NONE          // 10 秒没定位
                lastMockMs > lastRealMs && age <= 10_000 -> Source.MOCK
                lastRealMs > 0 && age <= 10_000 -> Source.REAL
                else -> Source.UNKNOWN
            }

            onStatus?.invoke(
                Status(
                    source = source,
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
    }
}
