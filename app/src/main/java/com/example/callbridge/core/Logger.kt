package com.example.callbridge.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class BridgeLogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val category: String,
    val message: String
) {
    fun formattedTime(): String {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
        return formatter.format(Date(timestampMs))
    }
}

/**
 * Thread-safe centralized logger that mirrors sanitized operational events to both
 * Android Logcat and an observable StateFlow for the dashboard RecyclerView.
 *
 * Phone numbers are automatically redacted via [maskPhoneNumber] so full numbers
 * are never exposed in logs.
 */
object Logger {

    private val sequence = AtomicLong(0L)
    private val lock = Any()
    private val entries = ArrayDeque<BridgeLogEntry>(AppConfig.MAX_EVENT_LOG_ENTRIES)

    private val _logs = MutableStateFlow<List<BridgeLogEntry>>(emptyList())
    val logs: StateFlow<List<BridgeLogEntry>> = _logs.asStateFlow()

    fun d(category: String, message: String) = append(LogLevel.DEBUG, category, message)
    fun i(category: String, message: String) = append(LogLevel.INFO, category, message)
    fun w(category: String, message: String) = append(LogLevel.WARN, category, message)
    fun e(category: String, message: String, throwable: Throwable? = null) {
        val detail = if (throwable != null) {
            "$message (${throwable.javaClass.simpleName}: ${throwable.message ?: "error"})"
        } else {
            message
        }
        append(LogLevel.ERROR, category, detail)
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            _logs.value = emptyList()
        }
    }

    /**
     * Masks a phone number for safe logging (keeps prefix/last 2 digits only).
     */
    fun maskPhoneNumber(raw: String?): String {
        if (raw.isNullOrBlank()) return "hidden"
        val cleaned = raw.trim()
        if (cleaned.length <= 4) return "****"
        val prefix = cleaned.take(3)
        val suffix = cleaned.takeLast(2)
        return "$prefix****$suffix"
    }

    private fun append(level: LogLevel, category: String, rawMessage: String) {
        val safeMessage = redactPotentialPhoneNumbers(rawMessage)
        val entry = BridgeLogEntry(
            id = sequence.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            level = level,
            category = category,
            message = safeMessage
        )

        synchronized(lock) {
            if (entries.size >= AppConfig.MAX_EVENT_LOG_ENTRIES) {
                entries.removeLast()
            }
            entries.addFirst(entry)
            _logs.value = entries.toList()
        }

        val tag = "${AppConfig.APP_TAG}/$category"
        runCatching {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, safeMessage)
                LogLevel.INFO -> Log.i(tag, safeMessage)
                LogLevel.WARN -> Log.w(tag, safeMessage)
                LogLevel.ERROR -> Log.e(tag, safeMessage)
            }
        }
    }

    private val phoneLikeRegex = Regex("(?<!\\d)(\\+?\\d[\\d\\-\\s]{6,14}\\d)(?!\\d)")

    internal fun redactPotentialPhoneNumbers(input: String): String {
        return phoneLikeRegex.replace(input) { match ->
            val candidate = match.value
            // Avoid redacting IPv4 addresses or pure short numbers
            if (candidate.contains('.')) candidate else maskPhoneNumber(candidate)
        }
    }
}
