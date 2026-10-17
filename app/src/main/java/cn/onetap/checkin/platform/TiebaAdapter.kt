package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import org.json.JSONObject
import java.security.MessageDigest

/** Per-forum ordinary signing only; no paid msign, repair-sign, or growth task. */
class TiebaAdapter : PlatformAdapter {
    override val platform = Platform.TIEBA
    private val net = NetClient(setOf("tiebac.baidu.com", "tieba.baidu.com"))
    private var lastSignNanos = 0L
    private data class Session(val uid: String, val name: String, val tbs: String)

    override fun validate(credential: String): Identity {
        val cookies = credentials(credential)
        val session = login(cookies)
        val targets = followedForums(cookies, session.uid)
        return Identity(session.uid, session.name, targets.ifEmpty {
            listOf(Target("tieba:none", "forum", "尚未关注贴吧", bound = false, metadata = mapOf("ownerUserId" to session.uid)))
        }, "已校验百度 UID；已分页获取 ${targets.size} 个关注贴吧，可在任务设置中全选或选择部分")
    }

    override fun execute(credential: String, target: Target): TaskResult {
        if (!target.bound) return TaskResult(TaskStatus.UNBOUND, "尚未关注此贴吧")
        requireTarget(target)
        val cookies = credentials(credential)
        val session = login(cookies)
        checkOwner(session, target)
        val state = querySigned(cookies, target)
        if (state?.status == TaskStatus.ALREADY) return state
        // Pacing, not captcha bypass: each account/target remains independently executable.
        synchronized(this) {
            val remaining = 5000L - (System.nanoTime() - lastSignNanos) / 1_000_000
            if (lastSignNanos != 0L && remaining > 0) Thread.sleep(remaining)
            lastSignNanos = System.nanoTime()
        }
        val response = appRequest("/c/c/forum/sign", mapOf("BDUSS" to cookies.getValue("BDUSS"),
            "_client_version" to VERSION, "kw" to target.metadata.getValue("forumName"), "tbs" to session.tbs))
        return parseSignResult(response)
    }

    override fun query(credential: String, target: Target): TaskResult? {
        if (!target.bound) return TaskResult(TaskStatus.UNBOUND, "尚未关注此贴吧")
        requireTarget(target)
        val cookies = credentials(credential)
        checkOwner(login(cookies), target)
        return querySigned(cookies, target)
    }

    private fun querySigned(cookies: Map<String, String>, target: Target): TaskResult? {
        // Current upstream caps forumGuide at 200. Absence is never evidence of unsigned.
        val response = try {
            // A pasted STOKEN could belong to a different account. Do not trust web state
            // until the web identity independently matches the app-protocol identity.
            val identity = net.get("https://tieba.baidu.com/mo/q/newmoindex?need_user=1", webHeaders(cookies))
            if (identity.opt("no")?.toString()?.toIntOrNull() != 0) {
                if (isManual(identity.optString("error")))
                    throw AdapterException(TaskStatus.MANUAL, "贴吧要求手动验证，请前往官方入口")
                return null
            }
            val webUid = identity.optJSONObject("data")?.optString("id")
            if (webUid.isNullOrEmpty()) return null
            if (webUid != target.metadata["ownerUserId"])
                throw AdapterException(TaskStatus.EXPIRED, "贴吧网页凭证与账号身份不一致，请重新导入同一账号的 Cookie")
            net.postForm("https://tieba.baidu.com/c/f/forum/forumGuide",
                mapOf("sort_type" to "3", "call_from" to "3"), webHeaders(cookies) + ("Subapp-Type" to "hybrid"))
        } catch (e: AdapterException) {
            if (e.status == TaskStatus.UNKNOWN) return null
            throw e
        } catch (_: java.io.IOException) {
            return null
        }
        if (errorCode(response) != 0) {
            val msg = response.optString("error_msg")
            if (isManual(msg)) throw AdapterException(TaskStatus.MANUAL, "贴吧要求手动验证，请前往官方入口", diagnostic(response))
            return null
        }
        val forums = response.optJSONArray("like_forum") ?: return null
        for (i in 0 until forums.length()) {
            val item = forums.optJSONObject(i) ?: continue
            if (item.optString("forum_id") != target.metadata["forumId"]) continue
            return when (item.opt("is_sign")?.toString()) {
                "1", "true" -> TaskResult(TaskStatus.ALREADY, "贴吧当前状态确认今日已签到", "tieba forumGuide is_sign=1")
                "0", "false" -> TaskResult(TaskStatus.PENDING, "贴吧当前状态确认今日尚未签到", "tieba forumGuide is_sign=0")
                else -> null
            }
        }
        return null
    }

