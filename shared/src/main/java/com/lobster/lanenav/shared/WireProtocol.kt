package com.lobster.lanenav.shared

/**
 * 控制命令(JSON 单行)。盒子按行驶状态发往手机做省电/唤醒协调。
 *   {"cmd":"sleep"}  熄火/停车: 手机降到 0.1Hz
 *   {"cmd":"wake"}   点火/行驶: 恢复 1Hz
 */
object WireProtocol {
    const val CMD_SLEEP = "sleep"
    const val CMD_WAKE = "wake"
    const val CMD_PING = "ping"
    const val CMD_PONG = "pong"

    /**
     * 心跳。手机在没有 GPS 定位时（隧道 / 地库 / 高楼遮挡）也必须发，
     * 否则盒子无法区分「只是没有 GPS」和「链路真的断了」，
     * 只能一律当作断流处理，出隧道后还会停在「已断开」不恢复。
     */
    const val CMD_HB = "hb"
    const val CMD_RTT = "rtt"
    const val DEFAULT_PORT = 19800

    fun command(cmd: String): String = """{"cmd":"$cmd"}"""

    /** 盒子把测得的 RTT 回传给手机，供手机界面显示链路延时 */
    fun rtt(ms: Long): String = """{"cmd":"$CMD_RTT","t":$ms}"""

    /**
     * 链路测速探针。
     *
     * 为什么必须用它: 原来的「延迟」是 (盒子时钟 - 手机GPS时刻)，两台设备
     * 的墙上时钟从未对时，时钟差 2-3 秒就直接显示 2-3 秒，且与链路无关 ——
     * 这就是蓝牙和 WiFi 显示出同样数值的原因。
     *
     * ping/pong 的 t 始终由发起方（盒子）自己的时钟生成并回传，
     * 全程单时钟，测出来的才是真实 RTT。
     */
    fun ping(ts: Long): String = """{"cmd":"ping","t":$ts}"""

    fun pong(ts: Long): String = """{"cmd":"pong","t":$ts}"""

    fun heartbeat(ts: Long): String = """{"cmd":"hb","t":$ts}"""

    /** 从 ping/pong 行中取出时间戳；不是该类型返回 null。 */
    fun parseTs(line: String, cmd: String): Long? {
        val key = "\"cmd\":\"$cmd\""
        if (!line.contains(key)) return null
        val i = line.indexOf("\"t\":")
        if (i < 0) return null
        val start = i + 4
        var end = start
        while (end < line.length && (line[end].isDigit() || line[end] == '-')) end++
        return line.substring(start, end).toLongOrNull()
    }

    /** 解析手机收到的命令行；非命令返回 null。 */
    fun parseCommand(line: String): String? {
        if (!line.trimStart().startsWith("{\"cmd\":")) return null
        return when {
            line.contains(CMD_SLEEP) -> CMD_SLEEP
            line.contains(CMD_WAKE) -> CMD_WAKE
            else -> null
        }
    }
}
