package com.lobster.lanenav.phone

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 运行日志环形缓冲。
 *
 * 只保留在本次进程内，退出即丢。上限 500 条，超出丢最旧的。
 * 用 version 计数让界面判断是否需要重绘，避免每秒全量比较字符串。
 */
object LogStore {

    private const val CAP = 500

    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 每次内容变化自增；界面据此判断要不要刷新 */
    @Volatile
    var version: Int = 0
        private set

    fun add(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        synchronized(buf) {
            if (buf.size >= CAP) buf.removeFirst()
            buf.addLast(line)
            version++
        }
    }

    fun lines(): List<String> = synchronized(buf) { buf.toList() }

    fun text(): String = lines().joinToString("\n")

    fun clear() {
        synchronized(buf) {
            buf.clear()
            version++
        }
    }

    fun size(): Int = synchronized(buf) { buf.size }
}