    private fun login(cookies: Map<String, String>): Session {
        val response = appRequest("/c/s/login", mapOf("_client_version" to VERSION, "bdusstoken" to cookies.getValue("BDUSS")))
        requireSuccess(response, "身份校验")
        val user = response.optJSONObject("user")
            ?: throw AdapterException(TaskStatus.EXPIRED, "贴吧未返回已登录身份，请更新 BDUSS", diagnostic(response))
        val uid = user.optString("id")
        if (uid.toLongOrNull()?.let { it > 0 } != true)
            throw AdapterException(TaskStatus.EXPIRED, "贴吧登录失效，请更新 BDUSS", diagnostic(response))
        val tbs = response.optJSONObject("anti")?.optString("tbs").orEmpty()
        if (!Regex("[a-fA-F0-9]{26}").matches(tbs))
            throw AdapterException(TaskStatus.UNKNOWN, "贴吧未返回可用的请求校验信息，已停止", diagnostic(response))
        return Session(uid, user.optString("name").takeIf { it.isNotBlank() } ?: "贴吧用户 $uid", tbs)
    }

    private fun checkOwner(session: Session, target: Target) {
        if (target.metadata["ownerUserId"] != session.uid)
            throw AdapterException(TaskStatus.EXPIRED, "凭证对应账号已改变，请重新校验账号并选择贴吧")
    }

    private fun requireTarget(target: Target) {
        val id = target.metadata["forumId"]
        val name = target.metadata["forumName"]
        if (target.type != "forum" || id?.toLongOrNull()?.let { it > 0 } != true ||
            target.id != "tieba:forum:$id" || name.isNullOrBlank() || name.any { it.code < 32 })
            throw AdapterException(TaskStatus.UNKNOWN, "贴吧目标缺失或不一致，请重新校验账号并选择贴吧")
    }

    private fun followedForums(cookies: Map<String, String>, uid: String): List<Target> {
        val result = linkedMapOf<String, Target>()
        for (page in 1..100) {
            val response = appRequest("/c/f/forum/like", mapOf("BDUSS" to cookies.getValue("BDUSS"),
                "_client_version" to VERSION, "friend_uid" to uid, "page_no" to "$page", "page_size" to "200"))
            requireSuccess(response, "关注贴吧列表")
            val rawGroups = response.opt("forum_list")
            if (rawGroups !is JSONObject && !(rawGroups is org.json.JSONArray && rawGroups.length() == 0))
                throw AdapterException(TaskStatus.UNKNOWN, "贴吧列表结构变化，未保存不完整列表")
            val groups = response.optJSONObject("forum_list")
            var found = 0
            for (key in listOf("non-gconforum", "gconforum")) {
                if (groups != null && groups.has(key) && !groups.isNull(key) && groups.optJSONArray(key) == null)
                    throw AdapterException(TaskStatus.UNKNOWN, "贴吧列表分组结构变化，未保存不完整列表")
                val array = groups?.optJSONArray(key) ?: continue
                for (i in 0 until array.length()) {
                    val forum = array.optJSONObject(i) ?: throw AdapterException(TaskStatus.UNKNOWN, "贴吧列表结构变化，请更新适配器")
                    val id = forum.optString("id")
                    val name = forum.optString("name")
                    if (id.toLongOrNull()?.let { it > 0 } != true || name.isBlank())
                        throw AdapterException(TaskStatus.UNKNOWN, "贴吧列表缺少明确的吧名或 ID")
                    found++
                    result[id] = Target("tieba:forum:$id", "forum", "${name}吧", metadata = mapOf(
                        "ownerUserId" to uid, "forumId" to id, "forumName" to name,
                        "resetZone" to "Asia/Shanghai", "resetHour" to "0", "resetVerified" to "false",
                        "resetEvidence" to "官方页明确为每日签到；精确边界待真实账号跨日确认"
                    ))
                }
            }
            when (response.opt("has_more")?.toString()) {
                "0", "false" -> return result.values.toList()
                "1", "true" -> if (found == 0) throw AdapterException(TaskStatus.UNKNOWN, "贴吧分页未前进，未保存不完整列表")
                else -> throw AdapterException(TaskStatus.UNKNOWN, "贴吧分页标记缺失，未保存不完整列表")
            }
        }
        throw AdapterException(TaskStatus.UNKNOWN, "关注贴吧超过本次读取上限，未保存不完整列表")
    }

