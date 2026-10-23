package com.lobster.lanenav.box

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
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

    /** 注册测试提供器。失败(无 mock 权限)返回 false 并给出原因。 */
    fun prepare(): Pair<Boolean, String> {
        if (ready) return true to "已就绪"
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

    /** 停止注入: 关闭并移除提供器, 让系统 GPS 回到原生状态。 */
    fun release() {
        if (!ready) return
        runCatching { lm.setTestProviderEnabled(PROVIDER, false) }
        runCatching { lm.removeTestProvider(PROVIDER) }
        ready = false
        preparedAt = 0L
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
