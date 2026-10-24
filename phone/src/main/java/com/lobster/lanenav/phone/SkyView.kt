package com.lobster.lanenav.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.location.GnssStatus
import android.view.View

/**
 * 卫星可视化。
 *
 * 两种模式共用一份卫星数据：
 *  - 地球：近似球体的地球示意图，卫星按方位/仰角投影到球面上
 *  - 天空图：极坐标图，圆心=天顶，圆周=地平线，正北朝上
 *
 * 仰角 90° → 圆心，0° → 圆周。
 */
class SkyView(ctx: Context) : View(ctx) {

    enum class Mode { GLOBE, SKY }

    var mode: Mode = Mode.GLOBE
        set(v) { field = v; invalidate() }

    /** 空数据时显示提示文字 */
    var emptyHint: String = ""
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val globePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 星座配色，与图例一致 */
    private fun colorOf(c: Int): Int = when (c) {
        GnssStatus.CONSTELLATION_BEIDOU -> 0xFFFFB020.toInt()
        GnssStatus.CONSTELLATION_GPS -> 0xFF38A7D5.toInt()
        GnssStatus.CONSTELLATION_GLONASS -> 0xFFA78BFA.toInt()
        GnssStatus.CONSTELLATION_GALILEO -> 0xFF2ED573.toInt()
        GnssStatus.CONSTELLATION_QZSS -> 0xFFFF7AB6.toInt()
        else -> 0xFF8FA3B8.toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 0.62f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f * 0.92f

        if (mode == Mode.GLOBE) drawGlobe(canvas, cx, cy, r)
        else drawSkyGrid(canvas, cx, cy, r)

        val sats = GnssStore.sats
        if (sats.isEmpty() || !GnssStore.fresh()) {
            paint.style = Paint.Style.FILL
            paint.color = 0xCC0A1220.toInt()
            canvas.drawCircle(cx, cy, r * 0.52f, paint)

            textPaint.color = 0xFFE8EEF5.toInt()
            textPaint.textSize = r * 0.115f
            textPaint.isFakeBoldText = true
            val msg = emptyHint.ifEmpty { "启动共享后采集卫星" }
            canvas.drawText(msg, cx, cy + textPaint.textSize * 0.36f, textPaint)
            return
        }

        // 卫星落点
        for (s in sats) {
            val elev = s.elevation.coerceIn(0f, 90f)
            val az = s.azimuth
            // 仰角 90 → 圆心；0 → 圆周
            val rr = (1f - elev / 90f) * r
            val rad = Math.toRadians((az - 90f).toDouble())
            val x = cx + (rr * Math.cos(rad)).toFloat()
            val y = cy + (rr * Math.sin(rad)).toFloat()

            val c = colorOf(s.constellation)
            val snr = (s.cn0 / 45f).coerceIn(0.25f, 1f)

            if (s.used) {
                // 参与定位：实心 + 光晕
                paint.style = Paint.Style.FILL
                paint.color = Ui.alphaOf(c, 0.22f * snr + 0.08f)
                canvas.drawCircle(x, y, r * 0.085f, paint)

                paint.color = c
                canvas.drawCircle(x, y, r * 0.030f, paint)
            } else {
                // 仅收到信号：空心
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.012f
                paint.color = Ui.alphaOf(c, 0.55f)
                canvas.drawCircle(x, y, r * 0.026f, paint)
            }

            // 编号（仅在有空间时画，避免糊成一团）
            if (sats.size <= 24) {
                textPaint.color = Ui.alphaOf(c, if (s.used) 0.95f else 0.6f)
                textPaint.textSize = r * 0.062f
                canvas.drawText(s.svid.toString(), x, y - r * 0.045f, textPaint)
            }
        }
    }

    /** 地球模式：径向渐变球体 + 抽象陆地 + 经纬弧 */
    private fun drawGlobe(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val r2 = r * 0.96f

        // 太空背景光晕
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, r2 * 1.35f,
            intArrayOf(0xFF123049.toInt(), 0xFF0A1520.toInt(), 0xFF070B10.toInt()),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r2 * 1.3f, paint)
        paint.shader = null

        // 球体
        globePaint.shader = RadialGradient(
            cx, cy, r2,
            intArrayOf(0xFF2A6FA8.toInt(), 0xFF14456E.toInt(), 0xFF08243C.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r2, globePaint)
        globePaint.shader = null

        // 抽象陆地（几块柔和色斑，示意用，不追求地理准确）
        landPaint.style = Paint.Style.FILL
        landPaint.color = 0x552E8B57
        drawBlob(canvas, cx - r2 * 0.30f, cy - r2 * 0.18f, r2 * 0.46f, landPaint)
        drawBlob(canvas, cx + r2 * 0.10f, cy + r2 * 0.16f, r2 * 0.40f, landPaint)
        landPaint.color = 0x44508B5A
        drawBlob(canvas, cx + r2 * 0.36f, cy - r2 * 0.30f, r2 * 0.26f, landPaint)

        // 经纬网格
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r2 * 0.005f
        paint.color = 0x33A8D8FF
        for (i in 1..3) {
            canvas.drawCircle(cx, cy, r2 * i / 4f, paint)
        }
        canvas.drawLine(cx - r2, cy, cx + r2, cy, paint)
        canvas.drawLine(cx, cy - r2, cx, cy + r2, paint)

        // 外圈描边
        paint.strokeWidth = r2 * 0.014f
        paint.color = 0x44A8D8FF
        canvas.drawCircle(cx, cy, r2, paint)
    }

    private fun drawBlob(canvas: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = Path()
        path.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.drawPath(path, p)
    }

    /** 天空图模式：极坐标网格 + 方位刻度 */
    private fun drawSkyGrid(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF0C1622.toInt()
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.006f
        paint.color = 0xFF1E2B3A.toInt()
        // 仰角圈：30/60
        canvas.drawCircle(cx, cy, r * 0.667f, paint)
        canvas.drawCircle(cx, cy, r * 0.333f, paint)
        // 十字与斜线
        canvas.drawLine(cx - r, cy, cx + r, cy, paint)
        canvas.drawLine(cx, cy - r, cx, cy + r, paint)
        val d = r * 0.7071f
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint)
        canvas.drawLine(cx - d, cy + d, cx + d, cy - d, paint)

        // 外圈
        paint.strokeWidth = r * 0.014f
        paint.color = 0x55A8D8FF
        canvas.drawCircle(cx, cy, r, paint)

        // 方位标注
        textPaint.color = 0xFF7D8DA3.toInt()
        textPaint.textSize = r * 0.095f
        canvas.drawText("北", cx, cy - r + r * 0.13f, textPaint)
        canvas.drawText("南", cx, cy + r - r * 0.02f, textPaint)
        canvas.drawText("东", cx + r - r * 0.06f, cy + r * 0.035f, textPaint)
        canvas.drawText("西", cx - r + r * 0.06f, cy + r * 0.035f, textPaint)
    }
}

/** SkyView 用的颜色工具（Ui.alpha 在 uikit，这里避免循环依赖直接内联） */
private object Ui {
    fun alphaOf(color: Int, a: Float): Int {
        val al = (a.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(al, Color.red(color), Color.green(color), Color.blue(color))
    }
}
