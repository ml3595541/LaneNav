package com.lobster.lanenav.box

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lobster.lanenav.uikit.PermCheck
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
    private lateinit var metricHeading: TextView
    private lateinit var warnText: TextView
    private lateinit var dataText: TextView
    private lateinit var ipField: android.widget.EditText
    private lateinit var portField: android.widget.EditText

    /** 用户主动点过「停止」，本次界面内不再被 onResume 自动拉起 */
    private var userStopped = false
    /** 有权限请求在途，期间不得启动前台服务 */
    private var permissionPending = false
    private lateinit var ipLabel: TextView

    // ---------- 权限自检 ----------
    private var permBox: LinearLayout? = null
    private var permSummary: TextView? = null
    private var lastPermSig = ""

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
        metricHeading = metricCell(grid, "航向")
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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        permSummary = TextView(ctx).apply {
            text = "检查中…"
            textSize = 11f
            setTextColor(Ui.MUTED)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        permHead.addView(permSummary)
        permCard.addView(permHead)

        permCard.addView(
            Ui.body(ctx, "逐项核对本机是否真的拿到了所需权限。模拟位置授权不在普通权限弹窗里，必须单独开启。")
        )
        permCard.addView(Ui.divider(ctx))

        permBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        permCard.addView(permBox)

        val permBtnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 10f) }
        }
        permBtnRow.addView(Ui.primaryButton(ctx, "申请缺失权限", accent) {
            requestMissingPerBox()
        })
        permBtnRow.addView(Ui.ghostButton(ctx, "系统设置", accent) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + packageName)
                    )
                )
            }
        })
        permCard.addView(permBtnRow)

        // 模拟位置授权拿不到时，给一键跳开发者选项的入口
        permCard.addView(TextView(ctx).apply {
            text = "打开「模拟位置信息应用」设置 →"
            textSize = 11.5f
            setTextColor(accent)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, Ui.dp(ctx, 10f), 0, 0)
            setOnClickListener {
                val ok = runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    )
                }.isSuccess
                if (!ok) {
                    android.widget.Toast.makeText(
                        ctx,
                        "无法打开开发者选项，请用 adb appops 授权",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
        root.addView(permCard)

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
        // ---------- 悬浮球 ----------
        val ballCard = Ui.card(ctx)
        ballCard.addView(Ui.sectionLabel(ctx, "悬浮球", Ui.MUTED))
        val ballSwitch = Ui.checkbox(ctx, "后台运行时显示连接状态", cfg.floatBall, accent)
        ballSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !FloatingBall.canShow(this)) {
                // 没有悬浮窗权限时不能开，直接把开关退回去并引导授权
                ballSwitch.isChecked = false
                android.widget.Toast.makeText(
                    this, "请先授予「显示在其他应用上层」权限",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                openOverlaySettings()
                return@setOnCheckedChangeListener
            }
            cfg.floatBall = checked
            if (checked) FloatingBall.show(this) else FloatingBall.hide()
        }
        ballCard.addView(ballSwitch)
        ballCard.addView(
            Ui.body(
                ctx,
                "绿色「手机」= 正在使用手机 GPS\n" +
                    "黄色「车机」= 已断开，回落到车机自带 GPS\n" +
                    "可拖动，位置自动记住"
            )
        )
        val ballRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 10f) }
        }
        ballRow.addView(Ui.ghostButton(ctx, "悬浮窗权限", accent) { openOverlaySettings() })
        ballCard.addView(ballRow)
        root.addView(ballCard)

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

    /** 跳系统「显示在其他应用上层」授权页。 */
    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            android.widget.Toast.makeText(
                this, "无法打开悬浮窗设置，请手动前往系统设置",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateIpVisibility() {
        val needIp = cfg.channel != "ble"
        val v = if (needIp) View.VISIBLE else View.GONE
        ipField.visibility = v
        ipLabel.visibility = v
    }

    /** 航向显示：角度 + 来源（传感器/GNSS） */
    private fun mockHeadingText(): String {
        val b = MockRelayService.lastBearing
        return when (MockRelayService.lastBsrc) {
            "sensor" -> String.format("%.0f° 传感器", b)
            "gnss" -> String.format("%.0f° GNSS", b)
            else -> "—"
        }
    }

    /** 渲染权限清单。 */
    private fun refreshPerms() {
        val items = PermCheck.box(this)
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

        for (it in items) box.addView(permRow(it))
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
            setTextColor(
                if (item.ok) Ui.TEXT else (if (item.critical) Ui.ERR else Ui.WARN)
            )
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
            typeface = android.graphics.Typeface.MONOSPACE
        })

        // 未通过的项可点击，直接跳到能解决它的系统页面
        if (!item.ok) {
            row.addView(TextView(ctx).apply {
                text = " ›"
                textSize = 17f
                setTextColor(Ui.MUTED)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { openPermTarget(item.id) }
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
            // 模拟位置：必须走开发者选项，没有别的入口
            "mock" -> {
                val ok = runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }.isSuccess
                if (ok) {
                    android.widget.Toast.makeText(
                        this,
                        "找到「选择模拟位置信息应用」，选本应用",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        this,
                        "入口被隐藏，请用 adb appops 授权",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            // 运行时权限：直接弹窗
            "fine", "btscan", "btconn" -> {
                requestLocationPermission()
                ui.postDelayed({ lastPermSig = ""; refreshPerms() }, 1200)
            }
            // 系统定位总开关
            "locsvc" -> runCatching {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
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
            "battery" -> {
                val direct = runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + packageName)
                        )
                    )
                }.isSuccess
                if (!direct) {
                    val listed = runCatching {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }.isSuccess
                    if (!listed) openAppSettings()
                }
            }
            else -> openAppSettings()
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName)
                )
            )
        }
    }

    /** 只在状态变化时重建。 */
    private fun refreshPermsIfChanged() {
        if (permBox == null) return
        val items = PermCheck.box(this)
        val sig = items.joinToString(",") { "${'$'}{it.id}=${'$'}{it.ok}" }
        if (sig == lastPermSig) return
        lastPermSig = sig
        refreshPerms()
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

    /**
     * 申请所有运行时权限。
     *
     * 关键改动: 原来拆成三次 requestPermissions 连发, 系统会排队弹窗,
     * 首次安装时用户还在看第一个弹窗, onResume 就已经把前台服务拉起来了 ——
     * Android 14 的 connectedDevice 前台服务缺蓝牙权限会抛 SecurityException,
     * 表现就是「第一次第二次打开闪退, 权限给了以后就不闪」。
     *
     * 现在合并为一次申请, 并且【权限齐了才启动服务】(见 maybeAutoStart)。
     */
    /** 只申请当前缺失的运行时权限。 */
    private fun requestMissingPerBox() {
        val need = mutableListOf<String>()
        if (!PermCheck.fineLocation(this)) need += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 31) {
            if (!PermCheck.btScan(this)) need += Manifest.permission.BLUETOOTH_SCAN
            if (!PermCheck.btConnect(this)) need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33 && !PermCheck.notifications(this)) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isEmpty()) {
            android.widget.Toast.makeText(
                this,
                "运行时权限已齐全，模拟位置授权需在开发者选项里单独开",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            permissionPending = true
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_ALL)
        }
        ui.postDelayed({ lastPermSig = ""; refreshPerms() }, 1200)
    }

    private fun requestLocationPermission() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.BLUETOOTH_SCAN
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS

        if (need.isNotEmpty()) {
            permissionPending = true
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_ALL)
        }
    }

    /**
     * 前台服务需要的权限是否齐全。
     *
     * Android 14: foregroundServiceType=connectedDevice 要求蓝牙三选一;
     * 定位权限用于读取「系统实际在用哪个 GPS」。
     */
    private fun hasServicePermissions(): Boolean {
        val loc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT < 31) return loc
        val bt = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        return loc && bt
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_ALL) return
        permissionPending = false
        // 权限结果回来后重新判断能否启动，并把界面刷新一次
        maybeAutoStart()
        refresh()
    }

    /** 权限齐了、且配置允许、且服务没在跑，才自动启动。 */
    private fun maybeAutoStart() {
        if (!cfg.autoConnect || userStopped) return
        if (MockRelayService.isRunning()) return
        if (permissionPending) return
        if (!hasServicePermissions()) return
        val ipRequired = cfg.channel == "tcp"
        if (ipRequired && cfg.phoneIp.isBlank()) return
        startRelay()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        lastPermSig = ""
        refresh()
        // 只有「仅 TCP」模式才硬性要求手机 IP；BLE 与双通道不填 IP 也能先跑起来，
        // TCP 部分会在服务内部进入 WAIT_CONFIG 等待，不影响 BLE 注入。
        maybeAutoStart()
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
            st == MockRelayService.State.NO_FIX ->
                Triple(Ui.WARN, "隧道中 · 无 GPS", Ui.WARN)
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
        metricHeading.text = mockHeadingText()
        dataText.text = "坐标 ${MockRelayService.lastFixText}"

        // ---- 警告 ----
        val warns = mutableListOf<String>()
        // 隧道里没有定位是正常的，不要报成故障
        if (ageSec > 10 && st == MockRelayService.State.RUNNING) {
            warns += "已 ${ageSec}s 未收到定位（断流）"
        }
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

        // 权限状态变化时才重建卡片，避免每秒刷 View
        refreshPermsIfChanged()

        ui.postDelayed({ refresh() }, 1000)
    }

    companion object {
        /** 一次申请全部运行时权限 */
        private const val REQ_ALL = 201
    }
}