    private fun appRequest(path: String, fields: Map<String, String>): JSONObject =
        net.postForm("https://tiebac.baidu.com$path", signedFields(fields))

    private fun webHeaders(cookies: Map<String, String>) = mapOf(
        "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
        "Referer" to "https://tieba.baidu.com/"
    )

    internal fun credentials(input: String): Map<String, String> {
        val raw = input.trim().removePrefix("Cookie:").trim()
        if (raw.isBlank() || raw.any { it == '\r' || it == '\n' } || raw.length > 32768)
            throw AdapterException(TaskStatus.EXPIRED, "请输入 BDUSS 值或含 BDUSS 的 Cookie")
        val cookies = if (raw.contains("BDUSS=")) raw.split(';').mapNotNull { part ->
            val p = part.trim().split('=', limit = 2)
            if (p.size == 2 && p[0] in setOf("BDUSS", "STOKEN")) p[0] to p[1] else null
        }.toMap() else if (!raw.contains(';') && !raw.contains(' ')) mapOf("BDUSS" to raw) else emptyMap()
        if (cookies["BDUSS"].isNullOrBlank() || cookies.values.any { it.any { c -> c.code < 33 || c.code > 126 || c == ';' } })
            throw AdapterException(TaskStatus.EXPIRED, "Cookie 中缺少有效 BDUSS")
        return cookies
    }

    private fun requireSuccess(response: JSONObject, action: String) {
        if (errorCode(response) == 0) return
        val msg = response.optString("error_msg")
        val status = when {
            isManual(msg) -> TaskStatus.MANUAL
            msg.contains("未登录") || msg.contains("请先登录") || msg.contains("登录失效") || msg.contains("登录过期") || msg.contains("BDUSS", true) -> TaskStatus.EXPIRED
            else -> TaskStatus.UNKNOWN
        }
        throw AdapterException(status, when (status) {
            TaskStatus.MANUAL -> "贴吧要求手动验证，请前往官方入口"
            TaskStatus.EXPIRED -> "贴吧登录失效，请更新 BDUSS"
            else -> "贴吧${action}返回未识别结果，请在官方入口确认"
        }, diagnostic(response))
    }

    companion object {
        private const val VERSION = "22.12.1.0"
        private fun errorCode(result: JSONObject): Int? = result.opt("error_code")?.toString()?.toIntOrNull()
        private fun diagnostic(result: JSONObject) = "tieba error_code=${errorCode(result) ?: "missing"}"
        private fun isManual(msg: String) = msg.contains("验证码") || msg.contains("安全验证") || msg.contains("频繁")

        /** aiotieba/helper/crypto/sign.py, Unlicense: sort raw key/value pairs then MD5 with APP_SALT. */
        internal fun signedFields(fields: Map<String, String>): Map<String, String> {
            val raw = fields.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + "tiebaclient!!!"
            val sign = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return fields + ("sign" to sign)
        }

        fun parseSignResult(result: JSONObject): TaskResult {
            val code = errorCode(result)
            val diagnostic = diagnostic(result)
            val msg = result.optString("error_msg")
            return when {
                code == 160002 -> TaskResult(TaskStatus.ALREADY, "贴吧明确返回今日已经签到", diagnostic)
                code == 0 && (result.optJSONObject("user_info")?.opt("sign_bonus_point")?.toString()?.toIntOrNull() ?: 0) > 0 ->
                    TaskResult(TaskStatus.SUCCESS, "贴吧签到成功", diagnostic)
                isManual(msg) -> TaskResult(TaskStatus.MANUAL, "请在贴吧官方入口完成手动验证", diagnostic)
                msg.contains("未登录") || msg.contains("请先登录") || msg.contains("登录失效") || msg.contains("登录过期") ->
                    TaskResult(TaskStatus.EXPIRED, "贴吧登录失效，请更新 BDUSS", diagnostic)
                msg.contains("封禁") || msg.contains("禁止签到") || msg.contains("吧已被关闭") ->
                    TaskResult(TaskStatus.FAILED, "平台拒绝此吧签到（封禁、限制或关闭），请在贴吧查看详情", diagnostic)
                else -> TaskResult(TaskStatus.UNKNOWN, "贴吧返回未识别结果，请在官方入口确认", diagnostic)
            }
        }
    }
}
