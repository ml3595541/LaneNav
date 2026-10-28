package com.lobster.lanenav.uikit

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat

/**
 * 权限自检。
 *
 * 分三类：
 *  - 运行时权限（弹窗可授予）
 *  - 特殊权限（AppOps / 电池优化，要跳系统页或 adb）
 *  - 系统开关（定位总开关）
 *
 * 每项给出 ok 状态、说明、以及点击后应跳转的目标（jump）。
 */
object PermCheck {

    data class Item(
        /** 稳定标识，界面据此决定点击后跳哪里 */
        val id: String,
        val title: String,
        val detail: String,
        val ok: Boolean,
        /** 缺失会导致核心功能不可用 */
        val critical: Boolean = true
    )

    // ---------------- 单项检测 ----------------

    fun fineLocation(ctx: Context): Boolean =
        granted(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)

    fun backgroundLocation(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        return granted(ctx, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun notifications(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return granted(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * 蓝牙「广播」权限：手机端作为外设广播，需要 ADVERTISE。
     * 12 以下无此概念，视为已授予。
     */
    fun btAdvertise(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) {
            return granted(ctx, android.Manifest.permission.BLUETOOTH)
        }
        return granted(ctx, android.Manifest.permission.BLUETOOTH_ADVERTISE)
    }

    /**
     * 蓝牙「扫描」权限：盒子端作为客户端搜索手机，需要 SCAN。
     */
    fun btScan(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) {
            return granted(ctx, android.Manifest.permission.BLUETOOTH)
        }
        return granted(ctx, android.Manifest.permission.BLUETOOTH_SCAN)
    }

    /** 蓝牙「连接」权限：两端都需要（GATT 服务端/客户端收发数据）。 */
    fun btConnect(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) {
            return granted(ctx, android.Manifest.permission.BLUETOOTH)
        }
        return granted(ctx, android.Manifest.permission.BLUETOOTH_CONNECT)
    }

    /** 系统定位总开关是否打开。关闭时即使有权限也拿不到定位。 */
    fun locationEnabled(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= 28) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    /**
     * 模拟位置授权（AppOps）。
     *
     * 这一项不在运行时权限弹窗里，只能在「开发者选项 → 选择模拟位置信息应用」
     * 或 adb appops 里授权。部分 ROM 会把入口藏起来。
     */
    fun mockLocation(ctx: Context): Boolean {
        return try {
            val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                aom.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    ctx.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                aom.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    ctx.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 悬浮窗权限（SYSTEM_ALERT_WINDOW）。
     *
     * 不在运行时权限弹窗里，只能跳「显示在其他应用上层」授权页。
     * 只影响悬浮球显示，不影响转发本身，因此归为建议项。
     */
    fun overlay(ctx: Context): Boolean =
        try {
            Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(ctx)
        } catch (e: Exception) {
            false
        }

    /** 电池优化是否已豁免。未豁免时息屏后可能被系统限流。 */
    fun batteryUnrestricted(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (e: Exception) {
            true
        }
    }

    private fun granted(ctx: Context, perm: String): Boolean =
        try {
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            // 某些 ROM 上权限名不存在会抛异常；视为不授予
            false
        }

    // ---------------- 两端清单 ----------------

    /**
     * 手机端：采集定位并**广播**给盒子。
     *
     * 注意：手机端从不扫描，因此**不检查 BLUETOOTH_SCAN**。
     * 早期版本按「扫描+连接」判定，导致蓝牙通道明明正常工作
     * 却永远报「未授予蓝牙权限」。
     */
    fun phone(ctx: Context): List<Item> = buildList {
        add(itemFine(ctx))
        add(itemBackground(ctx))

        val adv = btAdvertise(ctx)
        add(
            Item(
                "btadv", "蓝牙广播",
                if (adv) "已授予" else "未授予 · 盒子搜不到手机",
                adv
            )
        )
        val conn = btConnect(ctx)
        add(
            Item(
                "btconn", "蓝牙连接",
                if (conn) "已授予" else "未授予 · 无法与盒子收发数据",
                conn
            )
        )

        add(itemLocationSwitch(ctx))
        add(itemNoti(ctx))
        add(itemBattery(ctx))
        add(itemOverlay(ctx))
    }

    /**
     * 盒子端：**扫描**手机并注入模拟位置。
     */
    fun box(ctx: Context): List<Item> = buildList {
        val mock = mockLocation(ctx)
        add(
            Item(
                "mock", "模拟位置授权",
                if (mock) "已授权" else "未授权 · 注入完全无效（最致命）",
                mock
            )
        )
        add(itemFine(ctx))

        val scan = btScan(ctx)
        add(
            Item(
                "btscan", "蓝牙扫描",
                if (scan) "已授予" else "未授予 · 搜不到手机",
                scan
            )
        )
        val conn = btConnect(ctx)
        add(
            Item(
                "btconn", "蓝牙连接",
                if (conn) "已授予" else "未授予 · 无法与手机收发数据",
                conn
            )
        )

        add(itemLocationSwitch(ctx))
        add(itemNoti(ctx))
        add(itemBattery(ctx))
        add(itemOverlay(ctx))
    }

    // ---------------- 单项目工厂（两端共用文案） ----------------

    private fun itemFine(ctx: Context): Item {
        val ok = fineLocation(ctx)
        return Item(
            "fine", "精确定位",
            if (ok) "已授予" else "未授予 · 无法采集 GNSS",
            ok
        )
    }

    private fun itemBackground(ctx: Context): Item {
        val ok = backgroundLocation(ctx)
        return Item(
            "bg", "后台定位",
            if (ok) "已授予" else "未授予 · 息屏后可能断流",
            ok
        )
    }

    private fun itemLocationSwitch(ctx: Context): Item {
        val ok = locationEnabled(ctx)
        return Item(
            "locsvc", "系统定位开关",
            if (ok) "已开启" else "已关闭 · 权限给了也拿不到坐标",
            ok
        )
    }

    private fun itemOverlay(ctx: Context): Item {
        val ok = overlay(ctx)
        return Item(
            "overlay", "悬浮窗",
            if (ok) "已授予" else "未授予 · 悬浮球无法显示（不影响转发）",
            ok,
            critical = false
        )
    }

    private fun itemNoti(ctx: Context): Item {
        val ok = notifications(ctx)
        return Item(
            "noti", "通知权限",
            if (ok) "已授予" else "未授予 · 前台服务通知不可见",
            ok,
            critical = false
        )
    }

    private fun itemBattery(ctx: Context): Item {
        val ok = batteryUnrestricted(ctx)
        return Item(
            "battery", "忽略电池优化",
            if (ok) "已豁免" else "未豁免 · 建议在系统设置里设无限制",
            ok,
            critical = false
        )
    }

    /** 缺失的项数（只统计 critical）。 */
    fun missingCritical(items: List<Item>): Int = items.count { !it.ok && it.critical }

    fun allOk(items: List<Item>): Boolean = items.all { it.ok }
}
