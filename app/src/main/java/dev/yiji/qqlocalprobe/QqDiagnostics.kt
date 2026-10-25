package dev.yiji.qqlocalprobe

import android.content.Context

object QqDiagnostics {
    fun read(context: Context): String = TraceLog.read(context)
    fun error(context: Context, phase: String, error: Throwable) {
        val frames=error.stackTrace.take(8).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        TraceLog.line(context,"[$phase] ${error.javaClass.simpleName} $frames")
    }
}
