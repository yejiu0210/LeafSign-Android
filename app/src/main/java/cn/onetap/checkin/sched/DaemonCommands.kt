package cn.onetap.checkin.sched

import java.util.concurrent.TimeUnit

/** No shell interpreter or caller-provided command text. The package comes only from BuildConfig. */
internal fun keepAliveCommand(commandId: Int, pkg: String): List<String>? = when (commandId) {
    0 -> listOf("/system/bin/cmd", "deviceidle", "whitelist", "+$pkg")
    1 -> listOf("/system/bin/cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow")
    2 -> listOf("/system/bin/cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow")
    3 -> listOf("/system/bin/cmd", "appops", "set", pkg, "SCHEDULE_EXACT_ALARM", "allow")
    4 -> listOf("/system/bin/am", "set-standby-bucket", pkg, "active")
    5 -> listOf("/system/bin/pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS")
    6 -> listOf("/system/bin/cmd", "appops", "set", pkg, "10008", "allow")
    else -> null
}

internal data class DaemonCommandResult(val ok: Boolean, val output: String) {
    fun reply(): String = (if (ok) "ok\n" else "fail\n") + output.take(500)
}

internal fun runDaemonCommand(command: List<String>): DaemonCommandResult {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = StringBuilder()
    var hasError = false
    // Drain even after the retained prefix is full, so a noisy command cannot block on its pipe.
    val reader = Thread({
        try {
            process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(256)
                var tail = ""
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val chunk = String(buffer, 0, count)
                    synchronized(output) {
                        if ((tail + chunk).contains("Error", ignoreCase = true)) hasError = true
                        if (output.length < 500) output.append(chunk.take(500 - output.length))
                    }
                    tail = chunk.takeLast(4)
                }
            }
        } catch (_: java.io.IOException) { /* The timeout path closes the pipe. */ }
    }, "checkin-command-output").apply { isDaemon = true; start() }
    try {
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(200)
        return synchronized(output) {
            if (!finished) DaemonCommandResult(false, ("命令超时\n$output").take(500))
            else if (reader.isAlive) DaemonCommandResult(false, ("输出读取未完成\n$output").take(500))
            else DaemonCommandResult(process.exitValue() == 0 && !hasError, output.toString())
        }
    } finally {
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.errorStream.close() }
    }
}
