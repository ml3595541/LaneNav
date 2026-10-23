package com.lobster.lanenav.phone

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 手机端航向(车头方向)来源。
 *
 * 为什么需要它:
 *   GNSS 的 Location.bearing 是用**位移矢量**算的, 只有车辆真正在动(通常 >3m/s)
 *   时系统才给有效值; 停车、堵车、低速蠕行时 bearing 恒为 0,
 *   于是车机上高德"车头朝上"模式会一直指向正北, 看起来就是"车头卡在一个方向"。
 *   手机有陀螺仪+加速度计+磁力计, 静止时也能给出朝向, 因此由手机补齐。
 *
 * 实现要点:
 *  - 首选 TYPE_ROTATION_VECTOR(系统已做陀螺仪+加速度计+磁力计融合), 精度与稳定性最好
 *  - 退化时用 加速度计+磁力计 自己算旋转矩阵
 *  - 输出为**真北**航向: 传感器给的是磁北, 用 GeomagneticField 的磁偏角修正
 *  - 屏幕旋转要做补偿, 否则横屏时航向整体偏 90°
 *  - 低通滤波按最短角差平滑, 避免磁干扰导致车头抖动
 */
class SensorHeading(ctx: Context) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val appCtx = ctx.applicationContext

    private val rotationSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var haveGravity = false
    private var haveMag = false

    /** 最近一次有效航向(真北, 0-360)。NaN = 尚未就绪。 */
    @Volatile
    var headingDeg: Float = Float.NaN
        private set

    /** 最近一次收到传感器数据的时间 */
    @Volatile
    var lastUpdateMs: Long = 0L
        private set

    /** 当前航向的来源, 用于 UI 显示: 传感器 / 无 */
    @Volatile
    var source: String = "未启动"
        private set

    @Volatile private var running = false

    /** 缓存屏幕旋转角度, 取不到时沿用上次值, 避免回调里反复抛异常 */
    @Volatile private var cachedRotationDeg = 0f

    // 磁偏角修正(真北 = 磁北 + 偏角)
    @Volatile private var declination = 0f
    @Volatile private var lastGeoLat = Double.NaN
    @Volatile private var lastGeoLon = Double.NaN

    /** 平滑系数, 越小越稳但跟随越慢 */
    private val smoothing = 0.18f

    fun start(): Boolean {
        if (running) return true
        val ok = when {
            rotationSensor != null -> {
                sm.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
                source = "传感器"
                true
            }
            accelSensor != null && magSensor != null -> {
                sm.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
                sm.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_GAME)
                source = "传感器(加速度+磁力)"
                true
            }
            else -> {
                source = "无传感器"
                false
            }
        }
        // 旋转矢量不可用时走 加速度+磁力 融合, 由 computeFromAccelMag 处理
        running = ok
        return ok
    }

    fun stop() {
        if (!running) return
        runCatching { sm.unregisterListener(this) }
        running = false
        source = "已停止"
    }

    /** 航向是否可用于注入: 有值且最近 3 秒内更新过。 */
    fun isValid(): Boolean =
        !headingDeg.isNaN() && lastUpdateMs > 0 &&
            System.currentTimeMillis() - lastUpdateMs < 3000

    /**
     * 用当前坐标更新磁偏角。磁偏角随位置变化(中国境内约 -10°~+5°),
     * 不修正的话真北航向会有几度系统偏差, 高速上足以让车头偏一个车道。
     */
    fun setLocation(lat: Double, lon: Double, alt: Double) {
        if (!lat.isNaN()) lastGeoLat = lat
        if (!lon.isNaN()) lastGeoLon = lon
        val la = lastGeoLat
        val lo = lastGeoLon
        if (la.isNaN() || lo.isNaN()) return
        runCatching {
            declination = GeomagneticField(
                la.toFloat(), lo.toFloat(),
                if (alt.isNaN()) 0f else alt.toFloat(),
                System.currentTimeMillis()
            ).declination
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        // 传感器回调频率极高且跑在主线程, 任何未捕获异常都会直接闪退, 统一兜底
        runCatching { dispatchSensor(event) }
            .onFailure { Log.w(TAG, "传感器回调异常: ${it.message}") }
    }

    private fun dispatchSensor(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val r = FloatArray(9)
                runCatching { SensorManager.getRotationMatrixFromVector(r, event.values) }
                    .onSuccess {
                        val o = FloatArray(3)
                        SensorManager.getOrientation(r, o)
                        applyAzimuth(o[0], event.timestamp)
                        r.copyInto(rotationMatrix)
                    }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(event.values, gravity)
                haveGravity = true
                computeFromAccelMag(event.timestamp)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lowPass(event.values, geomagnetic)
                haveMag = true
                computeFromAccelMag(event.timestamp)
            }
        }
    }

    private fun computeFromAccelMag(timestamp: Long) {
        if (rotationSensor != null) return          // 有旋转矢量就不走这条路
        if (!haveGravity || !haveMag) return
        val r = FloatArray(9)
        val ok = SensorManager.getRotationMatrix(r, null, gravity, geomagnetic)
        if (!ok) return
        val o = FloatArray(3)
        SensorManager.getOrientation(r, o)
        applyAzimuth(o[0], timestamp)
    }

    /** azimuth: 弧度, 相对磁北; 转真北 + 屏幕旋转补偿 + 平滑 */
    private fun applyAzimuth(azimuthRad: Float, timestampNs: Long) {
        var deg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
        deg += declination                                     // 磁北 → 真北
        deg += displayRotationDegrees()                        // 屏幕方向补偿
        deg = normalize(deg)

        if (headingDeg.isNaN()) {
            headingDeg = deg
        } else {
            // 最短路径平滑: 直接线性插值会在 359°→1° 处猛转一圈
            var diff = deg - headingDeg
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            headingDeg = normalize(headingDeg + diff * smoothing)
        }
        lastUpdateMs = System.currentTimeMillis()
        if (source.startsWith("传感器")) source = "传感器"
    }

    /**
     * 屏幕旋转补偿。
     *
     * 注意: Service/Application 这类非可视化 Context 在 Android 11(API30) 以上调用
     * Context.getDisplay() 会抛 UnsupportedOperationException, 而传感器回调触发极快,
     * 一抛异常就是整个进程闪退。因此这里改用 WindowManager.defaultDisplay 并且全程兜底。
     */
    private fun displayRotationDegrees(): Float {
        val deg = runCatching {
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            @Suppress("DEPRECATION")
            val rot = wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            when (rot) {
                Surface.ROTATION_90 -> 90f
                Surface.ROTATION_180 -> 180f
                Surface.ROTATION_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(cachedRotationDeg)
        cachedRotationDeg = deg
        return deg
    }

    private fun lowPass(input: FloatArray, output: FloatArray) {
        for (i in output.indices) {
            output[i] = output[i] + (input[i] - output[i]) * 0.2f
        }
    }

    private fun normalize(v: Float): Float {
        var d = v % 360f
        if (d < 0) d += 360f
        return d
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.w(TAG, "传感器精度不可靠: ${sensor?.name}")
        }
    }

    companion object {
        private const val TAG = "LaneNavPhone"
    }
}
