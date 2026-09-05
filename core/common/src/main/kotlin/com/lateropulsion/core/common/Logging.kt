package com.lateropulsion.core.common

import java.util.concurrent.CopyOnWriteArrayList

public enum class LogLevel { DEBUG, INFO, WARN, ERROR }

public interface LogSink {
    public fun log(level: LogLevel, tag: String, message: String, fields: Map<String, Any?>, throwable: Throwable?)
}

/**
 * Structured logging facade (REQ-SEC-004: no PHI in logs).
 *
 * Callers pass fields as key/value pairs, never by string interpolation of patient data.
 * [PhiGuard] rejects keys that name identifying fields; the CI scanner in tools/ci/phi_log_scan.py
 * enforces the same rule statically.
 */
public object LpLog {
    private val sinks = CopyOnWriteArrayList<LogSink>()

    @Volatile public var minLevel: LogLevel = LogLevel.DEBUG
    @Volatile public var strictPhiGuard: Boolean = true

    public fun addSink(sink: LogSink) { sinks += sink }
    public fun removeSink(sink: LogSink) { sinks -= sink }
    public fun clearSinks() { sinks.clear() }

    public fun d(tag: String, message: String, vararg fields: Pair<String, Any?>): Unit = log(LogLevel.DEBUG, tag, message, fields, null)
    public fun i(tag: String, message: String, vararg fields: Pair<String, Any?>): Unit = log(LogLevel.INFO, tag, message, fields, null)
    public fun w(tag: String, message: String, vararg fields: Pair<String, Any?>): Unit = log(LogLevel.WARN, tag, message, fields, null)
    public fun e(tag: String, message: String, throwable: Throwable? = null, vararg fields: Pair<String, Any?>): Unit =
        log(LogLevel.ERROR, tag, message, fields, throwable)

    private fun log(level: LogLevel, tag: String, message: String, fields: Array<out Pair<String, Any?>>, t: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        val map = LinkedHashMap<String, Any?>(fields.size)
        for ((k, v) in fields) {
            if (strictPhiGuard) PhiGuard.assertSafeKey(k)
            map[k] = v
        }
        for (s in sinks) s.log(level, tag, message, map, t)
    }
}

/** Keys that must never appear in a log line because their values identify a patient. */
public object PhiGuard {
    private val forbiddenTokens = Regex(
        "(^|_|\\.)(mrn|contact|phone|mobile|email|address|dob|date_?of_?birth|aadhaar|passport|surname)(_|$|\\.)",
        RegexOption.IGNORE_CASE,
    )
    private val forbiddenNames = Regex(
        "^name$|(^|_|\\.)(patient|first|last|full|given|family)_?name(_|$|\\.)|(^|\\.)(patient|identity)\\.name(_|$|\\.)",
        RegexOption.IGNORE_CASE,
    )

    /** `name` alone or a person-name variant is PHI; `protocol_name` and friends are not. */
    public fun isSafeKey(key: String): Boolean = !forbiddenTokens.containsMatchIn(key) && !forbiddenNames.containsMatchIn(key)

    public fun assertSafeKey(key: String) {
        check(isSafeKey(key)) { "Refusing to log PHI field '$key'" }
    }
}

public object Redaction {
    private const val KEEP = 8
    /** First 8 characters of an opaque UUID; enough to correlate a log, not enough to re-identify. */
    public fun shortId(id: String): String = if (id.length <= KEEP) id else id.substring(0, KEEP) + "…"
}

/** Prints to stdout; used in tests and tools. Android installs a Logcat sink in the app module. */
public class PrintlnSink : LogSink {
    override fun log(level: LogLevel, tag: String, message: String, fields: Map<String, Any?>, throwable: Throwable?) {
        val f = if (fields.isEmpty()) "" else " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        println("${level.name[0]}/$tag: $message$f")
        throwable?.printStackTrace()
    }
}
