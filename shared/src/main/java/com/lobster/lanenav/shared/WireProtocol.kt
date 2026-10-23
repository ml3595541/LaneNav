package com.lobster.lanenav.shared

/**
 * 控制命令(JSON 单行)。盒子按行驶状态发往手机做省电/唤醒协调。
 *   {"cmd":"sleep"}  熄火/停车: 手机降到 0.1Hz
 *   {"cmd":"wake"}   点火/行驶: 恢复 1Hz
 */
object WireProtocol {
    const val CMD_SLEEP = "sleep"
    const val CMD_WAKE = "wake"
    const val DEFAULT_PORT = 19800

    fun command(cmd: String): String = """{"cmd":"$cmd"}"""

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
