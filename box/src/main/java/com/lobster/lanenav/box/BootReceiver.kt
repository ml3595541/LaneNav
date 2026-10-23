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
        // 仅「纯 TCP」模式才硬性要求手机 IP；BLE / 双通道不填 IP 也能自启，
        // TCP 部分会在服务内部进入 WAIT_CONFIG 等待，不阻塞 BLE 注入。
        if (cfg.channel == "tcp" && cfg.phoneIp.isBlank()) return
        Log.i("LaneNavBox", "开机自启: ${intent?.action}")
        runCatching {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, MockRelayService::class.java)
            )
        }
    }
}
