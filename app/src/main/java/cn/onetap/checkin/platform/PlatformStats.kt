package cn.onetap.checkin.platform

import cn.onetap.checkin.core.Stat
import cn.onetap.checkin.core.TaskResult
import org.json.JSONObject

internal fun JSONObject.statLong(field: String): Long? = when (val value = opt(field)) {
    is Number, is String -> value.toString().toLongOrNull()?.takeIf { it >= 0 }
    else -> null
}

internal fun progressStat(label: String, current: Long?, total: Long?): Stat? =
    if (current != null && total != null && total > 0 && current <= total)
        Stat(label, "", current, total)
    else null

internal fun TaskResult.withUnrecognizedStats(fields: Collection<String>): TaskResult {
    val note = "stats 未识别：字段 ${fields.distinct().joinToString(",")}"
    return copy(diagnostic = listOf(diagnostic, note).filter { it.isNotEmpty() }.joinToString("; "))
}
