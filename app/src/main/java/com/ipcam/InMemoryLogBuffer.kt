package com.ipcam

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Thread-safe in-memory circular buffer that captures app log entries.
 * Allows reading recent logs via HTTP without requiring ADB or Android Studio.
 */
object InMemoryLogBuffer {
    private const val MAX_ENTRIES = 500
    private val buffer = ArrayDeque<String>()
    private val lock = Any()
    // DateTimeFormatter is thread-safe (immutable), unlike SimpleDateFormat
    private val dateFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)

    fun add(level: String, tag: String, message: String) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val entry = "$timestamp $level/$tag: $message"
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
        }
    }

    fun getEntries(): List<String> = synchronized(lock) { buffer.toList() }

    fun getText(): String = getEntries().joinToString("\n")

    fun clear() = synchronized(lock) { buffer.clear() }
}
