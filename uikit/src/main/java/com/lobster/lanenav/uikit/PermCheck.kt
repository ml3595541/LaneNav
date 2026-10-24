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
 *  - 系统开关（定位总开关、蓝牙开关）
 *
 * 每项给出 ok 状态和一句话说明，界面直接渲染。
 */
object PermCheck {

    data class Item(
        /** 稳定标识，界面据此决定点击后做什么 */
        val id: String,
        val title: String,
        val detail: String,
        val ok: Boolean,
        /** 缺失会导致核心功能不可用 */
        val critical: Boolean = true
    )

    // ---------------- 单项检测 ----------------

    fun fineLocation(ctx: Context): Boolean = granted(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)

    fun coarseLocation(ctx: Context): Boolean = granted(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION)

    fun backgroundLocation(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        return granted(ctx, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun notifications(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return granted(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Android 12+ 蓝牙三件套；12 以下只需老 BLUETOOTH 权限。 */
    fun bluetooth(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) {
            return granted(ctx, android.Manifest.permission.BLUETOOTH)
        }
        return granted(ctx, android.Manifest.permission.BLUETOOTH_SCAN) &&
            granted(ctx, android.Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun bluetoothAdvertise(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return granted(ctx, android.Manifest.permission.BLUETOOTH_ADVERTISE)
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

    /** 手机端：采集定位并转发。 */
    fun phone(ctx: Context): List<Item> = buildList {
        add(
            Item(
                "fine",
                "精确定位",
                if (fineLocation(ctx)) "已授予" else "未授予 · 无法采集 GNSS",
                fineLocation(ctx)
            )
        )
        add(
            Item(
                "bg",
                "后台定位",
                if (backgroundLocation(ctx)) "已授予" else "未授予 · 息屏后可能断流",
                backgroundLocation(ctx)
            )
        )
        add(
            Item(
                "noti",
                "通知权限",
                if (notifications(ctx)) "已授予" else "未授予 · 前台服务通知不可见",
                notifications(ctx),
                critical = false
            )
        )
        add(
            Item(
                "bt",
                "蓝牙权限",
                if (bluetooth(ctx)) "已授予" else "未授予 · BLE 通道不可用",
                bluetooth(ctx)
            )
        )
        add(
            Item(
                "btadv",
                "蓝牙广播",
                if (bluetoothAdvertise(ctx)) "已授予" else "未授予 · 盒子搜不到手机",
                bluetoothAdvertise(ctx)
            )
        )
        add(
            Item(
                "locsvc",
                "系统定位开关",
                if (locationEnabled(ctx)) "已开启" else "已关闭 · 权限给了也拿不到坐标",
                locationEnabled(ctx)
            )
        )
        add(
            Item(
                "battery",
                "忽略电池优化",
                if (batteryUnrestricted(ctx)) "已豁免" else "未豁免 · 建议在系统设置里设无限制",
                batteryUnrestricted(ctx),
                critical = false
            )
        )
    }

    /** 盒子端：注入模拟位置。 */
    fun box(ctx: Context): List<Item> = buildList {
        add(
            Item(
                "mock",
                "模拟位置授权",
                if (mockLocation(ctx)) "已授权" else "未授权 · 注入完全无效（最致命）",
                mockLocation(ctx)
            )
        )
        add(
            Item(
                "fine",
                "定位权限",
                if (fineLocation(ctx)) "已授予" else "未授予 · 无法读取自身 GPS 状态",
                fineLocation(ctx)
            )
        )
        add(
            Item(
                "locsvc",
                "系统定位开关",
                if (locationEnabled(ctx)) "已开启" else "已关闭 · 导航软件无法定位",
                locationEnabled(ctx)
            )
        )
        add(
            Item(
                "bt",
                "蓝牙权限",
                if (bluetooth(ctx)) "已授予" else "未授予 · BLE 通道不可用",
                bluetooth(ctx)
            )
        )
        add(
            Item(
                "btadv",
                "蓝牙广播/扫描",
                if (bluetoothAdvertise(ctx)) "已授予" else "未授予 · 扫描不到手机",
                bluetoothAdvertise(ctx)
            )
        )
        add(
            Item(
                "noti",
                "通知权限",
                if (notifications(ctx)) "已授予" else "未授予 · 前台服务通知不可见",
                notifications(ctx),
                critical = false
            )
        )
        add(
            Item(
                "battery",
                "忽略电池优化",
                if (batteryUnrestricted(ctx)) "已豁免" else "未豁免 · 车机休眠后可能断开",
                batteryUnrestricted(ctx),
                critical = false
            )
        )
    }

    /** 缺失的项数（只统计 critical）。 */
    fun missingCritical(items: List<Item>): Int =
        items.count { !it.ok && it.critical }

    fun allOk(items: List<Item>): Boolean = items.all { it.ok }
}
