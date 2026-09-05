package com.lateropulsion.app.logging

import android.util.Log
import com.lateropulsion.core.common.LogLevel
import com.lateropulsion.core.common.LogSink

/** The only place android.util.Log is imported (tools/ci/phi_log_scan.py). Fields are already PHI-guarded by LpLog. */
class LogcatSink : LogSink {
    override fun log(level: LogLevel, tag: String, message: String, fields: Map<String, Any?>, throwable: Throwable?) {
        val f = if (fields.isEmpty()) "" else " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val t = "LP/$tag"
        when (level) {
            LogLevel.DEBUG -> Log.d(t, message + f)
            LogLevel.INFO -> Log.i(t, message + f)
            LogLevel.WARN -> Log.w(t, message + f)
            LogLevel.ERROR -> Log.e(t, message + f, throwable)
        }
    }
}
