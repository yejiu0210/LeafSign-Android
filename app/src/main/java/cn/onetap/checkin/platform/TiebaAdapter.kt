package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Per-forum ordinary signing and one opt-in daily like per forum; no paid msign or repair-sign. */
class TiebaAdapter internal constructor(
    private val store: StateStore?,
    private val net: TiebaTransport,
    private val pause: (Long) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) : PlatformAdapter {
    constructor(store: StateStore? = null) : this(store, OfficialTiebaTransport(), { Thread.sleep(it) })

    override val platform = Platform.TIEBA
    override val maxTargetConcurrency = 1
    override fun executionOrder(target: Target): Int =
        if (target.type == "daily-like" || target.id.startsWith(TiebaLikeTask.TARGET_PREFIX)) 1 else 0
    private class WritePace { var last: Long? = null }
    private val writePaces = ConcurrentHashMap<String, WritePace>()
    private val likeTask by lazy { TiebaLikeTask(store, ::appRequest, pace = ::paceWrites) }
    private data class Session(val uid: String, val name: String, val tbs: String)
    private data class Cached<T>(val value: T, val at: Long)
    private inner class ReadCache<T>(private val ttl: Long) {
        private val entries = ConcurrentHashMap<String, Cached<T>>()
        fun get(key: String): T? {
            val entry = entries[key] ?: return null
            return if (clock() - entry.at in 0 until ttl) entry.value else {
                entries.remove(key, entry); null
            }
        }
        fun put(key: String, value: T): T {
            val now = clock()
            entries.entries.removeIf { now - it.value.at !in 0 until ttl }
            entries[key] = Cached(value, now)
            return value
        }
        fun remove(key: String) { entries.remove(key) }
    }
    private val sessions = ReadCache<Session>(300_000)
    private val webIdentities = ReadCache<String>(300_000)
    private val followed = ReadCache<List<Target>>(300_000)
    private val guides = ReadCache<Map<String, String>>(20_000)
    // Cache keys contain only digests. STOKEN changes must invalidate independent web ownership checks.
    private fun cacheKey(cookies: Map<String, String>, web: Boolean = false): String =
        MessageDigest.getInstance("SHA-256").digest((cookies.getValue("BDUSS") +
            if (web) "\u0000" + cookies["STOKEN"].orEmpty() else "").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    override fun validate(credential: String): Identity {
        val cookies = credentials(credential)
        invalidateWeb(cookies, identity = true)
        val session = login(cookies, forceFresh = true)
        val targets = followedForums(cookies, session.uid, forceFresh = true)
        val tasks=if(targets.isEmpty()) listOf(
            Target("tieba:none", "forum", "尚未关注贴吧", bound = false, metadata = mapOf("ownerUserId" to session.uid)),
            TiebaLikeTask.target(session.uid,"none","",false)
        ) else targets.flatMap { forum -> listOf(forum,TiebaLikeTask.target(session.uid,forum.metadata.getValue("forumId"),forum.metadata.getValue("forumName"))) }
        return Identity(session.uid,session.name,tasks,
            "已校验百度 UID；已分页获取 ${targets.size} 个关注贴吧。每个吧可单独启用每日随机点赞一帖；已赞则此帖最多取消一次再赞。点赞完成后记录任务完成，不额外查询经验到账。")
    }

    override fun requiresFreshCompletionCheck(target: Target): Boolean = target.bound && target.type == "forum"

    override fun execute(credential: String, target: Target): TaskResult {
        if (target.type == "daily-like" || target.id.startsWith(TiebaLikeTask.TARGET_PREFIX)) {
            if (!target.bound) return TaskResult(TaskStatus.UNBOUND, "尚未关注贴吧，无法选择每日点赞帖子")
            val cookies=credentials(credential);val session=login(cookies);checkOwner(session,target)
            return likeTask.execute(session.uid,cookies.getValue("BDUSS"),session.tbs,target,followedForums(cookies,session.uid))
        }
        if (!target.bound) return TaskResult(TaskStatus.UNBOUND, "尚未关注此贴吧")
        requireTarget(target)
        val cookies = credentials(credential)
        val session = login(cookies)
        checkOwner(session, target)
        val state = querySigned(cookies, target)
        if (state?.status == TaskStatus.ALREADY) return state
        return signAndCheck(cookies, session, target)
    }

    private fun signAndCheck(cookies: Map<String, String>, session: Session, target: Target,
        previousFailure: String? = null): TaskResult {
        paceWrites(session.uid)
        // Even a timed-out write may have reached the server. Reconciliation must not use old PENDING.
        invalidateWeb(cookies)
        var writeFailure: String? = null
        val acknowledgement = try {
            parseSignResult(appRequest("/c/c/forum/sign", mapOf("BDUSS" to cookies.getValue("BDUSS"),
                "_client_version" to VERSION, "kw" to target.metadata.getValue("forumName"), "tbs" to session.tbs)))
        } catch (e: Exception) {
            if (!isTransient(e)) throw e
            val diagnostic = transportDiagnostic(e)
            writeFailure = diagnostic
            TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", diagnostic)
        }
        if (acknowledgement.status in setOf(TaskStatus.EXPIRED, TaskStatus.MANUAL, TaskStatus.FAILED)) return acknowledgement
        val checked = checkAfterSign(cookies, target, writeFailure != null)
        val diagnostic = writeFailure ?: previousFailure
        if (checked?.status == TaskStatus.ALREADY) {
            val status = if (acknowledgement.status == TaskStatus.SUCCESS) TaskStatus.SUCCESS else TaskStatus.ALREADY
            return TaskResult(status, if (status == TaskStatus.SUCCESS) "签到成功" else "今日已签到",
                diagnostic ?: checked.diagnostic)
        }
        if (writeFailure != null && previousFailure == null && checked?.status == TaskStatus.PENDING)
            return signAndCheck(cookies, session, target, previousFailure = writeFailure)
        return TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试",
            diagnostic ?: checked?.diagnostic ?: acknowledgement.diagnostic)
    }

    private fun checkAfterSign(cookies: Map<String, String>, target: Target, ambiguousWrite: Boolean): TaskResult? {
        var checked: TaskResult? = null
        var transportFailure: TaskResult? = null
        repeat(3) { attempt ->
            if (attempt > 0) {
                val baseDelay = if (ambiguousWrite || transportFailure != null) READ_RETRY_DELAY_MS else 400L
                pause(baseDelay * attempt)
            }
            val state = try {
                // The polling loop owns the retry budget after a write.
                querySigned(cookies, target, forceFresh = true, retryReads = false)
            } catch (e: IOException) {
                if (Thread.currentThread().isInterrupted) throw e
                TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", transportDiagnostic(e))
            }
            if (state?.status == TaskStatus.ALREADY) return state
            if (state?.status == TaskStatus.UNKNOWN) transportFailure = state
            checked = state
        }
        return checked ?: transportFailure
    }

    override fun query(credential: String, target: Target): TaskResult? {
        if (target.type == "daily-like" || target.id.startsWith(TiebaLikeTask.TARGET_PREFIX)) {
            if (!target.bound) return TaskResult(TaskStatus.UNBOUND,"尚未关注贴吧，无法选择每日点赞帖子")
            val cookies=credentials(credential);val session=login(cookies);checkOwner(session,target)
            return likeTask.query(session.uid,cookies.getValue("BDUSS"),target)
        }
        if (!target.bound) return TaskResult(TaskStatus.UNBOUND, "尚未关注此贴吧")
        requireTarget(target)
        val cookies = credentials(credential)
        checkOwner(login(cookies), target)
        return querySigned(cookies, target)
    }

    internal fun paceWrites(uid: String) {
        val pace = writePaces.computeIfAbsent(uid) { WritePace() }
        synchronized(pace) {
            pace.last?.let { last ->
                val remaining = WRITE_INTERVAL_MS - (clock() - last)
                if (remaining > 0) pause(remaining)
            }
            pace.last = clock()
        }
    }

    private fun invalidateWeb(cookies: Map<String, String>, identity: Boolean = false) {
        val key = cacheKey(cookies, web = true)
        if (identity) webIdentities.remove(key)
        guides.remove(key)
    }

    private fun querySigned(cookies: Map<String, String>, target: Target, forceFresh: Boolean = false,
        retryReads: Boolean = true): TaskResult? {
        val key = cacheKey(cookies, web = true)
        if (forceFresh) invalidateWeb(cookies)
        // Current upstream caps forumGuide at 200. Absence is never evidence of unsigned.
        val forums = try {
            // A pasted STOKEN could belong to a different account. Do not trust web state
            // until the web identity independently matches the app-protocol identity.
            val webUid = webIdentities.get(key) ?: run {
                val identity = readRequest(retryReads) {
                    net.get("https://tieba.baidu.com/mo/q/newmoindex?need_user=1", webHeaders(cookies))
                }
                if (identity.opt("no")?.toString()?.toIntOrNull() != 0) {
                    val message = identity.optString("error")
                    if (isManual(message))
                        throw AdapterException(TaskStatus.MANUAL, "需要在贴吧 App 完成验证")
                    if (isExpired(message))
                        throw AdapterException(TaskStatus.EXPIRED, "登录已失效，请重新登录")
                    return null
                }
                val uid = identity.optJSONObject("data")?.optString("id")
                if (uid.isNullOrEmpty()) return null
                webIdentities.put(key, uid)
            }
            if (webUid != target.metadata["ownerUserId"])
                throw AdapterException(TaskStatus.EXPIRED, "贴吧网页凭证与账号身份不一致，请重新导入同一账号的 Cookie")
            (if (forceFresh) null else guides.get(key)) ?: run {
                val response = readRequest(retryReads) {
                    net.postForm("https://tieba.baidu.com/c/f/forum/forumGuide",
                        mapOf("sort_type" to "3", "call_from" to "3"), webHeaders(cookies) + ("Subapp-Type" to "hybrid"))
                }
                if (errorCode(response) != 0) {
                    val msg = response.optString("error_msg")
                    if (isManual(msg)) throw AdapterException(TaskStatus.MANUAL, "需要在贴吧 App 完成验证", diagnostic(response))
                    if (isExpired(msg)) throw AdapterException(TaskStatus.EXPIRED, "登录已失效，请重新登录", diagnostic(response))
                    return null
                }
                val items = response.optJSONArray("like_forum") ?: return null
                // Keep only public IDs and business flags, never a raw response or credentials.
                val states = linkedMapOf<String, String>()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val id = item.optString("forum_id")
                    if (id !in states) states[id] = item.opt("is_sign")?.toString()
                        ?.takeIf { it in setOf("0", "1", "false", "true") }.orEmpty()
                }
                guides.put(key, states)
            }
        } catch (e: AdapterException) {
            // A redirect on the optional web session does not establish the App session's validity.
            if (e is HttpStatusException && (e.isRedirect || e.isTransient)) {
                return if (retryReads) null else TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", e.diagnostic)
            }
            if (e.status == TaskStatus.UNKNOWN) return null
            throw e
        } catch (e: IOException) {
            if (!retryReads || Thread.currentThread().isInterrupted) throw e
            return null
        }
        return when (forums[target.metadata["forumId"]]) {
            "1", "true" -> TaskResult(TaskStatus.ALREADY, "今日已签到", "tieba forumGuide is_sign=1")
            "0", "false" -> TaskResult(TaskStatus.PENDING, "今日尚未签到", "tieba forumGuide is_sign=0")
            else -> null
        }
    }

    private fun login(cookies: Map<String, String>, forceFresh: Boolean = false): Session {
        val key = cacheKey(cookies)
        if (forceFresh) sessions.remove(key) else sessions.get(key)?.let { return it }
        val response = readAppRequest("/c/s/login", mapOf("_client_version" to VERSION, "bdusstoken" to cookies.getValue("BDUSS")))
        requireSuccess(response)
        val user = response.optJSONObject("user")
            ?: throw AdapterException(TaskStatus.EXPIRED, "登录已失效，请重新登录", diagnostic(response))
        val uid = user.optString("id")
        if (uid.toLongOrNull()?.let { it > 0 } != true)
            throw AdapterException(TaskStatus.EXPIRED, "登录已失效，请重新登录", diagnostic(response))
        val tbs = response.optJSONObject("anti")?.optString("tbs").orEmpty()
        if (!Regex("[a-fA-F0-9]{26}").matches(tbs))
            throw AdapterException(TaskStatus.UNKNOWN, "贴吧校验未通过，请稍后重试", diagnostic(response))
        return sessions.put(key, Session(uid, user.optString("name").takeIf { it.isNotBlank() } ?: "贴吧用户 $uid", tbs))
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

    private fun followedForums(cookies: Map<String, String>, uid: String, forceFresh: Boolean = false): List<Target> {
        val key = cacheKey(cookies)
        if (forceFresh) followed.remove(key) else followed.get(key)?.let { return it }
        val result = linkedMapOf<String, Target>()
        for (page in 1..100) {
            val response = readAppRequest("/c/f/forum/like", mapOf("BDUSS" to cookies.getValue("BDUSS"),
                "_client_version" to VERSION, "friend_uid" to uid, "page_no" to "$page", "page_size" to "200"))
            requireSuccess(response)
            val rawGroups = response.opt("forum_list")
            if (rawGroups !is JSONObject && !(rawGroups is org.json.JSONArray && rawGroups.length() == 0))
                throw AdapterException(TaskStatus.UNKNOWN, "暂时无法读取关注列表")
            val groups = response.optJSONObject("forum_list")
            var found = 0
            for (key in listOf("non-gconforum", "gconforum")) {
                if (groups != null && groups.has(key) && !groups.isNull(key) && groups.optJSONArray(key) == null)
                    throw AdapterException(TaskStatus.UNKNOWN, "暂时无法读取关注列表")
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
                "0", "false" -> return followed.put(key, result.values.toList())
                "1", "true" -> if (found == 0) throw AdapterException(TaskStatus.UNKNOWN, "暂时无法读取完整的关注列表")
                else -> throw AdapterException(TaskStatus.UNKNOWN, "暂时无法读取完整的关注列表")
            }
        }
        throw AdapterException(TaskStatus.UNKNOWN, "关注贴吧超过本次读取上限，未保存不完整列表")
    }

    private fun appRequest(path: String, fields: Map<String, String>): JSONObject =
        net.postForm("https://tiebac.baidu.com$path", signedFields(fields))

    private fun readAppRequest(path: String, fields: Map<String, String>): JSONObject =
        try { readRequest { appRequest(path, fields) } }
        catch (e: IOException) {
            // The individual request has already used its retry budget.
            if (Thread.currentThread().isInterrupted) throw e
            throw AdapterException(TaskStatus.FAILED, "网络连接失败，请稍后重试", transportDiagnostic(e))
        }

    private fun <T> readRequest(retry: Boolean = true, request: () -> T): T {
        if (retry) repeat(2) { attempt ->
            try { return request() }
            catch (e: Exception) {
                if (!isTransient(e)) throw e
                pause(READ_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return request()
    }

    private fun isTransient(e: Exception): Boolean = !Thread.currentThread().isInterrupted &&
        (e is IOException || (e is HttpStatusException && e.isTransient))

    private fun transportDiagnostic(e: Exception): String =
        if (e is HttpStatusException) e.diagnostic else "exception=${e.javaClass.simpleName}"

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

    private fun requireSuccess(response: JSONObject) {
        if (errorCode(response) == 0) return
        val msg = response.optString("error_msg")
        val status = when {
            isManual(msg) -> TaskStatus.MANUAL
            msg.contains("未登录") || msg.contains("请先登录") || msg.contains("登录失效") || msg.contains("登录过期") || msg.contains("BDUSS", true) -> TaskStatus.EXPIRED
            else -> TaskStatus.UNKNOWN
        }
        throw AdapterException(status, when (status) {
            TaskStatus.MANUAL -> "需要在贴吧 App 完成验证"
            TaskStatus.EXPIRED -> "登录已失效，请重新登录"
            else -> "结果未确认，请稍后重试"
        }, diagnostic(response))
    }

    companion object {
        const val WRITE_INTERVAL_MS = 5000L
        private const val READ_RETRY_DELAY_MS = 1500L
        private const val VERSION = "22.12.1.0"
        private fun errorCode(result: JSONObject): Int? = result.opt("error_code")?.toString()?.toIntOrNull()
        private fun diagnostic(result: JSONObject) = "tieba error_code=${errorCode(result) ?: "missing"}"
        private fun isManual(msg: String) = msg.contains("验证码") || msg.contains("安全验证") || msg.contains("频繁")
        private fun isExpired(msg: String) = msg.contains("未登录") || msg.contains("请先登录") ||
            msg.contains("登录失效") || msg.contains("登录过期") || msg.contains("BDUSS", true)

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
                code == 160002 -> TaskResult(TaskStatus.ALREADY, "今日已签到", diagnostic)
                code == 0 && (result.optJSONObject("user_info")?.opt("sign_bonus_point")?.toString()?.toIntOrNull() ?: 0) > 0 ->
                    TaskResult(TaskStatus.SUCCESS, "签到成功", diagnostic)
                isManual(msg) -> TaskResult(TaskStatus.MANUAL, "需要在贴吧 App 完成验证", diagnostic)
                msg.contains("未登录") || msg.contains("请先登录") || msg.contains("登录失效") || msg.contains("登录过期") ->
                    TaskResult(TaskStatus.EXPIRED, "登录已失效，请重新登录", diagnostic)
                msg.contains("封禁") || msg.contains("禁止签到") || msg.contains("吧已被关闭") ->
                    TaskResult(TaskStatus.FAILED, "此吧暂时无法签到", diagnostic)
                else -> TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", diagnostic)
            }
        }
    }
}

/** Internal transport seam for isolated protocol fixtures; production still uses the exact host allowlist. */
internal interface TiebaTransport {
    fun get(url: String, headers: Map<String, String>): JSONObject
    fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): JSONObject
}

private class OfficialTiebaTransport : TiebaTransport {
    private val client = NetClient(setOf("tiebac.baidu.com", "tieba.baidu.com"))
    override fun get(url: String, headers: Map<String, String>) = client.get(url, headers)
    override fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String>) = client.postForm(url, fields, headers)
}
