package cn.onetap.checkin.platform

import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.HttpStatusException
import cn.onetap.checkin.core.Identity
import cn.onetap.checkin.core.NetClient
import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.core.PlatformAdapter
import cn.onetap.checkin.core.Target
import cn.onetap.checkin.core.TaskResult
import cn.onetap.checkin.core.TaskStatus
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class HeyboxAdapter internal constructor(
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val exchange: ((String, Map<String, String>, Map<String, String>) -> JSONObject)? = null
) : PlatformAdapter {
    override val platform = Platform.HEYBOX
    private val net = NetClient(setOf(HeyboxWebRequest.HOST))

    override fun validate(credential: String): Identity {
        val cookies = credentials(credential)
        val response = try {
            request(IDENTITY_PATH, cookies)
        } catch (_: IOException) {
            // The server may already have rotated pkey. Runner must not retry with the old Cookie.
            throw unknown("restore_login response unavailable")
        }
        rejection(response)?.let { throw it }
        val result = response.optJSONObject("result") ?: throw unknown("missing identity result")
        val profile = result.optJSONObject("profile")
        val profileId = positiveId(profile?.opt("heybox_id"))
        val accountId = positiveId(result.optJSONObject("account_detail")?.opt("userid"))
        val uid = profileId ?: accountId ?: throw unknown("missing server uid")
        if (profileId != null && accountId != null && profileId != accountId)
            throw unknown("conflicting server uid")
        if (cookies.getValue("heybox_id") != uid) throw expired("identity cookie mismatch")

        val pkey = (result.opt("pkey") as? String)?.takeIf(::validCookieValue)
            ?: throw unknown("missing server session")
        val wirePkey = cookieWireValue(pkey)
        val refreshed = cookies + mapOf("pkey" to wirePkey, "user_pkey" to wirePkey)
        val target = Target(TARGET_ID, TARGET_TYPE, "每日签到", metadata = mapOf(
            "ownerUserId" to uid, "resetZone" to "Asia/Shanghai", "resetHour" to "0",
            "resetVerified" to "false", "resetEvidence" to "以任务中心实时状态为准"
        ))
        val name = (profile?.opt("nickname") as? String)?.takeIf { it.isNotBlank() } ?: "小黑盒用户 $uid"
        return Identity(uid, name, listOf(target), updatedCredential = cookieHeader(refreshed))
    }

    override fun requiresFreshCompletionCheck(target: Target) = isSignTarget(target)

    override fun query(credential: String, target: Target): TaskResult {
        if (!isSignTarget(target)) return unknownState("unexpected target")
        val cookies = credentials(credential)
        checkOwner(cookies, target)
        return readState(cookies)
    }

    override fun execute(credential: String, target: Target): TaskResult {
        if (!isSignTarget(target)) return unknownState("unexpected target")
        val cookies = credentials(credential)
        checkOwner(cookies, target)
        val before = readState(cookies)
        if (before.status != TaskStatus.PENDING) return before

        val response = try {
            request(SIGN_PATH, cookies)
        } catch (error: AdapterException) {
            if (error.status != TaskStatus.UNKNOWN) throw error
            null
        }
        var signDiagnostic = ""
        var confirmedWrite = false
        if (response != null) {
            val rejected = rejection(response)
            if (rejected != null) {
                signDiagnostic = formatSignDiagnostic("status", response.opt("status"))
                if (rejected.status != TaskStatus.UNKNOWN)
                    throw AdapterException(rejected.status, rejected.message, signDiagnostic)
            } else {
                val state = response.optJSONObject("result")?.opt("state")
                confirmedWrite = state == "success"
                if (!confirmedWrite) signDiagnostic = formatSignDiagnostic("state", state)
            }
        }

        val unconfirmed = unknownState("sign still pending after write")
        var after: TaskResult? = null
        var readFailure = unconfirmed
        for (delay in READBACK_DELAYS) {
            if (delay > 0) pause(delay)
            val read = try {
                readState(cookies)
            } catch (error: AdapterException) {
                TaskResult(error.status, error.message, error.diagnostic)
            } catch (_: JSONException) {
                continue
            } catch (error: IOException) {
                if (Thread.currentThread().isInterrupted) throw error
                continue
            }
            if (read.status == TaskStatus.ALREADY) {
                return if (confirmedWrite) TaskResult(TaskStatus.SUCCESS, "签到成功")
                else read.copy(diagnostic = signDiagnostic)
            }
            if (read.status == TaskStatus.UNKNOWN) readFailure = read else after = read
        }
        val result = when (after?.status) {
            null -> readFailure
            TaskStatus.PENDING -> unconfirmed
            else -> after
        }
        return if (signDiagnostic.isEmpty()) result else result.copy(diagnostic = signDiagnostic)
    }

    private fun readState(cookies: Map<String, String>) = parseTaskState(request(TASK_PATH, cookies))

    private fun checkOwner(cookies: Map<String, String>, target: Target) {
        if (target.metadata["ownerUserId"] != cookies.getValue("heybox_id"))
            throw expired("target owner mismatch")
    }

    private fun request(path: String, cookies: Map<String, String>): JSONObject {
        val params = HeyboxWebRequest.parameters(path) + ("heybox_id" to cookies.getValue("heybox_id"))
        try {
            exchange?.let { return it(path, params, cookies) }
            return net.get(HeyboxWebRequest.url(path, params), HeyboxWebRequest.headers + ("Cookie" to cookieHeader(cookies)))
        } catch (error: HttpStatusException) {
            if (error.status == TaskStatus.EXPIRED)
                throw HttpStatusException(error.code, error.status, EXPIRED_MESSAGE, error.diagnostic)
            throw error
        }
    }

    internal fun credentials(input: String): Map<String, String> {
        if (input.length > 32768 || input.any { it == '\r' || it == '\n' }) throw expired("invalid cookie")
        val cookies = linkedMapOf<String, String>()
        for (part in input.trim().removePrefix("Cookie:").split(';')) {
            val (name, value) = part.trim().split('=', limit = 2).takeIf { it.size == 2 } ?: continue
            if (name !in COOKIE_NAMES) continue
            if (!validCookieValue(value) || cookies[name]?.let { it != value } == true)
                throw expired("invalid cookie")
            cookies[name] = value
        }
        fun normalizeAliases(name: String, alias: String): String {
            val value = cookies[name] ?: cookies[alias] ?: throw expired("missing cookie")
            if (cookies[alias]?.let { it != value } == true) throw expired("conflicting cookie aliases")
            cookies[name] = value
            cookies[alias] = value
            return value
        }
        normalizeAliases("pkey", "user_pkey")
        if (positiveId(normalizeAliases("heybox_id", "user_heybox_id")) == null)
            throw expired("invalid cookie uid")
        return cookies
    }

    companion object {
        const val TARGET_ID = "heybox:daily"
        const val TARGET_TYPE = "daily"
        const val IDENTITY_PATH = "/account/restore_login"
        const val TASK_PATH = "/task/list_v2/"
        const val SIGN_PATH = "/task/sign_v3/sign"
        internal val COOKIE_NAMES = setOf("pkey", "user_pkey", "heybox_id", "user_heybox_id", "x_xhh_tokenid")
        private val ID_PATTERN = Regex("[1-9][0-9]*")
        private val SIGN_DIAGNOSTIC_VALUE = Regex("[a-z0-9_]{1,32}")
        private val READBACK_DELAYS = longArrayOf(0, 1000, 1500, 2500)
        private const val EXPIRED_MESSAGE = "登录已失效，请重新扫码登录"
        private const val UNKNOWN_MESSAGE = "结果未确认，请稍后重试"

        internal fun parseTaskState(response: JSONObject): TaskResult {
            rejection(response)?.let { return TaskResult(it.status, it.message, it.diagnostic) }
            val groups = response.optJSONObject("result")?.optJSONArray("task_list")
                ?: return unknownState("missing task list")
            var signTask: JSONObject? = null
            for (i in 0 until groups.length()) {
                val tasks = groups.optJSONObject(i)?.optJSONArray("tasks") ?: return unknownState("invalid task group")
                for (j in 0 until tasks.length()) {
                    val task = tasks.optJSONObject(j) ?: return unknownState("invalid task object")
                    if (task.opt("type") != "sign") continue
                    if (signTask != null) return unknownState("ambiguous sign task")
                    signTask = task
                }
            }
            return when (signTask?.opt("state")) {
                "finish" -> TaskResult(TaskStatus.ALREADY, "今日已签到")
                "todo" -> TaskResult(TaskStatus.PENDING, "今日尚未签到")
                else -> unknownState("missing or unrecognized sign state")
            }
        }

        private fun rejection(response: JSONObject): AdapterException? = when (response.opt("status")) {
            "ok" -> null
            "login", "relogin" -> expired("auth required")
            "need_google_check", "show_captcha", "lack_token", "name_verify", "need_alipay_verify", "need_bind_phone", "need_phone_code" ->
                AdapterException(TaskStatus.MANUAL, "请在小黑盒完成验证后重试", "heybox manual verification required")
            else -> unknown("unrecognized business status")
        }

        private fun formatSignDiagnostic(field: String, value: Any?): String {
            val safeValue = (value as? String)?.takeIf(SIGN_DIAGNOSTIC_VALUE::matches) ?: "other"
            return "heybox sign $field=$safeValue"
        }

        internal fun positiveId(value: Any?): String? = when (value) {
            is String, is Number -> value.toString().takeIf(ID_PATTERN::matches)
            else -> null
        }
        private fun validCookieValue(value: String) = value.isNotEmpty() && value.all { it.code in 33..126 && it != ';' }
        private fun cookieHeader(cookies: Map<String, String>) = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        private fun isSignTarget(target: Target) = target.id == TARGET_ID && target.type == TARGET_TYPE
        private fun expired(code: String) = AdapterException(TaskStatus.EXPIRED, EXPIRED_MESSAGE, "heybox $code")
        private fun unknown(code: String) = AdapterException(TaskStatus.UNKNOWN, UNKNOWN_MESSAGE, "heybox $code")
        private fun unknownState(code: String) = TaskResult(TaskStatus.UNKNOWN, UNKNOWN_MESSAGE, "heybox $code")

        /** The login script uses JavaScript escape() for the raw pkey. Imported Cookies are already encoded. */
        internal fun cookieWireValue(value: String): String = buildString {
            for (character in value) {
                if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "@*_+-./")
                    append(character)
                else append('%').append(character.code.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
