package com.lobster.lanenav.box

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** 盒子常电: 开机/应用更新后自动启动中继服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val cfg = BoxConfig(ctx)
        if (!cfg.autoStart) return
        // BLE 模式不需要手机 IP，不能因 IP 为空就跳过自启
        if (cfg.channel != "ble" && cfg.phoneIp.isBlank()) return
        Log.i("LaneNavBox", "开机自启: ${intent?.action}")
        runCatching {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, MockRelayService::class.java)
            )
        }
    }
}
