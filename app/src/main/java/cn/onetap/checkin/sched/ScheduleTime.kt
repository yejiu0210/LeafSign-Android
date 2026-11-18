package cn.onetap.checkin.sched

import java.time.Instant
import java.time.ZoneId

// Kept separate from AppState/Android code so the shell UserService can use this directly.
internal val scheduleZone: ZoneId = ZoneId.of("Asia/Shanghai")

internal fun nextTrigger(nowMillis: Long, hour: Int, minute: Int): Long {
    val now = Instant.ofEpochMilli(nowMillis).atZone(scheduleZone)
    var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    if (!next.isAfter(now)) next = next.plusDays(1)
    return next.toInstant().toEpochMilli()
}

internal fun todayDate(nowMillis: Long): String = Instant.ofEpochMilli(nowMillis).atZone(scheduleZone).toLocalDate().toString()
