package cn.onetap.checkin.sched

import cn.onetap.checkin.core.Settings

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

data class KeepAliveResult(val label: String, val ok: Boolean, val output: String)

internal fun shouldRunDaemon(settings: Settings, state: ShizukuState): Boolean =
    settings.shizukuDaemon && settings.auto && state == ShizukuState.READY
