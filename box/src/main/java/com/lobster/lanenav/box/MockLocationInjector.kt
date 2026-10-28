package com.lobster.lanenav.box

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.lobster.lanenav.shared.LocationPacket

/**
 * 把手机传来的定位写进 Android 系统定位服务(测试提供器)。
 *
 * 前提: 本应用必须是系统「模拟位置应用」。
 *   设置 → 开发者选项 → 选择模拟位置信息应用 → 定位注入(盒子端)
 * 或 ADB: adb shell appops set com.lobster.lanenav.box android:mock_location allow
 *
 * 注入后, 盒子上所有读取系统 GPS 的应用(高德车机版/GPSTest)都会收到该坐标。
 */
class MockLocationInjector(private val ctx: Context) {

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var ready = false
    private var lastNanos = 0L

    /** 提供器是否已注册(影子模式下真实 GPS 会被遮蔽) */
    val isReady: Boolean get() = ready

    /** 最近一次注册成功的时间, 用于断流超时判定 */
    @Volatile var preparedAt: Long = 0L
        private set

    // ---- 归还真实 GPS 后的「唤醒探针」 ----
    // 移除测试提供器后，如果没有任何应用在请求 gps，系统 GNSS 引擎不会重新启动，
    // 高德就一直拿不到定位、也不会自己切回原车 GPS。
    // 这里由我们主动请求真实 gps，把引擎拉起来，位置会同时分发给所有监听者。
    @Volatile private var realProbeActive = false
    @Volatile var realFixAt: Long = 0L
        private set

    private val realListener = LocationListener { loc ->
        if (loc.provider == PROVIDER) realFixAt = System.currentTimeMillis()
    }

    /** 距离最近一次真实 GPS 定位的毫秒数；-1 表示还没收到过 */
    val realFixAgeMs: Long
        get() = if (realFixAt > 0) System.currentTimeMillis() - realFixAt else -1L

    val isProbingReal: Boolean get() = realProbeActive

    /** 注册测试提供器。失败(无 mock 权限)返回 false 并给出原因。 */
    fun prepare(): Pair<Boolean, String> {
        if (ready) return true to "已就绪"
        // 重新接管前先停掉真实 GPS 探针，避免和自己抢同一个提供器
        stopRealProbe()
        return try {
            // 已存在则先移除, 避免 IllegalArgumentException
            runCatching { lm.removeTestProvider(PROVIDER) }

            @Suppress("DEPRECATION")
            lm.addTestProvider(
                PROVIDER,
                false,  // requiresNetwork
                false,  // requiresSatellite
                false,  // requiresCell
                false,  // hasMonetaryCost
                true,   // supportsAltitude
                true,   // supportsSpeed
                true,   // supportsBearing
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
            lm.setTestProviderEnabled(PROVIDER, true)
            ready = true
            preparedAt = System.currentTimeMillis()
            true to "模拟位置提供器已就绪"
        } catch (e: SecurityException) {
            Log.e(TAG, "无模拟位置权限", e)
            false to "缺少模拟位置权限: 请在开发者选项中选择本应用"
        } catch (e: Exception) {
            Log.e(TAG, "注册提供器失败", e)
            false to "注册失败: ${e.message}"
        }
    }

    /** 注入一帧定位。返回是否成功。 */
    @Suppress("DEPRECATION")
    fun inject(p: LocationPacket): Boolean {
        if (!ready) {
            val (ok, msg) = prepare()
            if (!ok) {
                lastError = msg
                return false
            }
        }
        return try {
            val loc = Location(PROVIDER).apply {
                latitude = p.lat
                longitude = p.lon
                altitude = p.alt
                bearing = normalizeBearing(p.bearing)
                speed = p.speed.coerceAtLeast(0f)
                accuracy = if (p.acc <= 0f) 8f else p.acc
                time = if (p.ts > 0) p.ts else System.currentTimeMillis()

                // elapsedRealtimeNanos 必须单调递增, 否则部分 ROM 会丢弃
                var n = SystemClock.elapsedRealtimeNanos()
                if (n <= lastNanos) n = lastNanos + 1_000_000 // +1ms
                lastNanos = n
                elapsedRealtimeNanos = n

                if (Build.VERSION.SDK_INT >= 26) {
                    verticalAccuracyMeters = if (p.vacc > 0f) p.vacc else 5f
                    speedAccuracyMetersPerSecond = 0.5f
                    bearingAccuracyDegrees = 5f
                }
                // isFromMockProvider 为只读属性，由系统在 setTestProviderLocation 时自动置位

                // 卫星数写进 extras：GnssStatus 由系统 GNSS HAL 驱动提供，
                // 模拟位置提供器无法向其注入卫星，因此只能走 extras 这条非标准通道，
                // 供读取 extras 的导航/测试应用使用。
                extras = android.os.Bundle().apply {
                    putInt("satellites", p.sats)
                    putInt("satelliteCount", p.sats)
                    putInt("sats", p.sats)
                    putInt("satsUsed", p.sats)
                    putInt("satsTotal", if (p.satsTotal > 0) p.satsTotal else p.sats)
                    putInt("satellites_visible", if (p.satsTotal > 0) p.satsTotal else p.sats)
                    putInt("satellites_used", p.sats)
                    putInt("maxCn0", 0)
                    putInt("meanCn0", 0)
                }
            }
            lm.setTestProviderLocation(PROVIDER, loc)
            lastError = null
            true
        } catch (e: SecurityException) {
            lastError = "注入被拒绝: ${e.message}"
            false
        } catch (e: Exception) {
            lastError = "注入失败: ${e.message}"
            false
        }
    }

    /**
     * 停止注入，把定位交还车机自带 GPS。
     *
     * 两个关键点：
     *  1. 不再先调 setTestProviderEnabled(false)。部分车机在「同名提供器」上执行
     *     这个操作后，真实 gps 会被留在禁用态，移除测试提供器也不会自动恢复，
     *     表现就是断开后高德一直不动、也不会切回原车 GPS。
     *  2. 移除后主动请求一次真实 gps，把 GNSS 引擎唤醒，否则没有应用在请求定位，
     *     引擎不启动，导航软件收不到任何回调。
     */
    fun release() {
        if (ready) {
            runCatching { lm.removeTestProvider(PROVIDER) }
            ready = false
            preparedAt = 0L
        }
        startRealProbe()
    }

    /** 主动请求真实 GPS，唤醒系统 GNSS 引擎（位置会分发给所有监听者）。 */
    @SuppressLint("MissingPermission")
    fun startRealProbe() {
        if (realProbeActive) return
        realProbeActive = true
        realFixAt = 0L
        runCatching {
            lm.requestLocationUpdates(
                PROVIDER, 1000L, 0f, realListener, Looper.getMainLooper()
            )
        }.onFailure {
            Log.w(TAG, "真实 GPS 探针启动失败: ${it.message}")
            realProbeActive = false
        }
    }

    fun stopRealProbe() {
        if (!realProbeActive) return
        realProbeActive = false
        runCatching { lm.removeUpdates(realListener) }
    }

    private fun normalizeBearing(b: Float): Float {
        var v = b % 360f
        if (v < 0) v += 360f
        return v
    }

    var lastError: String? = null
        private set

    companion object {
        private const val TAG = "LaneNavBox"
        const val PROVIDER = LocationManager.GPS_PROVIDER // "gps"
    }
}
