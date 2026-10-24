package com.lobster.lanenav.phone

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lobster.lanenav.uikit.PermCheck
import com.lobster.lanenav.uikit.Segmented
import com.lobster.lanenav.uikit.Ui

/**
 * 手机端界面：深色科技风，底部四标签。
 *
 * 传输 / 卫星 / 日志 / 设置
 * 强调色 = 红（ACCENT_PHONE），与图标一致。
 */
class MainActivity : AppCompatActivity() {

    private val accent get() = Ui.ACCENT_PHONE

    // ---------- 传输页 ----------
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var metricIp: TextView
    private lateinit var metricPort: TextView
    private lateinit var metricTcp: TextView
    private lateinit var metricBle: TextView
    private lateinit var metricRtt: TextView
    private lateinit var metricHeading: TextView
    private lateinit var metricFix: TextView
    private lateinit var warnText: TextView

    // ---------- 卫星页 ----------
    private lateinit var satStateText: TextView       // 右上「尚未采集 / 已采集」
    private lateinit var satUsed: TextView            // 可用 / 搜索
    private lateinit var satAcc: TextView             // 精度
    private lateinit var skyView: SkyView
    private lateinit var satCellBeidou: TextView
    private lateinit var satCellGps: TextView
    private lateinit var satCellGlo: TextView
    private lateinit var satCellGal: TextView
    private lateinit var satHint: TextView
    private lateinit var satDetailBox: LinearLayout
    private var onlyUsed = false
    private var lastSatSig = ""

    // ---------- 日志页 ----------
    private lateinit var logBody: TextView
    private lateinit var logEmpty: TextView
    private var lastLogVersion = -1

    // ---------- 设置页 · 权限自检 ----------
    private var permBox: LinearLayout? = null
    private var permSummary: TextView? = null
    private var lastPermSig = ""

    /** 权限申请期间暂存「授予后要做什么」 */
    private var pendingAction: (() -> Unit)? = null

    // ---------- 页面容器 ----------
    private lateinit var content: FrameLayout
    private lateinit var nav: BottomNav
    private val pages = arrayOfNulls<View>(4)
    private var tab = 0

    private var running = false
    private var autoStartOnOpen = true
    private var autoStartPending = false
    private var userStopped = false

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val sp = getSharedPreferences("lanenav", MODE_PRIVATE)
        autoStartOnOpen = sp.getBoolean("auto_start_on_open", true)
        autoStartPending = autoStartOnOpen

        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        nav = BottomNav(this, accent) { idx -> showTab(idx) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(content)
            // 顶部细分隔线
            addView(View(this@MainActivity).apply {
                setBackgroundColor(Ui.BORDER)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
            })
            addView(
                nav,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        })

