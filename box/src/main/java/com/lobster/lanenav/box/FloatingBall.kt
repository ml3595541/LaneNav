package com.lobster.lanenav.box

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 悬浮球：后台运行时一眼看出当前用的是谁的 GPS。
 *
 *   绿色 + 「手机」= 正在用手机定位
 *   黄色 + 「车机」= 已断开，回落到车机自带 GPS
 *
 * 用 TYPE_APPLICATION_OVERLAY，需要 SYSTEM_ALERT_WINDOW 授权
 * （设置 → 应用 → 显示在其他应用上层）。没有权限时不显示，不影响主功能。
 */
object FloatingBall {

    private const val TAG = "LaneNavBox"

    const val COLOR_PHONE = 0xFF2ED573.toInt()
    const val COLOR_BOX = 0xFFFFB020.toInt()

    private val main = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var ctxRef: Context? = null

    private var curText: String = ""
    private var curColor: Int = 0

    /** 当前是否已挂在窗口上 */
    fun isShown(): Boolean = view != null

    fun canShow(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    /** 显示悬浮球（已显示则忽略）。必须在有悬浮窗权限时调用。 */
    fun show(ctx: Context) {
        main.post {
            if (view != null) return@post
            if (!canShow(ctx)) {
                Log.w(TAG, "无悬浮窗权限，跳过悬浮球")
                return@post
            }
            val app = ctx.applicationContext
            ctxRef = app
            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = w

            val size = dp(app, 58f)
            val tv = TextView(app).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.WHITE)
                text = "车机"
                background = ring(COLOR_BOX, size, app)
            }

            val sp = app.getSharedPreferences("lanenav", Context.MODE_PRIVATE)
            val p = WindowManager.LayoutParams(
                size,
                size,
                if (Build.VERSION.SDK_INT >= 26)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = sp.getInt("ball_x", dp(app, 16f))
                y = sp.getInt("ball_y", dp(app, 120f))
            }

            // 拖动：按下记录起点，移动时更新窗口坐标，松手存位置
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false

            tv.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = p.x; startY = p.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > dp(app, 4f) ||
                            kotlin.math.abs(dy) > dp(app, 4f)
                        ) moved = true
                        p.x = startX + dx
                        p.y = startY + dy
                        runCatching { w.updateViewLayout(tv, p) }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            sp.edit().putInt("ball_x", p.x).putInt("ball_y", p.y).apply()
                        } else {
                            // 轻点：显示一行更详细的状态
                            android.widget.Toast.makeText(
                                app,
                                if (curText == "手机") "正在使用手机 GPS 定位"
                                else "已断开，正在使用车机自带 GPS",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        true
                    }
                    else -> false
                }
            }

            view = tv
            params = p
            curText = "车机"
            curColor = COLOR_BOX
            runCatching { w.addView(tv, p) }
                .onFailure { Log.w(TAG, "悬浮球添加失败: ${it.message}") }
        }
    }

    fun hide() {
        main.post {
            val v = view ?: return@post
            runCatching { wm?.removeView(v) }
            view = null
            params = null
            wm = null
            ctxRef = null
        }
    }

    /**
     * 更新状态。text = 手机 / 车机，color = 对应边框色。
     * 相同状态不会重复重绘。
     */
    fun update(text: String, color: Int) {
        if (text == curText && color == curColor) return
        curText = text
        curColor = color
        main.post {
            val v = view ?: return@post
            val ctx = ctxRef ?: return@post
            val size = dp(ctx, 58f)
            v.text = text
            v.background = ring(color, size, ctx)
        }
    }

    private fun ring(color: Int, size: Int, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE60B1118.toInt())
            setStroke(dp(ctx, 3f), color)
            setSize(size, size)
        }

    private fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
