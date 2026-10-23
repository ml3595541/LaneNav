package com.lobster.lanenav.box

import android.content.Context
import com.lobster.lanenav.shared.WireProtocol

/** 简易配置存储: 手机 IP / 端口 / 是否自动连接。 */
class BoxConfig(ctx: Context) {

    private val sp = ctx.getSharedPreferences("lanenav", Context.MODE_PRIVATE)

    var phoneIp: String
        get() = sp.getString(KEY_IP, "") ?: ""
        set(v) = sp.edit().putString(KEY_IP, v.trim()).apply()

    var port: Int
        get() = sp.getInt(KEY_PORT, WireProtocol.DEFAULT_PORT)
        set(v) = sp.edit().putInt(KEY_PORT, v).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean(KEY_AUTO, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO, v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean(KEY_BOOT, true)
        set(v) = sp.edit().putBoolean(KEY_BOOT, v).apply()

    /** 传输通道: tcp / ble / both */
    var channel: String
        get() = sp.getString(KEY_CHANNEL, "both") ?: "both"
        set(v) = sp.edit().putString(KEY_CHANNEL, v).apply()

    /** 熄火省电: 静止超过 N 秒通知手机降频 */
    var ecoMode: Boolean
        get() = sp.getBoolean(KEY_ECO, true)
        set(v) = sp.edit().putBoolean(KEY_ECO, v).apply()

    private companion object {
        const val KEY_IP = "phone_ip"
        const val KEY_PORT = "port"
        const val KEY_AUTO = "auto_connect"
        const val KEY_BOOT = "boot_start"
        const val KEY_ECO = "eco_mode"
        const val KEY_CHANNEL = "channel"
    }
}
