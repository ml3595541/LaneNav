package com.lobster.lanenav.shared

import java.util.UUID

/**
 * BLE 通道协议定义。
 *
 * 角色: 手机 = GATT Server(广播)，盒子 = GATT Client(扫描连接)。
 *
 * 为什么需要分包: BLE 单次通知可写字节数 = MTU - 3。
 *   - 默认 MTU 23 → 20 字节
 *   - 协商 517 → 514 字节
 * 一条定位 JSON 约 150 字节，默认 MTU 下必须拆成多包。
 */
object BleProtocol {

    /** 主服务 UUID（盒子按此过滤扫描） */
    val SERVICE_UUID: UUID = UUID.fromString("5b1ea100-7e57-4b1a-9c3d-2f6a1b0c9d01")

    /** 定位数据特征：手机 notify → 盒子 */
    val CHAR_DATA_UUID: UUID = UUID.fromString("5b1ea101-7e57-4b1a-9c3d-2f6a1b0c9d01")

    /** 控制命令特征：盒子 write → 手机（sleep/wake） */
    val CHAR_CMD_UUID: UUID = UUID.fromString("5b1ea102-7e57-4b1a-9c3d-2f6a1b0c9d01")

    /** CCCD（客户端特征配置描述符），开启通知用 */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DEVICE_NAME = "LaneNav-Phone"

    /** 分包头长度: [frameId, chunkIndex, totalChunks] */
    const val HEADER_SIZE = 3

    /** 期望协商的 MTU，之后一条定位可单包发送 */
    const val TARGET_MTU = 517

    const val DEFAULT_MTU = 23

    /** 单包可承载的业务字节数 */
    fun maxPayload(mtu: Int): Int = (mtu - 3 - HEADER_SIZE).coerceAtLeast(1)
}

/**
 * 分包与重组。
 *
 * 分片格式:
 *   byte[0] = frameId      帧号（0-255 循环，用于区分并发帧）
 *   byte[1] = chunkIndex   片序号
 *   byte[2] = totalChunks  总片数
 *   byte[3..] = 业务数据
 */
object BleFrames {

    /** 把一条完整 JSON 拆成若干 BLE 包。 */
    fun split(payload: ByteArray, maxChunk: Int, frameId: Int): List<ByteArray> {
        val chunkSize = maxChunk.coerceAtLeast(1)
        val total = ((payload.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        if (total > 255) return emptyList()   // 极端情况，放弃该帧

        val out = ArrayList<ByteArray>(total)
        var offset = 0
        var index = 0
        while (offset < payload.size) {
            val len = minOf(chunkSize, payload.size - offset)
            val buf = ByteArray(BleProtocol.HEADER_SIZE + len)
            buf[0] = (frameId and 0xFF).toByte()
            buf[1] = index.toByte()
            buf[2] = total.toByte()
            System.arraycopy(payload, offset, buf, BleProtocol.HEADER_SIZE, len)
            out += buf
            offset += len
            index++
        }
        return out
    }

    /** 接收端重组器，喂入单包，凑齐一帧时返回完整字节，否则 null。 */
    class Assembler {
        private val buffers = HashMap<Int, Array<ByteArray?>>()
        private val totals = HashMap<Int, Int>()
        private val received = HashMap<Int, Int>()

        fun feed(chunk: ByteArray): ByteArray? {
            if (chunk.size < BleProtocol.HEADER_SIZE) return null
            val frameId = chunk[0].toInt() and 0xFF
            val index = chunk[1].toInt() and 0xFF
            val total = chunk[2].toInt() and 0xFF
            if (total == 0 || index >= total) return null

            var slots = buffers[frameId]
            if (slots == null || totals[frameId] != total) {
                slots = arrayOfNulls(total)
                buffers[frameId] = slots
                totals[frameId] = total
                received[frameId] = 0
            }

            if (slots[index] == null) {
                slots[index] = chunk.copyOfRange(BleProtocol.HEADER_SIZE, chunk.size)
                received[frameId] = (received[frameId] ?: 0) + 1
            }

            if ((received[frameId] ?: 0) >= total) {
                val out = java.io.ByteArrayOutputStream()
                for (s in slots) {
                    if (s == null) return null    // 有空洞，等待
                    out.write(s)
                }
                buffers.remove(frameId)
                totals.remove(frameId)
                received.remove(frameId)
                return out.toByteArray()
            }
            return null
        }

        fun reset() {
            buffers.clear()
            totals.clear()
            received.clear()
        }
    }
}