        showTab(BottomNav.TAB_TRANSFER)
    }

    // ==================== 页面切换 ====================

    private fun showTab(i: Int) {
        tab = i
        nav.select(i)
        content.removeAllViews()
        val page = pages[i] ?: when (i) {
            BottomNav.TAB_SAT -> buildSatPage()
            BottomNav.TAB_LOG -> buildLogPage()
            BottomNav.TAB_SETTINGS -> buildSettingsPage()
            else -> buildTransferPage()
        }.also { pages[i] = it }
        content.addView(page)

        // 切页时立即刷新一次，避免看到过期数据
        when (i) {
            BottomNav.TAB_SAT -> refreshSat()
            BottomNav.TAB_LOG -> refreshLog(force = true)
            BottomNav.TAB_SETTINGS -> {
                lastPermSig = ""
                refreshPermsIfChanged()
            }
        }
        refresh()
    }

    private fun pageScroll(root: LinearLayout): ScrollView = ScrollView(this).apply {
        setBackgroundColor(Ui.BG)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        addView(root)
    }

    private fun pageRoot(): LinearLayout {
        val pad = Ui.dp(this, 16f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Ui.BG)
        }
    }

    // ==================== 传输页 ====================

    private fun buildTransferPage(): View {
        val ctx = this
        val root = pageRoot()

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(ctx, 16f) }
        }
        header.addView(TextView(ctx).apply {
            text = "传输"
            textSize = 24f
            setTextColor(Ui.TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(ctx).apply {
            text = "PHONE"
            textSize = 10f
            setTextColor(accent)
            letterSpacing = 0.18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
        root.addView(header)

        // ---------- 状态卡 ----------
        val statusCard = Ui.card(ctx, accent).apply {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = Ui.dp(ctx, 12f) }
        }
        val statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = Ui.dot(ctx, Ui.DIM, 10f)
        statusRow.addView(statusDot)

        val statusCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusTitle = TextView(ctx).apply {
            text = "未启动"
            textSize = 17f
            setTextColor(Ui.TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }
        statusSub = TextView(ctx).apply {
            text = "点击下方按钮开始转发"
            textSize = 11.5f
            setTextColor(Ui.MUTED)
            typeface = Typeface.MONOSPACE
        }
        statusCol.addView(statusTitle)
        statusCol.addView(statusSub)
        statusRow.addView(statusCol)
        statusCard.addView(statusRow)

        statusCard.addView(Ui.divider(ctx))

        val grid1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        metricIp = metricCell(grid1, "本机 IP")
        metricPort = metricCell(grid1, "监听端口")
        statusCard.addView(grid1)

        val grid2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 10f) }
        }
        metricTcp = metricCell(grid2, "TCP 连接")
        metricBle = metricCell(grid2, "BLE 连接")
        statusCard.addView(grid2)

        val grid3 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 10f) }
        }
        metricRtt = metricCell(grid3, "链路延时")
        metricHeading = metricCell(grid3, "航向")
        statusCard.addView(grid3)

        statusCard.addView(Ui.spacer(ctx, 10f))
        metricFix = TextView(ctx).apply {
            text = "最近定位：—"
            textSize = 11.5f
            setTextColor(Ui.MUTED)
            typeface = Typeface.MONOSPACE
        }
        statusCard.addView(metricFix)

        warnText = TextView(ctx).apply {
            textSize = 11.5f
            setTextColor(Ui.ERR)
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setPadding(0, Ui.dp(ctx, 8f), 0, 0)
        }
        statusCard.addView(warnText)
        root.addView(statusCard)

        // ---------- 传输通道 ----------
        val chanCard = Ui.card(ctx)
        chanCard.addView(Ui.sectionLabel(ctx, "传输通道", Ui.MUTED))

        val sp = getSharedPreferences("lanenav", MODE_PRIVATE)
        val curChannel = sp.getString("channel", "both") ?: "both"
        val chanIndex = when (curChannel) {
            "ble" -> 0
            "tcp" -> 1
            else -> 2
        }
        val seg = Segmented(ctx, listOf("蓝牙 BLE", "WiFi TCP", "双通道"), accent, chanIndex) { idx ->
            val ch = when (idx) {
                0 -> "ble"
                1 -> "tcp"
                else -> "both"
            }
            sp.edit().putString("channel", ch).apply()
            LogStore.add("CFG", "通道切换为 $ch")
            if (running) {
                stopRelay()
                ui.postDelayed({ startRelay() }, 500)
            }
        }
        chanCard.addView(seg)
        chanCard.addView(Ui.body(ctx, "切换后自动重启服务。BLE 不需要开热点，功耗更低。"))
        root.addView(chanCard)

        // ---------- 操作按钮 ----------
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = Ui.dp(ctx, 4f)
                bottomMargin = Ui.dp(ctx, 16f)
            }
        }
        btnRow.addView(Ui.primaryButton(ctx, "启动转发", accent) {
            LogStore.add("UI", "点击启动转发")
            requestPermissionsThen { startRelay() }
        })
        btnRow.addView(Ui.ghostButton(ctx, "停止", accent) {
            LogStore.add("UI", "点击停止")
            stopRelay()
        })
        root.addView(btnRow)

        root.addView(Ui.spacer(ctx, 8f))
        return pageScroll(root)
    }

    // ==================== 卫星页 ====================

    private fun buildSatPage(): View {
        val ctx = this
        val root = pageRoot()

        // 标题行
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = "卫星状态"
                textSize = 24f
                setTextColor(Ui.TEXT)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
            addView(TextView(ctx).apply {
                text = "GNSS / 实时信号"
                textSize = 12.5f
                setTextColor(Ui.MUTED)
                typeface = Typeface.MONOSPACE
            })
        })

        // 右上角数据块
        val rightCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 14f), Ui.dp(ctx, 8f), Ui.dp(ctx, 14f), Ui.dp(ctx, 8f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(ctx, 12f).toFloat()
                setColor(Ui.CARD)
                setStroke(1, Ui.BORDER)
            }
        }
        satStateText = TextView(ctx).apply {
            text = "尚未采集"
            textSize = 11f
            setTextColor(Ui.MUTED)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(ctx, 4f) }
        }
        rightCard.addView(satStateText)

        val usedRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        satUsed = TextView(ctx).apply {
            text = "— / —"
            textSize = 20f
            setTextColor(Ui.TEXT)
            typeface = Typeface.MONOSPACE
        }
        usedRow.addView(satUsed)
        rightCard.addView(usedRow)

        rightCard.addView(TextView(ctx).apply {
            text = "可用 / 搜索   本机 GNSS"
            textSize = 9.5f
            setTextColor(Ui.DIM)
            typeface = Typeface.MONOSPACE
        })
        header.addView(rightCard)
        root.addView(header)

        // 精度行
        satAcc = TextView(ctx).apply {
            text = "精度 —"
            textSize = 13f
            setTextColor(Ui.OK)
            typeface = Typeface.MONOSPACE
            setPadding(0, Ui.dp(ctx, 12f), 0, Ui.dp(ctx, 8f))
        }
        root.addView(satAcc)

        // 图例
        val legend = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(ctx, 8f) }
        }
        listOf(
            "北斗" to 0xFFFFB020.toInt(),
            "GPS" to 0xFF38A7D5.toInt(),
            "GLONASS" to 0xFFA78BFA.toInt(),
            "Galileo" to 0xFF2ED573.toInt()
        ).forEach { (name, color) ->
            legend.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                addView(View(ctx).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(color)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        Ui.dp(ctx, 7f), Ui.dp(ctx, 7f)
                    ).apply { marginEnd = Ui.dp(ctx, 5f) }
                })
                addView(TextView(ctx).apply {
                    text = name
                    textSize = 10.5f
                    setTextColor(Ui.MUTED)
                })
            })
        }
        root.addView(legend)

        // 球体 / 天空图卡片
        val skyCard = Ui.card(ctx).apply {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = Ui.dp(ctx, 10f) }
        }
        skyView = SkyView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        skyCard.addView(skyView)

        val modeSeg = Segmented(ctx, listOf("🌍  地球", "◎  天空图"), accent, 0) { idx ->
            skyView.mode = if (idx == 0) SkyView.Mode.GLOBE else SkyView.Mode.SKY
        }
        modeSeg.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(ctx, 4f) }
        skyCard.addView(modeSeg)

        skyCard.addView(TextView(ctx).apply {
            text = if (true) "地球示意 · 北朝上 · 实测方位/仰角" else ""
            textSize = 10.5f
            setTextColor(Ui.DIM)
            typeface = Typeface.MONOSPACE
        })
        root.addView(skyCard)

        // 四星座 2x2
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        satCellBeidou = satCell(row1, "北斗", 0xFFFFB020.toInt())
        satCellGps = satCell(row1, "GPS", 0xFF38A7D5.toInt())
        root.addView(row1)

        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 8f) }
        }
        satCellGlo = satCell(row2, "GLO", 0xFFA78BFA.toInt())
        satCellGal = satCell(row2, "GAL", 0xFF2ED573.toInt())
        root.addView(row2)

        satHint = TextView(ctx).apply {
            text = "共享尚未启动，启动「共享 GPS」后显示本机卫星。"
            textSize = 11f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(ctx, 12f), 0, Ui.dp(ctx, 4f))
        }
        root.addView(satHint)

        // 卫星明细
        val detailCard = Ui.card(ctx)
        val detailHead = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        detailHead.addView(TextView(ctx).apply {
            text = "卫星明细"
            textSize = 16f
            setTextColor(Ui.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        detailHead.addView(TextView(ctx).apply {
            text = "信号强度 ↓"
            textSize = 11f
            setTextColor(Ui.MUTED)
            typeface = Typeface.MONOSPACE
        })
        detailCard.addView(detailHead)

        val onlyUsedRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 8f), 0, Ui.dp(ctx, 4f))
        }
        onlyUsedRow.addView(TextView(ctx).apply {
            text = "仅看可用"
            textSize = 12f
            setTextColor(Ui.MUTED)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = android.widget.Switch(ctx).apply {
            isChecked = onlyUsed
            setOnCheckedChangeListener { _, checked ->
                onlyUsed = checked
                lastSatSig = ""
                refreshSat()
            }
        }
        onlyUsedRow.addView(sw)
        detailCard.addView(onlyUsedRow)

        detailCard.addView(TextView(ctx).apply {
            text = "等待报告 · 实心参与定位，空心未参与"
            textSize = 10f
            setTextColor(Ui.DIM)
            setPadding(0, 0, 0, Ui.dp(ctx, 6f))
        })

        satDetailBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        detailCard.addView(satDetailBox)
        root.addView(detailCard)

        root.addView(Ui.spacer(ctx, 16f))
        return pageScroll(root)
    }

    /** 星座四宫格单元。 */
    private fun satCell(container: LinearLayout, name: String, color: Int): TextView {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = Ui.dp(this@MainActivity, 8f) }
            setPadding(Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 10f),
                Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 10f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(this@MainActivity, 12f).toFloat()
                setColor(Ui.CARD)
                setStroke(1, Ui.BORDER)
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(
                Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 8f)
            ).apply { marginEnd = Ui.dp(this@MainActivity, 6f) }
        })
        head.addView(TextView(this).apply {
            text = name
            textSize = 12f
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        cell.addView(head)

        val value = TextView(this).apply {
            text = "— / —"
            textSize = 17f
            setTextColor(Ui.TEXT)
            typeface = Typeface.MONOSPACE
            setPadding(0, Ui.dp(this@MainActivity, 4f), 0, 0)
        }
        cell.addView(value)

        cell.addView(TextView(this).apply {
            text = "可用 / 搜索"
            textSize = 9.5f
            setTextColor(Ui.DIM)
            typeface = Typeface.MONOSPACE
        })
        container.addView(cell)
        return value
    }

    // ==================== 日志页 ====================

    private fun buildLogPage(): View {
        val ctx = this
        val root = pageRoot()

        root.addView(TextView(ctx).apply {
            text = "运行日志"
            textSize = 24f
            setTextColor(Ui.TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        root.addView(TextView(ctx).apply {
            text = "连接、定位与错误记录"
            textSize = 12.5f
            setTextColor(Ui.MUTED)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, Ui.dp(ctx, 14f))
        })

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(ctx, 12f) }
        }
        btnRow.addView(textButton("复制日志", accent) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("lanenav-log", LogStore.text()))
            LogStore.add("UI", "日志已复制 (${LogStore.size()} 条)")
            Toast.makeText(this, "已复制 ${LogStore.size()} 条日志", Toast.LENGTH_SHORT).show()
            refreshLog(force = true)
        })
        btnRow.addView(textButton("分享日志", accent) {
            val text = LogStore.text()
            if (text.isBlank()) {
                Toast.makeText(this, "暂无日志可分享", Toast.LENGTH_SHORT).show()
            } else {
                runCatching {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                                putExtra(Intent.EXTRA_SUBJECT, "LaneNav 运行日志")
                            },
                            "分享日志"
                        )
                    )
                }
            }
        })
        root.addView(btnRow)

        logEmpty = TextView(ctx).apply {
            text = "暂无日志。启动传输后可在这里查看状态。"
            textSize = 12.5f
            setTextColor(Ui.MUTED)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 26f), Ui.dp(ctx, 16f), Ui.dp(ctx, 26f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(ctx, 14f).toFloat()
                setColor(Ui.CARD)
                setStroke(1, Ui.BORDER)
            }
        }
        root.addView(logEmpty)

        val logCard = Ui.card(ctx).apply { visibility = View.GONE }
        logBody = TextView(ctx).apply {
            textSize = 11.5f
            setTextColor(Ui.TEXT)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(Ui.dp(ctx, 3f).toFloat(), 1f)
        }
        logCard.addView(logBody)
        root.addView(logCard)
        logCard.tag = "logCard"

        root.addView(TextView(ctx).apply {
            text = "日志只保留在本次应用进程中。分享前请检查其中的设备地址和位置信息。"
            textSize = 10.5f
            setTextColor(Ui.DIM)
            setPadding(0, Ui.dp(ctx, 14f), 0, 0)
        })

        root.addView(Ui.spacer(ctx, 16f))
        return pageScroll(root)
    }

    private fun textButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13.5f
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 16f),
                Ui.dp(this@MainActivity, 10f))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = Ui.dp(this@MainActivity, 4f) }
        }

    // ==================== 设置页 ====================

    private fun buildSettingsPage(): View {
        val ctx = this
        val root = pageRoot()

        root.addView(TextView(ctx).apply {
            text = "设置"
            textSize = 24f
            setTextColor(Ui.TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, Ui.dp(ctx, 14f))
        })

        // ---------- 权限自检 ----------
        val permCard = Ui.card(ctx, accent)
        val permHead = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        permHead.addView(TextView(ctx).apply {
            text = "权限自检"
            textSize = 15f
            setTextColor(Ui.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        permSummary = TextView(ctx).apply {
            text = "检查中…"
            textSize = 11f
            setTextColor(Ui.MUTED)
            typeface = Typeface.MONOSPACE
        }
        permHead.addView(permSummary)
        permCard.addView(permHead)

        permCard.addView(Ui.body(ctx, "逐项核对本机是否真的拿到了所需权限。"))
        permCard.addView(Ui.divider(ctx))

        permBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        permCard.addView(permBox)

        // 按钮：申请缺失的运行时权限 / 跳系统设置
        val permBtnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 10f) }
        }
        permBtnRow.addView(Ui.primaryButton(ctx, "申请缺失权限", accent) {
            requestMissingPermissions()
        })
        permBtnRow.addView(Ui.ghostButton(ctx, "系统设置", accent) {
            openAppSettings()
        })
        permCard.addView(permBtnRow)
        root.addView(permCard)

        // 启动行为
        val autoCard = Ui.card(ctx)
        autoCard.addView(Ui.sectionLabel(ctx, "启动行为", Ui.MUTED))
        val autoBox = Ui.checkbox(ctx, "打开应用自动启动转发", autoStartOnOpen, accent)
        autoBox.setOnCheckedChangeListener { _, checked ->
            autoStartOnOpen = checked
            getSharedPreferences("lanenav", MODE_PRIVATE).edit()
                .putBoolean("auto_start_on_open", checked).apply()
            LogStore.add("CFG", if (checked) "已开启自动启动" else "已关闭自动启动")
        }
        autoCard.addView(autoBox)
        autoCard.addView(
            Ui.body(
                ctx,
                "开启后，每次打开本应用会自动开始转发（权限已授予时立即生效）。" +
                    "若需要临时关闭转发，请点「停止」，本次不会自动重启。"
            )
        )
        root.addView(autoCard)

        // 必设清单
        val helpCard = Ui.card(ctx)
        helpCard.addView(Ui.sectionLabel(ctx, "手机必设，否则会出现断流", Ui.WARN))
        helpCard.addView(
            Ui.body(
                ctx,
                "1. 权限 → 定位 → 选「始终允许」\n" +
                    "2. 应用信息 → 省电策略 → 无限制\n" +
                    "3. 应用信息 → 自启动 → 允许\n" +
                    "4. 最近任务里下拉本应用 → 加锁\n" +
                    "5. 开发者选项 → 关闭「锁屏时清除内存」",
                Ui.TEXT
            )
        )
        helpCard.addView(Ui.divider(ctx))
        helpCard.addView(Ui.sectionLabel(ctx, "使用", Ui.MUTED))
        helpCard.addView(
            Ui.body(
                ctx,
                "· WiFi TCP：手机开热点，盒子连该热点，在盒子端填本机 IP\n" +
                    "· 蓝牙 BLE：两端蓝牙打开即可，无需热点\n" +
                    "· 双通道：两者同时启用，互为备份"
            )
        )
        root.addView(helpCard)

        // 关于
        val aboutCard = Ui.card(ctx)
        aboutCard.addView(Ui.sectionLabel(ctx, "关于", Ui.MUTED))
        aboutCard.addView(Ui.row(ctx, "版本", versionName()))
        aboutCard.addView(Ui.row(ctx, "机型", Build.MODEL))
        aboutCard.addView(Ui.row(ctx, "系统", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
        aboutCard.addView(Ui.divider(ctx))
        aboutCard.addView(Ui.row(ctx, "日志条数", "${LogStore.size()} / 500"))
        val clearBtn = Ui.ghostButton(ctx, "清空日志", accent) {
            LogStore.clear()
            lastLogVersion = -1
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }
        clearBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 42f)
        )
        aboutCard.addView(clearBtn)
        root.addView(aboutCard)

        // 作者
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 4f) }
        }
        footer.addView(TextView(ctx).apply {
            text = "作者微信：SnownyC"
            textSize = 12f
            setTextColor(Ui.DIM)
            typeface = Typeface.MONOSPACE
            setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 8f), Ui.dp(ctx, 10f), Ui.dp(ctx, 8f))
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("wechat", "SnownyC"))
                Toast.makeText(this@MainActivity, "微信号已复制", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(footer)

        root.addView(Ui.spacer(ctx, 20f))
        return pageScroll(root)
    }

    /** 渲染权限清单。 */
    private fun refreshPermsNow() {
        val items = PermCheck.phone(this)
        val box = permBox ?: return
        box.removeAllViews()

        val missing = PermCheck.missingCritical(items)
        val soft = items.count { !it.ok && !it.critical }
        permSummary?.text = when {
            PermCheck.allOk(items) -> "全部通过"
            missing > 0 -> "缺 $missing 项关键"
            else -> "$soft 项建议调整"
        }
        permSummary?.setTextColor(
            when {
                PermCheck.allOk(items) -> Ui.OK
                missing > 0 -> Ui.ERR
                else -> Ui.WARN
            }
        )

        for (it in items) {
            box.addView(permRow(it))
        }
    }

    private fun permRow(item: PermCheck.Item): LinearLayout {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 7f), 0, Ui.dp(ctx, 7f))
        }

        val dotColor = when {
            item.ok -> Ui.OK
            item.critical -> Ui.ERR
            else -> Ui.WARN
        }
        row.addView(View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(dotColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                Ui.dp(ctx, 8f), Ui.dp(ctx, 8f)
            ).apply { marginEnd = Ui.dp(ctx, 10f) }
        })

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = item.title
            textSize = 13f
            setTextColor(if (item.ok) Ui.TEXT else (if (item.critical) Ui.ERR else Ui.WARN))
        })
        col.addView(TextView(ctx).apply {
            text = item.detail
            textSize = 10.5f
            setTextColor(Ui.MUTED)
        })
        row.addView(col)

        row.addView(TextView(ctx).apply {
            text = if (item.ok) "✓" else "✗"
            textSize = 15f
            setTextColor(dotColor)
            typeface = Typeface.MONOSPACE
        })

        // 未通过的项可点击，直接跳到能解决它的系统页面
        if (!item.ok) {
            row.addView(TextView(ctx).apply {
                text = " ›"
                textSize = 17f
                setTextColor(Ui.MUTED)
                typeface = Typeface.MONOSPACE
            })
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { openPermTarget(item.id) }
            // 点击反馈
            row.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Ui.alpha(accent, 0.12f)),
                null, null
            )
        }
        return row
    }

    /** 点击权限项：跳到能解决该项的系统页面。 */
    private fun openPermTarget(id: String) {
        when (id) {
            // 运行时权限：直接弹窗
            "fine", "btadv", "btconn" -> {
                requestPermissionsThen {
                    lastPermSig = ""
                    refreshPermsNow()
                }
            }
            // 后台定位：Android 11+ 不能单独弹窗，必须去应用详情页选「始终允许」
            "bg" -> {
                Toast.makeText(
                    this,
                    "请在「权限 → 位置信息」中选择「始终允许」",
                    Toast.LENGTH_LONG
                ).show()
                openAppSettings()
            }
            // 系统定位总开关
            "locsvc" -> {
                val ok = runCatching {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }.isSuccess
                if (!ok) openAppSettings()
            }
            // 通知设置
            "noti" -> {
                val ok = runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                }.isSuccess
                if (!ok) openAppSettings()
            }
            // 电池优化
            "battery" -> openBatterySettings()
            // 兜底：应用详情页
            else -> openAppSettings()
        }
    }

    private fun openBatterySettings() {
        // 优先直接弹系统对话框
        val direct = runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.isSuccess
        if (!direct) {
            // 兜底：跳电池优化列表，用户自己找本应用
            val listed = runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.isSuccess
            if (!listed) openAppSettings()
        }
    }

    /** 只在状态变化时重建，避免每秒刷 View。 */
    private fun refreshPermsIfChanged() {
        if (permBox == null) return
        val items = PermCheck.phone(this)
        val sig = items.joinToString(",") { "${it.id}=${it.ok}" }
        if (sig == lastPermSig) return
        lastPermSig = sig
        refreshPermsNow()
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** 指标格：上方小标签，下方大数字。 */
    private fun metricCell(container: LinearLayout, label: String): TextView {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        cell.addView(TextView(this).apply {
            text = label
            textSize = 10f
            setTextColor(Ui.DIM)
            letterSpacing = 0.1f
            typeface = Typeface.MONOSPACE
        })
        val value = TextView(this).apply {
            text = "—"
            textSize = 15f
            setTextColor(Ui.TEXT)
            typeface = Typeface.MONOSPACE
        }
        cell.addView(value)
        container.addView(cell)
        return value
    }

    // ==================== 生命周期 ====================

    override fun onResume() {
        super.onResume()
        running = LocationRelayService.isRunning
        lastPermSig = ""
        refresh()

        if (autoStartPending && !userStopped && !LocationRelayService.isRunning) {
            autoStartPending = false
            requestPermissionsThen { startRelay() }
        }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
    }

    private fun startRelay() {
        ContextCompat.startForegroundService(
            this, Intent(this, LocationRelayService::class.java)
        )
        running = true
        ui.postDelayed({ refresh() }, 700)
    }

    private fun stopRelay() {
        stopService(Intent(this, LocationRelayService::class.java))
        running = false
        userStopped = true
        autoStartPending = false
        refresh()
    }

    // ==================== 刷新 ====================

    private fun refresh() {
        if (pages[BottomNav.TAB_TRANSFER] != null) refreshTransfer()
        if (pages[BottomNav.TAB_SAT] != null) refreshSat()
        if (pages[BottomNav.TAB_LOG] != null) refreshLog(force = false)
        refreshPermsIfChanged()

        ui.postDelayed({ if (!isFinishing) refresh() }, 1000)
    }

    private fun refreshTransfer() {
        if (!running) {
            statusDot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Ui.DIM)
            }
            statusTitle.text = "未启动"
            statusTitle.setTextColor(Ui.TEXT)
            statusSub.text = "点击「启动转发」开始"
            metricIp.text = NetUtils.localIpv4()
            metricPort.text = "19800"
            metricTcp.text = "0"
            metricBle.text = "0"
            metricRtt.text = "—"
            metricHeading.text = "—"
            metricFix.text = "最近定位：—"
            warnText.visibility = View.GONE
            return
        }

        val tcp = LocationRelayService.clientCount
        val ble = LocationRelayService.bleClientCount
        val active = tcp + ble > 0

        statusDot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (active) Ui.OK else Ui.WARN)
        }
        statusTitle.text = if (active) "转发中" else "等待连接"
        statusTitle.setTextColor(if (active) Ui.OK else Ui.TEXT)
        statusSub.text = when {
            tcp > 0 && ble > 0 -> "双通道在线"
            ble > 0 -> "BLE 在线"
            tcp > 0 -> "TCP 在线"
            else -> "服务运行中"
        }

        metricIp.text = NetUtils.localIpv4()
        metricPort.text = "19800"
        metricTcp.text = tcp.toString()
        metricBle.text = ble.toString()
        val rtt = LocationRelayService.lastRtt
        metricRtt.text = if (rtt > 0) "${rtt}ms" else "—"
        metricHeading.text = LocationRelayService.lastHeadingSummary
        metricFix.text = "最近定位：${LocationRelayService.lastFixSummary}"

        val err = LocationRelayService.bleError
        if (err != null) {
            warnText.text = "⚠ BLE：$err"
            warnText.visibility = View.VISIBLE
        } else {
            warnText.visibility = View.GONE
        }
    }

    private fun refreshSat() {
        val fresh = GnssStore.fresh()
        val sats = GnssStore.sats
        val used = GnssStore.usedTotal()
        val vis = GnssStore.visibleTotal()

        satStateText.text = if (fresh) "已采集" else "尚未采集"
        satStateText.setTextColor(if (fresh) Ui.OK else Ui.MUTED)
        satUsed.text = if (fresh) "$used / $vis" else "— / —"

        val acc = LocationRelayService.lastAccuracy
        satAcc.text = if (running && acc > 0) {
            "精度 ${String.format("%.1f", acc)} m"
        } else if (!running) {
            "精度 —    共享尚未启动"
        } else {
            "精度 —    等待定位"
        }
        satAcc.setTextColor(if (running && acc > 0) Ui.OK else Ui.MUTED)

        val bd = GnssStore.countOf("北斗")
        val gp = GnssStore.countOf("GPS")
        val gl = GnssStore.countOf("GLO")
        val ga = GnssStore.countOf("GAL")
        satCellBeidou.text = if (fresh) "${bd.first} / ${bd.second}" else "— / —"
        satCellGps.text = if (fresh) "${gp.first} / ${gp.second}" else "— / —"
        satCellGlo.text = if (fresh) "${gl.first} / ${gl.second}" else "— / —"
        satCellGal.text = if (fresh) "${ga.first} / ${ga.second}" else "— / —"

        satHint.text = when {
            !running -> "共享尚未启动，启动「共享 GPS」后显示本机卫星。"
            !fresh -> "已启动，正在搜索卫星…"
            else -> "卫星数据实时更新中（${vis} 颗可见，${used} 颗参与定位）。"
        }

        skyView.emptyHint = if (!running) "启动共享后采集卫星" else "搜索卫星中…"
        skyView.invalidate()

        // 明细列表：数据变了才重建，避免每秒抖动
        val sig = if (!fresh) "none" else sats.sortedByDescending { it.cn0 }
            .joinToString("|") { "${it.label},${it.cn0},${it.used}" } + "|u=$onlyUsed"
        if (sig != lastSatSig) {
            lastSatSig = sig
            rebuildSatDetail()
        }
    }

    private fun rebuildSatDetail() {
        satDetailBox.removeAllViews()
        val fresh = GnssStore.fresh()
        if (!fresh) {
            satDetailBox.addView(Ui.body(this, "暂无卫星数据", Ui.DIM))
            return
        }
        var list = GnssStore.sats.sortedByDescending { it.cn0 }
        if (onlyUsed) list = list.filter { it.used }
        if (list.isEmpty()) {
            satDetailBox.addView(Ui.body(this, "没有符合条件的卫星", Ui.DIM))
            return
        }

        for (s in list.take(40)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(this@MainActivity, 5f), 0, Ui.dp(this@MainActivity, 5f))
            }
            row.addView(TextView(this).apply {
                text = s.label
                textSize = 12f
                setTextColor(if (s.used) Ui.TEXT else Ui.MUTED)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    Ui.dp(this@MainActivity, 52f), LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            row.addView(TextView(this).apply {
                text = String.format("%.0f°", s.elevation)
                textSize = 11f
                setTextColor(Ui.DIM)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    Ui.dp(this@MainActivity, 42f), LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })

            // 信号条
            val bar = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(this@MainActivity, 3f).toFloat()
                    setColor(
                        if (s.used) 0xFF38A7D5.toInt() else Ui.DIM
                    )
                }
            }
            val ratio = (s.cn0 / 50f).coerceIn(0.05f, 1f)
            row.addView(bar, LinearLayout.LayoutParams(0, Ui.dp(this@MainActivity, 6f), ratio))
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f - ratio)
            })

            row.addView(TextView(this).apply {
                text = String.format("%.0f", s.cn0)
                textSize = 11f
                setTextColor(if (s.used) Ui.TEXT else Ui.MUTED)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    Ui.dp(this@MainActivity, 40f), LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            satDetailBox.addView(row)
        }
    }

    private fun refreshLog(force: Boolean) {
        if (!force && lastLogVersion == LogStore.version) return
        lastLogVersion = LogStore.version

        val lines = LogStore.lines()
        val card = pages[BottomNav.TAB_LOG]?.findViewWithTag<View>("logCard")
        if (lines.isEmpty()) {
            logEmpty.visibility = View.VISIBLE
            card?.visibility = View.GONE
        } else {
            logEmpty.visibility = View.GONE
            card?.visibility = View.VISIBLE
            // 最新在底部，保持可读
            logBody.text = lines.joinToString("\n")
        }
    }

    // ==================== 权限 ====================

    private fun requestPermissionsThen(action: () -> Unit) {
        pendingAction = action
        doRequestPermissions()
    }

    /** 只申请当前缺失的运行时权限（供设置页「申请权限」按钮用）。 */
    private fun requestMissingPermissions() {
        val need = mutableListOf<String>()

        if (!PermCheck.fineLocation(this)) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (!PermCheck.btAdvertise(this)) need += Manifest.permission.BLUETOOTH_ADVERTISE
            if (!PermCheck.btConnect(this)) need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33 && !PermCheck.notifications(this)) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }

        if (need.isEmpty()) {
            // 运行时权限都齐了，问题在特殊权限/系统开关，引导去设置页
            Toast.makeText(
                this,
                "运行时权限已齐全，请逐个点击未通过的项跳转设置",
                Toast.LENGTH_LONG
            ).show()
            lastPermSig = ""
            refreshPermsNow()
        } else {
            pendingAction = { lastPermSig = ""; refreshPermsNow() }
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_BASIC)
        }
    }

    private fun doRequestPermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.ACCESS_FINE_LOCATION

        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.BLUETOOTH_ADVERTISE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.BLUETOOTH_CONNECT
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS

        if (need.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 30 &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG
                )
            } else {
                val a = pendingAction
                pendingAction = null
                a?.invoke() ?: refreshPermsNow()
            }
        } else {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_BASIC)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_BASIC -> doRequestPermissions()
            REQ_BG -> {
                val a = pendingAction
                pendingAction = null
                a?.invoke() ?: refreshPermsNow()
            }
        }
        // 结果回来后立刻刷新自检状态
        lastPermSig = ""
        refreshPermsIfChanged()
    }

    companion object {
        private const val REQ_BASIC = 100
        private const val REQ_BG = 101
    }
}
