package cn.onetap.checkin.core

enum class Platform(val title: String) {
    NETEASE("网易云音乐"), TIEBA("百度贴吧"), SKLAND("森空岛"), BILIBILI("哔哩哔哩")
}

enum class TaskStatus(val title: String) {
    SUCCESS("签到成功"), ALREADY("今日已签到"), UNBOUND("未绑定"),
    INELIGIBLE("不满足领取条件"), EXPIRED("登录失效"), MANUAL("需要手动验证"),
    FAILED("失败"), UNKNOWN("结果待确认"), NOT_CONNECTED("尚未接入"),
    RUNNING("执行中"), PENDING("待签到"), INTERRUPTED("执行中断，结果待确认")
}

data class Target(
    val id: String,
    val type: String,
    val label: String,
    val game: String = "",
    val server: String = "",
    val bound: Boolean = true,
    val metadata: Map<String, String> = emptyMap()
)

data class Identity(
    val userId: String,
    val displayName: String,
    val targets: List<Target>,
    val details: String = "",
    /** Refreshed official session, consumed locally and never exposed in diagnostics. */
    val updatedCredential: String? = null
)

data class TaskResult(
    val status: TaskStatus,
    val message: String,
    val diagnostic: String = "",
    val experienceState: String = ""
)

interface PlatformAdapter {
    val platform: Platform
    fun validate(credential: String): Identity
    fun execute(credential: String, target: Target): TaskResult
    /** Read only. Return null when no reliable status endpoint is known. */
    fun query(credential: String, target: Target): TaskResult? = null
}

class AdapterException(val status: TaskStatus, override val message: String, val diagnostic: String = "") : Exception(message)
