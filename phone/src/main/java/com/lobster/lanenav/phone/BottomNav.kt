package com.lobster.lanenav.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.lobster.lanenav.uikit.Ui

/**
 * 底部四标签导航：传输 / 卫星 / 日志 / 设置。
 *
 * 图标用 Canvas 手绘（不引入额外资源），选中态填强调色 + 顶部短横条。
 */
class BottomNav(
    ctx: Context,
    private val accent: Int,
    private val onSelect: (Int) -> Unit
) : LinearLayout(ctx) {

    companion object {
        const val TAB_TRANSFER = 0
        const val TAB_SAT = 1
        const val TAB_LOG = 2
        const val TAB_SETTINGS = 3

        // 0=主页 1=卫星 2=日志 3=齿轮（必须先于 ICONS 声明）
        private const val ICON_HOME = 0
        private const val ICON_SAT = 1
        private const val ICON_LOG = 2
        private const val ICON_GEAR = 3

        private val LABELS = arrayOf("传输", "卫星", "日志", "设置")
        private val ICONS = intArrayOf(ICON_HOME, ICON_SAT, ICON_LOG, ICON_GEAR)
    }

    private var selected = 0
    private val items = mutableListOf<LinearLayout>()
    private val iconViews = mutableListOf<TabIcon>()
    private val labelViews = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        setBackgroundColor(0xFF0A1119.toInt())
        setPadding(0, Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 6f))

        LABELS.forEachIndexed { i, name ->
            val col = LinearLayout(ctx).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, Ui.dp(ctx, 4f), 0, Ui.dp(ctx, 4f))
                setOnClickListener {
                    if (selected != i) {
                        selected = i
                        render()
                        onSelect(i)
                    }
                }
            }

            val icon = TabIcon(ctx, ICONS[i]).apply {
                layoutParams = LayoutParams(Ui.dp(ctx, 24f), Ui.dp(ctx, 24f))
            }
            col.addView(icon)
            col.addView(View(ctx).apply {
                layoutParams = LayoutParams(1, Ui.dp(ctx, 3f))
            })
            val label = TextView(ctx).apply {
                text = name
                textSize = 11f
                gravity = Gravity.CENTER
            }
            col.addView(label)

            items += col
            iconViews += icon
            labelViews += label
            addView(col)
        }
        render()
    }

    fun select(i: Int) {
        if (i == selected) return
        selected = i
        render()
    }

    private fun render() {
        items.forEachIndexed { i, col ->
            val on = i == selected
            val c = if (on) accent else Ui.DIM

            // 选中态：柔和高亮底
            col.background = GradientDrawable().apply {
                cornerRadius = Ui.dp(context, 12f).toFloat()
                setColor(if (on) Ui.alpha(accent, 0.10f) else Color.TRANSPARENT)
            }

            iconViews[i].color = c
            iconViews[i].invalidate()

            labelViews[i].setTextColor(if (on) accent else Ui.MUTED)
            labelViews[i].setTypeface(
                android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    if (on) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL
                )
            )
        }
    }

    /** 手绘图标，24dp 见方。 */
    private class TabIcon(ctx: Context, private val kind: Int) : View(ctx) {

        var color: Int = Color.GRAY

        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val s = minOf(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            p.color = color
            p.strokeWidth = s * 0.085f

            when (kind) {
                ICON_HOME -> {
                    // 房子
                    val w = s * 0.30f
                    val h = s * 0.24f
                    val roofY = cy - s * 0.30f
                    val bodyTop = cy - s * 0.06f
                    val path = Path()
                    path.moveTo(cx - w, bodyTop)
                    path.lineTo(cx, roofY)
                    path.lineTo(cx + w, bodyTop)
                    canvas.drawPath(path, p)
                    val body = Path()
                    body.addRect(
                        cx - w, bodyTop, cx + w, cy + h,
                        Path.Direction.CW
                    )
                    canvas.drawPath(body, p)
                }
                ICON_SAT -> {
                    // 卫星：中心圆 + 两侧翼 + 斜向信号
                    p.style = Paint.Style.STROKE
                    canvas.drawCircle(cx, cy, s * 0.14f, p)
                    canvas.drawLine(
                        cx - s * 0.34f, cy, cx - s * 0.18f, cy, p
                    )
                    canvas.drawLine(
                        cx + s * 0.18f, cy, cx + s * 0.34f, cy, p
                    )
                    canvas.drawLine(
                        cx - s * 0.34f, cy - s * 0.09f,
                        cx - s * 0.34f, cy + s * 0.09f, p
                    )
                    canvas.drawLine(
                        cx + s * 0.34f, cy - s * 0.09f,
                        cx + s * 0.34f, cy + s * 0.09f, p
                    )
                }
                ICON_LOG -> {
                    // 三横线列表
                    p.style = Paint.Style.STROKE
                    for (i in -1..1) {
                        val y = cy + i * s * 0.19f
                        canvas.drawLine(cx - s * 0.28f, y, cx + s * 0.28f, y, p)
                    }
                }
                ICON_GEAR -> {
                    // 齿轮：圆 + 八个齿
                    p.style = Paint.Style.STROKE
                    canvas.drawCircle(cx, cy, s * 0.17f, p)
                    val inner = s * 0.10f
                    val outer = s * 0.30f
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        val cos = Math.cos(a).toFloat()
                        val sin = Math.sin(a).toFloat()
                        canvas.drawLine(
                            cx + cos * inner, cy + sin * inner,
                            cx + cos * outer, cy + sin * outer, p
                        )
                    }
                }
            }
        }
    }
}
