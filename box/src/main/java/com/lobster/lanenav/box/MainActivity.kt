package com.lobster.lanenav.box

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lobster.lanenav.uikit.Segmented
import com.lobster.lanenav.uikit.Ui

/**
 * 盒子端界面：深色科技风。
 *
 * 强调色 = 蓝（ACCENT_BOX），与图标一致。
 */
class MainActivity : AppCompatActivity() {

    private val accent get() = Ui.ACCENT_BOX

    private lateinit var cfg: BoxConfig

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var metricSource: TextView
    private lateinit var metricSats: TextView
    private lateinit var metricLatency: TextView
    private lateinit var metricFrames: TextView
    private lateinit var warnText: TextView
    private lateinit var dataText: TextView
    private lateinit var ipField: android.widget.EditText
    private lateinit var portField: android.widget.EditText

    /** 用户主动点过「停止」，本次界面内不再被 onResume 自动拉起 */
    private var userStopped = false
    private lateinit var ipLabel: TextView

    /** 刷新循环专用 Handler，便于统一取消，避免重复循环。 */
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        cfg = BoxConfig(this)

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
            text = "BOX"
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
        val statusCard = Ui.card(ctx, accent)
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
            text = "配置手机地址后连接"
            textSize = 11.5f
            setTextColor(Ui.MUTED)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        statusCol.addView(statusTitle)
        statusCol.addView(statusSub)
        statusRow.addView(statusCol)
        statusCard.addView(statusRow)

        statusCard.addView(Ui.divider(ctx))

        // 实际定位来源（最关键指标，单独放大展示）
        statusCard.addView(TextView(ctx).apply {
            text = "实际定位源"
            textSize = 10f
            setTextColor(Ui.DIM)
            letterSpacing = 0.1f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        metricSource = TextView(ctx).apply {
            text = "检测中"
            textSize = 20f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, Ui.dp(ctx, 2f), 0, Ui.dp(ctx, 4f))
        }
        statusCard.addView(metricSource)
        metricSats = TextView(ctx).apply {
            text = "盒子天线 — 星"
            textSize = 11.5f
            setTextColor(Ui.MUTED)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        statusCard.addView(metricSats)

        statusCard.addView(Ui.divider(ctx))

        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        metricLatency = metricCell(grid, "延迟")
        metricFrames = metricCell(grid, "累计帧")
        statusCard.addView(grid)

        dataText = TextView(ctx).apply {
            text = "坐标 —"
            textSize = 11.5f
            setTextColor(Ui.MUTED)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, Ui.dp(ctx, 10f), 0, 0)
        }
        statusCard.addView(dataText)

        warnText = TextView(ctx).apply {
            textSize = 11.5f
            setTextColor(Ui.WARN)
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = View.GONE
            setPadding(0, Ui.dp(ctx, 8f), 0, 0)
        }
        statusCard.addView(warnText)
        root.addView(statusCard)

        // ---------- 连接设置 ----------
        val connCard = Ui.card(ctx)
        connCard.addView(Ui.sectionLabel(ctx, "连接设置", Ui.MUTED))

        ipLabel = TextView(ctx).apply {
            text = "手机 IP（BLE 模式可留空）"
            textSize = 10.5f
            setTextColor(Ui.DIM)
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, Ui.dp(ctx, 6f))
        }
        connCard.addView(ipLabel)

        ipField = Ui.field(ctx, "192.168.43.1", cfg.phoneIp)
        connCard.addView(ipField)

