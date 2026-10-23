package com.lobster.lanenav.uikit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 深色科技风 UI 构件库。纯代码绘制，不依赖 XML 布局。
 *
 * 设计基调：近黑底 + 抬升卡片 + 1px 冷色描边 + 等宽数字。
 * 手机端与盒子端共用，仅强调色不同。
 */
object Ui {

    const val BG = 0xFF070B10.toInt()
    const val CARD = 0xFF0F1620.toInt()
    const val CARD_HI = 0xFF141E2B.toInt()
    const val BORDER = 0xFF1E2B3A.toInt()
    const val TEXT = 0xFFE8EEF5.toInt()
    const val MUTED = 0xFF7D8DA3.toInt()
    const val DIM = 0xFF46566A.toInt()
    const val OK = 0xFF2ED573.toInt()
    const val WARN = 0xFFFFB020.toInt()
    const val ERR = 0xFFFF4757.toInt()

    /** 手机端强调色（红） */
    const val ACCENT_PHONE = 0xFFFF5A4E.toInt()

    /** 盒子端强调色（蓝） */
    const val ACCENT_BOX = 0xFF38A7D5.toInt()

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
        ).toInt()

    private fun rounded(fill: Int, strokeColor: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fill)
            if (strokeColor != 0) setStroke(1, strokeColor)
        }

    // ---------------- 容器 ----------------

    /** 抬升卡片容器。返回内容区，直接往里 addView。
     *  accent 非 0 时在卡片顶部画一条 2dp 强调色，强化科技感。 */
    fun card(ctx: Context, accent: Int = 0): LinearLayout {
        val p = dp(ctx, 16f)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
            background = rounded(CARD, BORDER, dp(ctx, 14f).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12f) }
            if (accent != 0) {
                addView(View(ctx).apply {
                    setBackgroundColor(alpha(accent, 0.85f))
                    layoutParams = LinearLayout.LayoutParams(
                        dp(ctx, 34f), dp(ctx, 2f)
                    ).apply { bottomMargin = dp(ctx, 12f) }
                })
            }
        }
    }

    /** 卡片内的分组小标题（全大写 + 字距）。 */
    fun sectionLabel(ctx: Context, text: String, accent: Int): TextView =
        TextView(ctx).apply {
            this.text = text.uppercase()
            textSize = 10.5f
            setTextColor(accent)
            letterSpacing = 0.16f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 0, 0, dp(ctx, 10f))
        }

    /** 一行「标签 — 值」，值用等宽字体，便于扫读数字。 */
    fun row(ctx: Context, label: String, value: String, valueColor: Int = TEXT): LinearLayout {
        val lp = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 7f) }
        }
        lp.addView(TextView(ctx).apply {
            text = label
            textSize = 12.5f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        lp.addView(TextView(ctx).apply {
            text = value
            textSize = 13f
            setTextColor(valueColor)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        })
        return lp
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(BORDER)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1f)
        ).apply { topMargin = dp(ctx, 8f); bottomMargin = dp(ctx, 10f) }
    }

    fun spacer(ctx: Context, h: Float): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h))
    }

    // ---------------- 文本 ----------------

    fun mono(ctx: Context, text: String, size: Float = 12.5f, color: Int = TEXT): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(ctx, 3f).toFloat(), 1f)
        }

    fun body(ctx: Context, text: String, color: Int = MUTED): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(color)
            setLineSpacing(dp(ctx, 3f).toFloat(), 1f)
        }

    // ---------------- 控件 ----------------

    /** 主按钮：实心强调色 + 深色文字。 */
    fun primaryButton(ctx: Context, text: String, accent: Int, onClick: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setTextColor(0xFF04070A.toInt())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    rounded(blend(accent, 0.75f), 0, dp(ctx, 12f).toFloat())
                )
                addState(intArrayOf(), rounded(accent, 0, dp(ctx, 12f).toFloat()))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 46f), 1f).apply {
                marginEnd = dp(ctx, 10f)
            }
            setOnClickListener { onClick() }
        }

    /** 次按钮：描边透明。 */
    fun ghostButton(ctx: Context, text: String, accent: Int, onClick: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setTextColor(accent)
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    rounded(alpha(0xFFFFFFFF.toInt(), 0.10f), accent, dp(ctx, 12f).toFloat())
                )
                addState(
                    intArrayOf(),
                    rounded(Color.TRANSPARENT, alpha(accent, 0.6f), dp(ctx, 12f).toFloat())
                )
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 46f), 1f)
            setOnClickListener { onClick() }
        }

    fun field(ctx: Context, hint: String, value: String, numeric: Boolean = false): EditText =
        EditText(ctx).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            setTextColor(TEXT)
            setHintTextColor(DIM)
            typeface = Typeface.MONOSPACE
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
            else InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
            background = rounded(CARD_HI, BORDER, dp(ctx, 10f).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 44f)
            ).apply { bottomMargin = dp(ctx, 10f) }
        }

    fun checkbox(ctx: Context, text: String, checked: Boolean, accent: Int): CheckBox =
        CheckBox(ctx).apply {
            this.text = text
            this.isChecked = checked
            textSize = 13f
            setTextColor(TEXT)
            buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(accent, DIM)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 4f) }
        }

    /** 状态指示灯。 */
    fun dot(ctx: Context, color: Int, sizeDp: Float = 9f): View = View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp)).apply {
            marginEnd = dp(ctx, 8f)
        }
    }

    /** 颜色按比例变暗。 */
    fun blend(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    fun alpha(color: Int, a: Float): Int {
        val al = (a * 255).toInt().coerceIn(0, 255)
        return Color.argb(al, Color.red(color), Color.green(color), Color.blue(color))
    }
}

/**
 * 分段选择器（通道切换）。等宽字体，选中态半透明填充 + 描边。
 */
class Segmented(
    ctx: Context,
    items: List<String>,
    private val accent: Int,
    initial: Int,
    private val onChange: (Int) -> Unit
) : LinearLayout(ctx) {

    var selected: Int = initial
        private set

    private val labels = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Ui.dp(ctx, 11f).toFloat()
            setColor(Ui.CARD_HI)
            setStroke(1, Ui.BORDER)
        }
        setPadding(Ui.dp(ctx, 4f), Ui.dp(ctx, 4f), Ui.dp(ctx, 4f), Ui.dp(ctx, 4f))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Ui.dp(ctx, 12f) }

        items.forEachIndexed { i, name ->
            val tv = TextView(ctx).apply {
                text = name
                textSize = 12.5f
                gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(ctx, 38f), 1f)
                setOnClickListener {
                    if (selected != i) {
                        selected = i
                        render()
                        onChange(i)
                    }
                }
            }
            labels += tv
            addView(tv)
        }
        render()
    }

    fun setSelected(i: Int) {
        if (i == selected) return
        selected = i
        render()
    }

    private fun render() {
        labels.forEachIndexed { i, tv ->
            val on = i == selected
            tv.setTextColor(if (on) accent else Ui.MUTED)
            tv.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(context, 8f).toFloat()
                setColor(if (on) Ui.alpha(accent, 0.14f) else Color.TRANSPARENT)
                if (on) setStroke(1, Ui.alpha(accent, 0.55f))
            }
        }
    }
}
