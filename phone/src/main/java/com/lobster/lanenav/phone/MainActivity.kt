package com.lobster.lanenav.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lobster.lanenav.uikit.Segmented
import com.lobster.lanenav.uikit.Ui

/**
 * 手机端界面：深色科技风。
 *
 * 强调色 = 红（ACCENT_PHONE），与图标一致。
 */
class MainActivity : AppCompatActivity() {

    private val accent get() = Ui.ACCENT_PHONE

    private lateinit var statusDot: android.view.View
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
    private var running = false

    /** 是否允许「打开应用自动启动」。默认开启。 */
    private var autoStartOnOpen = true

    /** 本次进界面尚未尝试过自动启动（防止在 onResume 里反复触发） */
    private var autoStartPending = false

    /** 用户主动点过「停止」，本次进界面内不再自动重启 */
    private var userStopped = false

    /** 刷新循环专用 Handler，便于统一取消，避免重复循环。 */
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val ctx = this
        val pad = Ui.dp(ctx, 16f)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Ui.BG)
        }

        // ---------- 顶栏 ----------
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(ctx, 16f) }
        }
        header.addView(TextView(ctx).apply {
            text = "LANENAV"
            textSize = 19f
            setTextColor(Ui.TEXT)
            letterSpacing = 0.12f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(ctx).apply {
            text = "PHONE"
            textSize = 10f
            setTextColor(accent)
            letterSpacing = 0.18f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = Ui.dp(ctx, 6f).toFloat()
                setColor(Ui.alpha(accent, 0.14f))
                setStroke(1, Ui.alpha(accent, 0.5f))
            }
            setPadding(Ui.dp(ctx, 8f), Ui.dp(ctx, 4f), Ui.dp(ctx, 8f), Ui.dp(ctx, 4f))
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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        statusSub = TextView(ctx).apply {
            text = "点击下方按钮开始转发"
            textSize = 11.5f
            setTextColor(Ui.MUTED)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        statusCol.addView(statusTitle)
        statusCol.addView(statusSub)
        statusRow.addView(statusCol)
        statusCard.addView(statusRow)

        statusCard.addView(Ui.divider(ctx))

        // 指标网格（2x2）
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
            typeface = android.graphics.Typeface.MONOSPACE
        }
        statusCard.addView(metricFix)

        warnText = TextView(ctx).apply {
            textSize = 11.5f
            setTextColor(Ui.ERR)
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = android.view.View.GONE
            setPadding(0, Ui.dp(ctx, 8f), 0, 0)
        }
        statusCard.addView(warnText)

        root.addView(statusCard)

        // ---------- 传输通道 ----------
        val chanCard = Ui.card(ctx)
        chanCard.addView(Ui.sectionLabel(ctx, "传输通道", Ui.MUTED))

        val sp = getSharedPreferences("lanenav", MODE_PRIVATE)
        autoStartOnOpen = sp.getBoolean("auto_start_on_open", true)
        autoStartPending = autoStartOnOpen
        val curChannel = sp.getString("channel", "both") ?: "both"
        val chanIndex = when (curChannel) {
            "ble" -> 0
            "tcp" -> 1
            else -> 2
        }
        val seg = Segmented(
            ctx,
            listOf("蓝牙 BLE", "WiFi TCP", "双通道"),
            accent,
            chanIndex
        ) { idx ->
            val ch = when (idx) {
                0 -> "ble"
                1 -> "tcp"
                else -> "both"
            }
            sp.edit().putString("channel", ch).apply()
            if (running) {
                stopRelay()
                ui.postDelayed({ startRelay() }, 500)
            }
        }
        chanCard.addView(seg)
        chanCard.addView(
            Ui.body(ctx, "切换后自动重启服务。BLE 不需要开热点，功耗更低。")
        )
        root.addView(chanCard)

        // ---------- 自动启动 ----------
        val autoCard = Ui.card(ctx)
        autoCard.addView(Ui.sectionLabel(ctx, "启动行为", Ui.MUTED))
        val autoBox = Ui.checkbox(
            ctx, "打开应用自动启动转发", autoStartOnOpen, accent
        )
        autoBox.setOnCheckedChangeListener { _, checked ->
            autoStartOnOpen = checked
            getSharedPreferences("lanenav", MODE_PRIVATE).edit()
                .putBoolean("auto_start_on_open", checked).apply()
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

        // ---------- 操作按钮 ----------
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(ctx, 16f) }
        }
        btnRow.addView(Ui.primaryButton(ctx, "启动转发", accent) {
            requestPermissionsThen { startRelay() }
        })
        btnRow.addView(Ui.ghostButton(ctx, "停止", accent) { stopRelay() })
        root.addView(btnRow)

        // ---------- 必设说明 ----------
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

        helpCard.addView(Ui.divider(ctx))
        helpCard.addView(
            Ui.mono(
                ctx,
                "机型 ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                11f,
                Ui.DIM
            )
        )
        root.addView(helpCard)

        // ---------- 作者联系方式 ----------
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 2f) }
        }
        footer.addView(TextView(ctx).apply {
            text = "作者微信：SnownyC"
            textSize = 12f
            setTextColor(Ui.DIM)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 8f), Ui.dp(ctx, 10f), Ui.dp(ctx, 8f))
            // 点一下复制，方便直接去微信搜索
            setOnClickListener {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("wechat", "SnownyC")
                )
                android.widget.Toast.makeText(
                    ctx, "微信号已复制", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        })
        root.addView(footer)

        root.addView(Ui.spacer(ctx, 24f))

        setContentView(ScrollView(ctx).apply {
            setBackgroundColor(Ui.BG)
            addView(root)
        })
    }

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
            typeface = android.graphics.Typeface.MONOSPACE
        })
        val value = TextView(this).apply {
            text = "—"
            textSize = 15f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        cell.addView(value)
        container.addView(cell)
        return value
    }

    override fun onResume() {
        super.onResume()
        running = LocationRelayService.isRunning
        refresh()

        // 开启「打开应用自动启动」时，进界面自动启动一次。
        // autoStartPending 保证只触发一次；userStopped 保证手动停止后不会被拉起。
        if (autoStartPending && !userStopped && !LocationRelayService.isRunning) {
            autoStartPending = false
            requestPermissionsThen { startRelay() }
        }
    }

    override fun onPause() {
        super.onPause()
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

    private fun refresh() {
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
            metricFix.text = "最近定位：—"
            warnText.visibility = android.view.View.GONE
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
        statusSub.text = buildString {
            append(if (tcp > 0 && ble > 0) "双通道在线" else if (ble > 0) "BLE 在线" else if (tcp > 0) "TCP 在线" else "服务运行中")
        }

        metricIp.text = NetUtils.localIpv4()
        metricPort.text = "19800"
        metricTcp.text = tcp.toString()
        metricBle.text = ble.toString()
        // 延时由盒子回传（ping/pong 单时钟测量），自己算不出来
        val rtt = LocationRelayService.lastRtt
        metricRtt.text = if (rtt > 0) "${rtt}ms" else "—"
        metricHeading.text = LocationRelayService.lastHeadingSummary
        metricFix.text = "最近定位：${LocationRelayService.lastFixSummary}"

        val err = LocationRelayService.bleError
        if (err != null) {
            warnText.text = "⚠ BLE：$err"
            warnText.visibility = android.view.View.VISIBLE
        } else {
            warnText.visibility = android.view.View.GONE
        }

        ui.postDelayed({ if (running) refresh() }, 1000)
    }

    private fun requestPermissionsThen(action: () -> Unit) {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.ACCESS_FINE_LOCATION

        // Android 12+ 蓝牙运行时权限（手机端要广播，需要 ADVERTISE + CONNECT）
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
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQ_BG
                )
            } else action()
        } else {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_BASIC)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_BASIC -> requestPermissionsThen { startRelay() }
            REQ_BG -> startRelay()
        }
    }

    companion object {
        private const val REQ_BASIC = 100
        private const val REQ_BG = 101
    }
}
