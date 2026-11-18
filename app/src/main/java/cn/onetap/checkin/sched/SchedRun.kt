package cn.onetap.checkin.sched

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SchedRun(val time: Long, val source: String, val summary: String)

internal object SchedRunLog {
    @Synchronized fun noNetworkOnce(context: Context) {
        val prefs = context.getSharedPreferences("sched_runs", Context.MODE_PRIVATE)
        val today = todayDate(System.currentTimeMillis())
        if (prefs.getString("no_network_date", "") == today) return
        if (prefs.edit().putString("no_network_date", today).commit())
            add(context, "backup", "跳过：暂无网络，下次整点再试")
    }

    @Synchronized fun recent(context: Context): List<SchedRun> = try {
        val rows = JSONArray(context.getSharedPreferences("sched_runs", Context.MODE_PRIVATE).getString("runs", "[]"))
        (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            SchedRun(it.getLong("time"), it.getString("source"), it.getString("summary"))
        } }.take(20)
    } catch (_: Exception) { emptyList() }

    @Synchronized fun add(context: Context, source: String, summary: String) {
        val rows = listOf(SchedRun(System.currentTimeMillis(), source, summary)) + recent(context).take(19)
        val json = JSONArray().apply { rows.forEach { row -> put(JSONObject().apply {
            put("time", row.time); put("source", row.source); put("summary", row.summary)
        }) } }
        context.getSharedPreferences("sched_runs", Context.MODE_PRIVATE).edit().putString("runs", json.toString()).apply()
    }
}
