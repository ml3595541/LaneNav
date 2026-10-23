package com.lobster.lanenav.shared

/**
 * 定位数据包。一端(手机)序列化经 TCP/BLE 传输，另一端(盒子)反序列化后喂给 MockLocation。
 *
 * 字段对齐：[android.location.Location] 的核心属性 + 调试/推算状态。
 */
data class LocationPacket(
    val ts: Long,            // 采集时间戳, System.currentTimeMillis()
    val lat: Double,
    val lon: Double,
    val alt: Double,         // 米
    val bearing: Float,      // 航向角 0-360
    val speed: Float,        // 米/秒
    val acc: Float,          // 水平精度 米
    val vacc: Float,         // 垂直精度 米(API26+)
    val fix: FixType,        // 数据来源: GNSS/融合/推算
    val sats: Int = 0,       // 参与定位的卫星数(usedInFix)
    val satsTotal: Int = 0,  // 可见卫星总数(visible)
    val bsrc: String = "none" // 航向来源: sensor(传感器) / gnss / none
) {
    enum class FixType(val tag: String) {
        GNSS("gnss"),
        FUSED("fused"),
        IMU("imu");

        companion object {
            fun from(tag: String): FixType =
                values().firstOrNull { it.tag == tag } ?: FUSED
        }
    }

    /** 序列化为单行 JSON(无换行,便于 TCP 逐行读)。 */
    fun toJson(): String = buildString {
        append("{\"ts\":").append(ts)
        append(",\"lat\":").append(lat)
        append(",\"lon\":").append(lon)
        append(",\"alt\":").append(alt)
        append(",\"bearing\":").append(bearing)
        append(",\"speed\":").append(speed)
        append(",\"acc\":").append(acc)
        append(",\"vacc\":").append(vacc)
        append(",\"fix\":\"").append(fix.tag).append('"')
        append(",\"sats\":").append(sats)
        append(",\"satsTotal\":").append(satsTotal)
        append(",\"bsrc\":\"").append(bsrc).append('"')
        append('}')
    }

    companion object {
        /** 从单行 JSON 解析；解析失败返回 null。 */
        fun fromJson(line: String): LocationPacket? {
            if (!line.contains("lat")) return null
            fun num(key: String): Double {
                val s = key + "\":"
                val i = line.indexOf(s)
                if (i < 0) return 0.0
                val start = i + s.length
                var end = start
                while (end < line.length && line[end] != ',' && line[end] != '}') end++
                return line.substring(start, end).trim().toDoubleOrNull() ?: 0.0
            }
            fun str(key: String): String {
                val s = key + "\":\""
                val i = line.indexOf(s)
                if (i < 0) return ""
                val start = i + s.length
                var end = start
                while (end < line.length && line[end] != '"') end++
                return line.substring(start, end)
            }
            return try {
                LocationPacket(
                    ts = num("ts").toLong(),
                    lat = num("lat"),
                    lon = num("lon"),
                    alt = num("alt"),
                    bearing = num("bearing").toFloat(),
                    speed = num("speed").toFloat(),
                    acc = num("acc").toFloat(),
                    vacc = num("vacc").toFloat(),
                    fix = FixType.from(str("fix")),
                    sats = num("sats").toInt(),
                    satsTotal = num("satsTotal").toInt(),
                    bsrc = str("bsrc").ifBlank { "none" }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
