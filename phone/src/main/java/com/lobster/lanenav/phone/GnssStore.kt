package com.lobster.lanenav.phone

import android.location.GnssStatus

/** 单颗卫星快照。 */
data class SatInfo(
    val svid: Int,
    /** GnssStatus.CONSTELLATION_* */
    val constellation: Int,
    /** 载噪比 dB-Hz */
    val cn0: Float,
    /** 方位角，0=北，顺时针 */
    val azimuth: Float,
    /** 仰角，0=地平线，90=天顶 */
    val elevation: Float,
    /** 是否参与定位解算 */
    val used: Boolean
) {
    val constellationName: String
        get() = when (constellation) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            GnssStatus.CONSTELLATION_GLONASS -> "GLO"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
            GnssStatus.CONSTELLATION_BEIDOU -> "北斗"
            GnssStatus.CONSTELLATION_GALILEO -> "GAL"
            GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
            else -> "其他"
        }

    /** 卫星编号，按各系统惯例加前缀。 */
    val label: String
        get() = when (constellation) {
            GnssStatus.CONSTELLATION_GPS -> "G$svid"
            GnssStatus.CONSTELLATION_BEIDOU -> "C$svid"
            GnssStatus.CONSTELLATION_GLONASS -> "R$svid"
            GnssStatus.CONSTELLATION_GALILEO -> "E$svid"
            GnssStatus.CONSTELLATION_QZSS -> "J$svid"
            GnssStatus.CONSTELLATION_SBAS -> "S$svid"
            GnssStatus.CONSTELLATION_IRNSS -> "I$svid"
            else -> "$svid"
        }
}

/**
 * 卫星快照仓库：服务写入，界面读取。
 *
 * 只保存最近一次 GnssStatus 回调的内容，不做历史。
 */
object GnssStore {

    @Volatile
    var sats: List<SatInfo> = emptyList()
        private set

    /** 最近一次更新的时间戳；0 表示从未收到过 */
    @Volatile
    var updatedAt: Long = 0L
        private set

    fun update(list: List<SatInfo>) {
        sats = list
        updatedAt = System.currentTimeMillis()
    }

    fun clear() {
        sats = emptyList()
        updatedAt = 0L
    }

    /** 某星座的 (参与定位数, 可见数)。 */
    fun countOf(name: String): Pair<Int, Int> {
        var used = 0
        var vis = 0
        for (s in sats) {
            if (s.constellationName == name) {
                vis++
                if (s.used) used++
            }
        }
        return used to vis
    }

    fun usedTotal(): Int = sats.count { it.used }

    fun visibleTotal(): Int = sats.size

    /** 数据是否新鲜（3 秒内） */
    fun fresh(): Boolean =
        updatedAt > 0 && System.currentTimeMillis() - updatedAt < 3000
}
