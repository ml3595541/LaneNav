package com.lobster.lanenav.phone

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
import android.view.WindowManager
import android.widget.TextView

/**
 * 手机端悬浮球：后台运行时一眼看出盒子是否还连着。
 *
 *   绿色 + 「连接」  = 盒子已连上，正在转发定位
 *   红色 + 「未连接」= 没有盒子连接（TCP 与 BLE 都为空）
 *
 * 需要 SYSTEM_ALERT_WINDOW（显示在其他应用上层）。
 * 没有权限时不显示，不影响转发本身。
 */
object FloatingBall {

    private const val TAG = "LaneNavPhone"

    const val COLOR_ON = 0xFF2ED573.toInt()   // 连接：绿
    const val COLOR_OFF = 0xFFFF4757.toInt()  // 未连接：红

    private val main = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var view: TextView? = null
    private var ctxRef: Context? = null

    private var curText = ""
    private var curColor = 0

    fun isShown(): Boolean = view != null

    fun canShow(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

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
                textSize = 12.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.WHITE)
                text = "未连接"
                background = ring(COLOR_OFF, size, app)
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
                x = sp.getInt("phone_ball_x", dp(app, 16f))
                y = sp.getInt("phone_ball_y", dp(app, 160f))
            }

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
                            sp.edit()
                                .putInt("phone_ball_x", p.x)
                                .putInt("phone_ball_y", p.y)
                                .apply()
                        } else {
                            android.widget.Toast.makeText(
                                app,
                                if (curText == "连接") "盒子已连接，正在转发定位"
                                else "没有盒子连接（TCP / BLE 均无）",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        true
                    }
                    else -> false
                }
            }

            view = tv
            curText = "未连接"
            curColor = COLOR_OFF
            runCatching { w.addView(tv, p) }
                .onFailure { Log.w(TAG, "悬浮球添加失败: ${it.message}") }
        }
    }

    fun hide() {
        main.post {
            val v = view ?: return@post
            runCatching { wm?.removeView(v) }
            view = null
            wm = null
            ctxRef = null
        }
    }

    /** 相同状态不重复重绘。 */
    fun update(text: String, color: Int) {
        if (text == curText && color == curColor) return
        curText = text
        curColor = color
        main.post {
            val v = view ?: return@post
            val ctx = ctxRef ?: return@post
            v.text = text
            v.background = ring(color, dp(ctx, 58f), ctx)
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