        connCard.addView(TextView(ctx).apply {
            text = "端口"
            textSize = 10.5f
            setTextColor(Ui.DIM)
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, Ui.dp(ctx, 6f))
        })
        portField = Ui.field(ctx, "19800", cfg.port.toString(), numeric = true)
        connCard.addView(portField)

        connCard.addView(Ui.sectionLabel(ctx, "传输通道", Ui.MUTED))
        val chanIndex = when (cfg.channel) {
            "ble" -> 0
            "tcp" -> 1
            else -> 2
        }
        val seg = Segmented(
            ctx, listOf("蓝牙 BLE", "WiFi TCP", "双通道"), accent, chanIndex
        ) { idx ->
            cfg.channel = when (idx) {
                0 -> "ble"
                1 -> "tcp"
                else -> "both"
            }
            updateIpVisibility()
        }
        connCard.addView(seg)
        updateIpVisibility()

        val autoConnect = Ui.checkbox(ctx, "打开应用自动启动（每次进来自动注入）", cfg.autoConnect, accent)
        val bootStart = Ui.checkbox(ctx, "开机自启（盒子常电推荐）", cfg.autoStart, accent)
        val eco = Ui.checkbox(ctx, "省电模式（静止时通知手机降频）", cfg.ecoMode, accent)
        connCard.addView(autoConnect)
        connCard.addView(bootStart)
        connCard.addView(eco)
        root.addView(connCard)

        // ---------- 操作 ----------
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(ctx, 16f) }
        }
        btnRow.addView(Ui.primaryButton(ctx, "连接并注入", accent) {
            cfg.phoneIp = ipField.text.toString().trim()
            cfg.port = portField.text.toString().toIntOrNull() ?: 19800
            cfg.autoConnect = autoConnect.isChecked
            cfg.autoStart = bootStart.isChecked
            cfg.ecoMode = eco.isChecked
            // 通道变更需重启服务生效
            MockRelayService.requestStop(this)
            ui.postDelayed({ startRelay() }, 600)
        })
        btnRow.addView(Ui.ghostButton(ctx, "停止", accent) {
            userStopped = true
            MockRelayService.requestStop(this)
            ui.postDelayed({ refresh() }, 500)
        })
        root.addView(btnRow)

        // ---------- 说明 ----------
        val helpCard = Ui.card(ctx)
        helpCard.addView(Ui.sectionLabel(ctx, "首次必做：授权模拟位置", Ui.WARN))
        helpCard.addView(
            Ui.body(
                ctx,
                "方式一：设置 → 开发者选项 → 选择模拟位置信息应用 → 本应用\n\n" +
                    "方式二（入口被隐藏时）：\n" +
                    "adb shell appops set com.lobster.lanenav.box android:mock_location allow",
                Ui.TEXT
            )
        )

        helpCard.addView(Ui.divider(ctx))
        helpCard.addView(Ui.sectionLabel(ctx, "排查", Ui.MUTED))
        helpCard.addView(
            Ui.body(
                ctx,
                "· 状态一直「等待连接」→ 看手机端是否已启动转发\n" +
                    "· WiFi TCP → 盒子需连手机热点，并填手机端显示的本机 IP\n" +
                    "· 蓝牙 BLE → 两端蓝牙打开，无需热点也无需填 IP\n" +
                    "· 「实际定位源」显示盒子自带 GPS → 说明注入未生效"
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

        requestLocationPermission()
    }

    private fun updateIpVisibility() {
        val needIp = cfg.channel != "ble"
        val v = if (needIp) View.VISIBLE else View.GONE
        ipField.visibility = v
        ipLabel.visibility = v
    }

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

    /** 盒子端需要定位权限才能读取"系统实际在用哪个 GPS"，仅用于检测，不采集位置。 */
    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOC
            )
        }
        // Android 12+ 蓝牙权限（盒子要扫描）
        if (Build.VERSION.SDK_INT >= 31) {
            val need = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.BLUETOOTH_SCAN
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.BLUETOOTH_CONNECT
            if (need.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_BT)
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTI
            )
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        refresh()
        // 只有「仅 TCP」模式才硬性要求手机 IP；BLE 与双通道不填 IP 也能先跑起来，
        // TCP 部分会在服务内部进入 WAIT_CONFIG 等待，不影响 BLE 注入。
        val ipRequired = cfg.channel == "tcp"
        if (cfg.autoConnect && !userStopped &&
            (!ipRequired || cfg.phoneIp.isNotBlank()) &&
            !MockRelayService.isRunning()
        ) {
            startRelay()
        }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    private fun startRelay() {
        userStopped = false
        runCatching {
            ContextCompat.startForegroundService(
                this, Intent(this, MockRelayService::class.java)
            )
        }
        ui.postDelayed({ refresh() }, 800)
    }

    private fun refresh() {
        val st = MockRelayService.state
        val ageSec = if (MockRelayService.lastPacketAt > 0)
            (System.currentTimeMillis() - MockRelayService.lastPacketAt) / 1000 else -1

        // ---- 主状态 ----
        val (dotColor, title, titleColor) = when {
            st == MockRelayService.State.RUNNING && ageSec in 0..3 ->
                Triple(Ui.OK, "注入中", Ui.OK)
            st == MockRelayService.State.RUNNING ->
                Triple(Ui.WARN, "注入中 · 数据滞后", Ui.WARN)
            st == MockRelayService.State.CONNECTING ->
                Triple(Ui.WARN, "连接中", Ui.TEXT)
            st == MockRelayService.State.NO_MOCK_PERMISSION ->
                Triple(Ui.ERR, "缺少模拟位置权限", Ui.ERR)
            st == MockRelayService.State.WAIT_CONFIG ->
                Triple(Ui.WARN, "未配置手机地址", Ui.WARN)
            st == MockRelayService.State.DISCONNECTED ->
                Triple(Ui.ERR, "已断开 · 重连中", Ui.ERR)
            else -> Triple(Ui.DIM, "未启动", Ui.TEXT)
        }
        statusDot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(dotColor)
        }
        statusTitle.text = title
        statusTitle.setTextColor(titleColor)
        statusSub.text = "通道 ${MockRelayService.displayChannel()} · ${st.name}"

        // ---- 实际定位源 ----
        val src = MockRelayService.srcLabel
        val srcAge = MockRelayService.srcAgeSec
        when {
            src.contains("手机") -> {
                metricSource.text = "手机 GPS"
                metricSource.setTextColor(Ui.OK)
            }
            src.contains("盒子") -> {
                metricSource.text = "盒子自带 GPS"
                metricSource.setTextColor(Ui.WARN)
            }
            src.contains("无定位") -> {
                metricSource.text = "无定位"
                metricSource.setTextColor(Ui.ERR)
            }
            else -> {
                metricSource.text = "检测中"
                metricSource.setTextColor(Ui.MUTED)
            }
        }

        if (!hasLocationPermission()) {
            metricSats.text = "未授予定位权限，无法检测（不采集位置）"
        } else {
            val boxSats = if (MockRelayService.srcSats == "0/0") "—"
            else MockRelayService.srcSats + " 星"
            val used = MockRelayService.phoneSatsUsed
            val total = MockRelayService.phoneSatsTotal
            val phoneSats = if (used > 0 || total > 0)
                "手机 $used/$total 星" else "手机 —"
            metricSats.text = buildString {
                append("盒子天线 $boxSats · $phoneSats")
                if (srcAge >= 0) append(" · ${srcAge}s 前更新")
            }
        }

        // ---- 指标 ----
        metricLatency.text = if (MockRelayService.lastLatencyMs > 0)
            "${MockRelayService.lastLatencyMs}ms" else "—"
        metricFrames.text = MockRelayService.rxCount.toString()
        dataText.text = "坐标 ${MockRelayService.lastFixText}"

        // ---- 警告 ----
        val warns = mutableListOf<String>()
        if (ageSec > 10) warns += "已 ${ageSec}s 未收到定位（断流）"
        MockRelayService.bleError?.let { warns += "BLE：$it" }
        MockRelayService.lastError?.let {
            if (st != MockRelayService.State.RUNNING) warns += it
        }
        if (warns.isEmpty()) {
            warnText.visibility = View.GONE
        } else {
            warnText.text = warns.joinToString("\n") { "⚠ $it" }
            warnText.visibility = View.VISIBLE
        }

        ui.postDelayed({ refresh() }, 1000)
    }

    companion object {
        private const val REQ_LOC = 201
        private const val REQ_NOTI = 202
        private const val REQ_BT = 203
    }
}
