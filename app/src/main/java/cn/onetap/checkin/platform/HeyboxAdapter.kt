package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Only the ordinary daily sign task. No replenishment, rewards, sharing, or other task writes. */
class HeyboxAdapter internal constructor(
    private val store: StateStore? = null,
    private val exchange: ((String, Map<String, String>, Map<String, String>) -> JSONObject)? = null
) : PlatformAdapter {
    override val platform = Platform.HEYBOX
    private val net = NetClient(setOf("api.xiaoheihe.cn"))

    override fun validate(credential: String): Identity {
        val cookies = credentials(credential)
        val response = request(IDENTITY_PATH, cookies)
        rejection(response)?.let { throw it }
        val result = response.optJSONObject("result")
            ?: throw unknown("小黑盒未返回有效账号信息，请重新网页登录", "identity missing result")
        val profile = result.optJSONObject("profile")
        val profileId = positiveId(profile?.opt("heybox_id"))
        val accountId = positiveId(result.optJSONObject("account_detail")?.opt("userid"))
        val uid = profileId ?: accountId
            ?: throw unknown("小黑盒未返回可验证的账号 ID，请重新网页登录", "identity missing server uid")
        if (profileId != null && accountId != null && profileId != accountId)
            throw unknown("小黑盒返回的账号身份不一致，已停止执行", "identity conflicting server uid")
        // The official restore-login contract requires a server-issued pkey as well as an account ID.
        val refreshedPkey = result.opt("pkey") as? String
        if (refreshedPkey.isNullOrBlank() || refreshedPkey.any { it.code < 33 || it.code > 126 || it == ';' })
            throw unknown("小黑盒未确认有效登录会话，请重新网页登录", "identity missing server session")
        val cookieId = cookies["heybox_id"] ?: cookies["user_heybox_id"]
        if (cookieId != null && cookieId != uid)
            throw AdapterException(TaskStatus.EXPIRED, "小黑盒登录身份与当前凭证不一致，请重新登录", "heybox identity cookie mismatch")
        // restore_login refreshes pkey. Use the server's new session for every following operation.
        val refreshedCookies = cookies.toMutableMap().apply {
            put("pkey", refreshedPkey); put("user_pkey", refreshedPkey)
        }
        val refreshedCredential = refreshedCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (refreshedCredential != credential) store?.update { state ->
            state.copy(accounts = state.accounts.map { account ->
                // Compare and replace avoids overwriting a credential the user has just changed.
                if (account.platform == platform && account.userId == uid && account.credential == credential)
                    account.copy(credential = refreshedCredential)
                else account
            })
        }
        return Identity(uid, (profile?.opt("nickname") as? String)?.takeIf { it.isNotBlank() } ?: "小黑盒用户 $uid",
            listOf(Target(TARGET_ID, TARGET_TYPE, "每日签到", metadata = mapOf(
                "ownerUserId" to uid, "resetZone" to "Asia/Shanghai", "resetHour" to "0",
                "resetVerified" to "false", "resetEvidence" to "每日签到；以任务中心实时状态确认，精确重置时刻待验证"
            ))), "已校验小黑盒账号；只执行每日签到，并从任务中心复查结果", updatedCredential = refreshedCredential)
    }

    override fun query(credential: String, target: Target): TaskResult {
        if (!validTarget(target)) return wrongTarget()
        val identity = validate(credential)
        if (target.metadata["ownerUserId"] != identity.userId) return wrongOwner()
        return readState(credentials(identity.updatedCredential ?: credential), identity.userId)
    }

    override fun execute(credential: String, target: Target): TaskResult {
        if (!validTarget(target)) return wrongTarget()
        val identity = validate(credential)
        if (target.metadata["ownerUserId"] != identity.userId) return wrongOwner()
        val cookies = credentials(identity.updatedCredential ?: credential)
        val before = readState(cookies, identity.userId)
        // Unknown or missing task state must never cause a speculative write.
        if (before.status != TaskStatus.PENDING) return before
        val written = request(SIGN_PATH, cookies, identity.userId)
        val writeError = rejection(written)
        if (writeError?.status in setOf(TaskStatus.EXPIRED, TaskStatus.MANUAL)) throw writeError!!
        val after = readState(cookies, identity.userId)
        return when (after.status) {
            TaskStatus.ALREADY -> if (writeError == null && written.optJSONObject("result")?.opt("state") == "success")
                TaskResult(TaskStatus.SUCCESS, "小黑盒每日签到成功，任务中心已确认完成", "heybox sign status=ok; task sign=finish")
            else TaskResult(TaskStatus.ALREADY, "任务中心已确认今日签到完成；本次写入结果未单独确认", "heybox write unconfirmed; task sign=finish")
            TaskStatus.PENDING -> TaskResult(TaskStatus.UNKNOWN, "已请求小黑盒签到，任务中心尚未确认完成，请稍后查询", "heybox after-write task sign=todo")
            else -> after
        }
    }

    private fun readState(cookies: Map<String, String>, uid: String) = parseTaskState(request(TASK_PATH, cookies, uid))

    private fun request(path: String, cookies: Map<String, String>, verifiedUserId: String? = null): JSONObject {
        require(path in setOf(IDENTITY_PATH, TASK_PATH, SIGN_PATH))
        val params = linkedMapOf("app" to "heybox", "os_type" to "web", "x_app" to "heybox_website",
            "x_client_type" to "web", "client_type" to "web", "x_os_type" to "Android", "web_version" to "3.0")
        (verifiedUserId ?: cookies["heybox_id"] ?: cookies["user_heybox_id"])?.let { params["heybox_id"] = it }
        params.putAll(HeyboxWebSigning.parameters(path))
        exchange?.let { return it(path, params, cookies) }
        val url = "https://api.xiaoheihe.cn$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        return net.get(url.toString(), mapOf(
            "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            "Referer" to "https://www.xiaoheihe.cn/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
        ))
    }

    internal fun credentials(input: String): Map<String, String> {
        val raw = input.trim().removePrefix("Cookie:").trim()
        if (raw.isEmpty() || raw.length > 32768 || raw.any { it == '\r' || it == '\n' })
            throw invalidCredential()
        val cookies = linkedMapOf<String, String>()
        for (part in raw.split(';')) {
            val pair = part.trim().split('=', limit = 2)
            if (pair.size != 2 || pair[0] !in COOKIE_NAMES) continue
            val value = pair[1]
            if (value.isBlank() || value.any { it.code < 33 || it.code > 126 || it == ';' } ||
                (cookies.containsKey(pair[0]) && cookies[pair[0]] != value)) throw invalidCredential()
            cookies[pair[0]] = value
        }
        val pkey = cookies["pkey"] ?: cookies["user_pkey"] ?: throw invalidCredential()
        val userId = cookies["heybox_id"] ?: cookies["user_heybox_id"] ?: throw invalidCredential()
        if ((cookies["pkey"] != null && cookies["user_pkey"] != null && cookies["pkey"] != cookies["user_pkey"]) ||
            (cookies["heybox_id"] != null && cookies["user_heybox_id"] != null && cookies["heybox_id"] != cookies["user_heybox_id"]) ||
            positiveId(userId) == null) throw invalidCredential()
        // Same aliases the official login center sets. Only this platform's exact names are forwarded.
        cookies["pkey"] = pkey; cookies["user_pkey"] = pkey
        cookies["heybox_id"] = userId; cookies["user_heybox_id"] = userId
        return cookies
    }

    companion object {
        const val TARGET_ID = "heybox:daily"
        const val TARGET_TYPE = "daily"
        const val IDENTITY_PATH = "/account/restore_login"
        const val TASK_PATH = "/task/list_v2/"
        const val SIGN_PATH = "/task/sign_v3/sign"
        val COOKIE_NAMES = setOf("pkey", "user_pkey", "heybox_id", "user_heybox_id", "x_xhh_tokenid")

        fun parseTaskState(response: JSONObject): TaskResult {
            rejection(response)?.let { return TaskResult(it.status, it.message, it.diagnostic) }
            val list = response.optJSONObject("result")?.optJSONArray("task_list")
                ?: return unknownState("missing task_list")
            val signTasks = mutableListOf<JSONObject>()
            for (i in 0 until list.length()) {
                val tasks = list.optJSONObject(i)?.optJSONArray("tasks") ?: return unknownState("invalid task group")
                for (j in 0 until tasks.length()) {
                    val task = tasks.optJSONObject(j) ?: return unknownState("invalid task object")
                    if (task.opt("type") == "sign") signTasks += task
                }
            }
            if (signTasks.size != 1) return unknownState("missing or ambiguous sign task")
            return when (signTasks.single().opt("state")) {
                "finish" -> TaskResult(TaskStatus.ALREADY, "小黑盒今日已签到（任务中心已确认）", "heybox task sign=finish")
                "todo" -> TaskResult(TaskStatus.PENDING, "小黑盒今日尚未签到（任务中心已确认）", "heybox task sign=todo")
                else -> unknownState("unrecognized sign state")
            }
        }

        private fun rejection(response: JSONObject): AdapterException? = when (response.opt("status")) {
            "ok" -> null
            "login", "relogin" -> AdapterException(TaskStatus.EXPIRED, "小黑盒登录失效，请重新网页登录", "heybox auth required")
            "need_google_check", "show_captcha", "lack_token", "name_verify", "need_alipay_verify", "need_bind_phone", "need_phone_code" ->
                AdapterException(TaskStatus.MANUAL, "小黑盒要求手动验证，请在官方登录页面完成后重试", "heybox manual verification required")
            else -> unknown("小黑盒返回未识别的业务结果，请在官方任务中心确认", "unrecognized business status")
        }
        private fun positiveId(value: Any?): String? = when (value) {
            is String -> value.takeIf { Regex("[1-9][0-9]*").matches(it) }
            is Number -> value.toString().takeIf { Regex("[1-9][0-9]*").matches(it) }
            else -> null
        }
        private fun unknown(message: String, code: String) = AdapterException(TaskStatus.UNKNOWN, message, "heybox $code")
        private fun unknownState(code: String) = TaskResult(TaskStatus.UNKNOWN, "小黑盒任务中心未能确认今日签到状态，请在官方入口查看", "heybox $code")
        private fun invalidCredential() = AdapterException(TaskStatus.EXPIRED, "请重新网页登录，或导入同一账号的 heybox_id 和 pkey Cookie", "heybox invalid cookie")
        private fun validTarget(target: Target) = target.id == TARGET_ID && target.type == TARGET_TYPE
        private fun wrongTarget() = TaskResult(TaskStatus.UNKNOWN, "小黑盒签到任务类型不匹配，已停止")
        private fun wrongOwner() = TaskResult(TaskStatus.EXPIRED, "小黑盒凭证对应账号已改变，请重新校验并选择任务")
    }
}
