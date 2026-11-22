package cn.onetap.checkin.platform

import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.Identity
import cn.onetap.checkin.core.NetClient
import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.core.PlatformAdapter
import cn.onetap.checkin.core.Stat
import cn.onetap.checkin.core.Target
import cn.onetap.checkin.core.TaskResult
import cn.onetap.checkin.core.TaskStatus
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Official platform hosts only. Protocol evidence and limitations: docs/integration-skland.md. */
class SklandAdapter internal constructor(
    private val net: SklandTransport,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) : PlatformAdapter {
    constructor() : this(OfficialSklandTransport())
    override val platform = Platform.SKLAND
    override val maxTargetConcurrency = 1

    private data class Session(val cred: String, val signToken: String, val userId: String, val did: String)
    private class SessionSlot { var session: Session? = null; var at = 0L }
    // Raw input credentials are never retained as cache keys or in the slots.
    private val sessions = ConcurrentHashMap<String, SessionSlot>()

    private inner class SessionAccess(private val credential: String, private val slot: SessionSlot) {
        val session: Session get() = checkNotNull(slot.session)
        fun invalidate() { slot.session = null }
        fun refresh() {
            val owner = session.userId
            invalidate()
            val fresh = authorize(credential)
            if (fresh.userId != owner)
                throw AdapterException(TaskStatus.EXPIRED, "森空岛身份已改变，请重新登录")
            slot.session = fresh
            slot.at = clock()
        }
    }

    private fun <T> withSession(credential: String, forceFresh: Boolean = false, action: (SessionAccess) -> T): T {
        val key = MessageDigest.getInstance("SHA-256").digest(credential.trim().toByteArray(Charsets.UTF_8)).hex()
        val slot = sessions.computeIfAbsent(key) { SessionSlot() }
        // Also serialize use vs. validate/refresh, so a new authorization cannot invalidate an in-flight request.
        return synchronized(slot) {
            if (forceFresh || slot.session == null || clock() - slot.at !in 0 until SESSION_TTL_MS) {
                slot.session = null
                slot.session = authorize(credential)
                slot.at = clock()
            }
            try {
                action(SessionAccess(credential, slot)).also {
                    if (it is TaskResult && it.status == TaskStatus.EXPIRED) slot.session = null
                }
            } catch (e: AdapterException) {
                if (e.status == TaskStatus.EXPIRED) slot.session = null
                throw e
            }
        }
    }

    override fun validate(credential: String): Identity = withSession(credential, forceFresh = true) { session ->
        val targets = loadTargets(session)
        val names = targets.filter { it.bound && it.metadata["unavailable"].isNullOrEmpty() }
            .map { it.label.substringBefore(" · ") }.distinct().take(2)
        Identity(
            session.session.userId,
            names.joinToString(" / ").ifBlank { "森空岛 ${session.session.userId}" },
            targets,
            "森空岛 ID ${session.session.userId}；游戏与角色由官方绑定接口获取；接口资料已核对，尚待本人账号验收"
        )
    }

    override fun execute(credential: String, target: Target): TaskResult {
        if (!target.bound) return TaskResult(TaskStatus.UNBOUND, "${target.game}未绑定")
        return withSession(credential) { session ->
            val current = ownedTarget(session, target)
            if (current.metadata["roleMissing"] == "true")
                return@withSession TaskResult(TaskStatus.UNKNOWN, "未找到终末地角色，请重新校验账号")
            current.metadata["unavailable"]?.takeIf { it.isNotBlank() }?.let {
                return@withSession TaskResult(TaskStatus.INELIGIBLE, it)
            }
            // The server decides the cycle. Never infer 'already' from an error code alone.
            val before = queryWithSession(session, current)
            if (before.status != TaskStatus.PENDING) return@withSession before
            val path = if (current.type == ARK) ARK_PATH else ENDFIELD_PATH
            val body = if (current.type == ARK) JSONObject().put("uid", requiredMeta(current, "uid")).toString() else ""
            val response = sessionRequest(session, path, body, roleHeaders(current))
            parseAttendanceResult(current.type, response)
        }
    }

    override fun query(credential: String, target: Target): TaskResult? {
        if (!target.bound) return TaskResult(TaskStatus.UNBOUND, "${target.game}未绑定")
        return withSession(credential) { session ->
            val current = ownedTarget(session, target)
            if (current.metadata["roleMissing"] == "true")
                return@withSession TaskResult(TaskStatus.UNKNOWN, "未找到终末地角色，请重新校验账号")
            current.metadata["unavailable"]?.takeIf { it.isNotBlank() }?.let {
                return@withSession TaskResult(TaskStatus.INELIGIBLE, it)
            }
            queryWithSession(session, current)
        }
    }

    private fun authorize(input: String): Session {
        val value = input.trim()
        if (value.isBlank()) throw AdapterException(TaskStatus.EXPIRED, "请在本机输入森空岛登录凭证")
        val imported = if (value.startsWith("{")) try { JSONObject(value) } catch (_: Exception) {
            throw AdapterException(TaskStatus.FAILED, "凭证 JSON 格式不正确")
        } else null
        val did = imported?.optString("dId").orEmpty()
        if (did.length > 2048 || did.any { it.code !in 32..126 })
            throw AdapterException(TaskStatus.FAILED, "设备标识格式不正确")
        val explicitCred = imported?.optString("cred").orEmpty()
        val data: JSONObject
        val cred: String
        val signToken: String
        if (explicitCred.isNotBlank()) {
            // Imported cred is refreshed at the official endpoint; never trust an imported userId.
            cred = validHeaderSecret(explicitCred)
            val response = net.get(BASE + "/api/v1/auth/refresh", plainHeaders(did) + ("cred" to cred))
            data = checkedData(response, "code", "刷新森空岛会话")
            signToken = requiredValue(data, "token", "刷新返回缺少签名凭证")
        } else {
            val token = imported?.optJSONObject("data")?.optString("content")?.takeIf { it.isNotBlank() }
                ?: imported?.optString("token")?.takeIf { it.isNotBlank() }
                ?: if (imported == null) value else throw AdapterException(TaskStatus.FAILED, "请输入 Token、官方返回 JSON，或包含 cred 的 JSON")
            if (token.length > 4096 || token.any { it.isWhitespace() })
                throw AdapterException(TaskStatus.FAILED, "Token 格式不正确，请只复制官方 content 字段")
            val grant = net.postJson(
                "https://as.hypergryph.com/user/oauth2/v2/grant",
                JSONObject().put("appCode", "4ca99fa6b56cc2ba").put("token", token).put("type", 0),
                plainHeaders(did)
            )
            val code = requiredValue(checkedData(grant, "status", "森空岛授权"), "code", "授权返回缺少临时代码")
            val response = net.postJson(
                BASE + "/api/v1/user/auth/generate_cred_by_code",
                JSONObject().put("code", code).put("kind", 1), plainHeaders(did)
            )
            data = checkedData(response, "code", "生成森空岛会话")
            cred = validHeaderSecret(requiredValue(data, "cred", "授权返回缺少会话凭证"))
            signToken = requiredValue(data, "token", "授权返回缺少签名凭证")
        }
        var session = Session(cred, signToken, "", did)
        // Do not use the login form's claimed identity or a game UID as the platform account ID.
        val identityPath = "/api/v1/user/teenager"
        val identity = checkedData(net.get(BASE + identityPath, signedHeaders(session, identityPath)), "code", "校验森空岛身份")
        val userId = identity.optJSONObject("teenager")?.let { stringValue(it, "userId") }.orEmpty()
        if (userId.isBlank()) throw AdapterException(TaskStatus.UNKNOWN, "官方未返回可确认的森空岛身份", diagnostic("identity", identity))
        session = session.copy(userId = userId)
        return session
    }

    private fun loadTargets(session: SessionAccess): List<Target> {
        val path = "/api/v1/game/player/binding"
        val response = sessionRequest(session, path)
        val data = checkedData(response, "code", "获取森空岛绑定角色")
        val apps = data.optJSONArray("list") ?: throw AdapterException(TaskStatus.UNKNOWN, "绑定返回缺少游戏列表", diagnostic("binding", response))
        return parseTargets(session.session.userId, apps)
    }

    private fun ownedTarget(session: SessionAccess, target: Target): Target {
        if (target.metadata["ownerUserId"] != session.session.userId)
            throw AdapterException(TaskStatus.FAILED, "凭证与此角色所属森空岛账号不一致，请重新校验账号")
        return loadTargets(session).firstOrNull { it.id == target.id && it.bound }
            ?: throw AdapterException(TaskStatus.UNBOUND, "角色已不在此账号的绑定列表，请校验账号刷新角色")
    }

    private fun queryWithSession(session: SessionAccess, target: Target): TaskResult {
        val path = when (target.type) {
            ARK -> ARK_PATH + "?gameId=1&uid=" + requiredMeta(target, "uid").urlEncoded()
            ENDFIELD -> ENDFIELD_PATH
            else -> return TaskResult(TaskStatus.NOT_CONNECTED, "不支持的森空岛任务")
        }
        val response = sessionRequest(session, path, extraHeaders = roleHeaders(target))
        return parseAttendanceState(target.type, response)
    }

    private fun sessionRequest(session: SessionAccess, path: String, body: String? = null,
        extraHeaders: Map<String, String> = emptyMap()): JSONObject {
        repeat(2) { attempt ->
            try {
                val headers = signedHeaders(session.session, path, body.orEmpty()) + extraHeaders
                val response = if (body == null) net.get(BASE + path, headers) else net.postRawJson(BASE + path, body, headers)
                // 10002 explicitly rejects authentication. Message text alone never authorizes a write retry.
                if (numericCode(response, "code") != 10002) return response
                if (attempt == 1) { session.invalidate(); throw responseError(response, "code", "请求") }
            } catch (e: AdapterException) {
                if (e.status != TaskStatus.EXPIRED || e.diagnostic != "HTTP 401") throw e
                if (attempt == 1) { session.invalidate(); throw e }
            }
            // Retry only this rejected request, not the enclosing execute() or any successful write.
            session.refresh()
        }
        error("unreachable")
    }

    private fun roleHeaders(target: Target): Map<String, String> = if (target.type == ENDFIELD)
        mapOf("sk-game-role" to "3_${requiredMeta(target, "roleId")}_${requiredMeta(target, "serverId")}") else emptyMap()

    private fun plainHeaders(did: String) = buildMap {
        put("User-Agent", USER_AGENT)
        put("Accept", "application/json")
        put("Origin", "https://game.skland.com")
        put("Referer", "https://game.skland.com/")
        put("platform", "3")
        put("vName", "1.0.0")
        put("dId", did)
    }

    private fun signedHeaders(session: Session, pathAndQuery: String, body: String = ""): Map<String, String> {
        val url = (BASE + pathAndQuery).toHttpUrl()
        val timestamp = (System.currentTimeMillis() / 1000 - 1).toString()
        val payload = if (body.isEmpty()) url.encodedQuery.orEmpty() else body
        return plainHeaders(session.did) + mapOf(
            "timestamp" to timestamp, "cred" to session.cred,
            "sign" to signature(session.signToken, url.encodedPath, payload, timestamp, session.did)
        )
    }

    companion object {
        internal const val SESSION_TTL_MS = 120_000L
        const val ARK = "arknights"
        const val ENDFIELD = "endfield"
        private const val BASE = "https://zonai.skland.com"
        private const val ARK_PATH = "/api/v1/game/attendance"
        private const val ENDFIELD_PATH = "/web/v1/game/endfield/attendance"
        private const val USER_AGENT = "Skland/1.32.1 (com.hypergryph.skland; build:103201004; Android 33; ) Okhttp/4.11.0"
        private val attendanceZone = ZoneId.of("Asia/Shanghai")

        /** Header ordering and body bytes must exactly match the signed material. */
        internal fun signature(token: String, path: String, bodyOrQuery: String, timestamp: String, did: String = ""): String {
            val signedJson = "{\"platform\":\"3\",\"timestamp\":${JSONObject.quote(timestamp)},\"dId\":${JSONObject.quote(did)},\"vName\":\"1.0.0\"}"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val hmacHex = mac.doFinal((path + bodyOrQuery + timestamp + signedJson).toByteArray(Charsets.UTF_8)).hex()
            return MessageDigest.getInstance("MD5").digest(hmacHex.toByteArray(Charsets.UTF_8)).hex()
        }

        internal fun parseTargets(userId: String, apps: JSONArray): List<Target> {
            val targets = mutableListOf<Target>()
            for (kind in listOf(ARK, ENDFIELD)) {
                val game = if (kind == ARK) "明日方舟" else "明日方舟：终末地"
                val app = (0 until apps.length()).mapNotNull { apps.optJSONObject(it) }.firstOrNull { it.optString("appCode") == kind }
                val bindings = app?.optJSONArray("bindingList")
                if (app != null && bindings == null)
                    throw AdapterException(TaskStatus.UNKNOWN, "$game 绑定列表格式变化，结果待确认")
                val common = mapOf(
                    "ownerUserId" to userId, "resetZone" to "Asia/Shanghai", "resetHour" to "0",
                    "resetVerified" to "false", "resetEvidence" to "官方UTC+8每日一次；自然日0点推导；官方明确每月1日00:00重置月进度；服务端状态优先"
                )
                if (bindings == null || bindings.length() == 0) {
                    targets += Target("$kind:unbound", kind, "$game · 未绑定", game, bound = false, metadata = common)
                    continue
                }
                for (i in 0 until bindings.length()) {
                    val binding = bindings.optJSONObject(i) ?: throw AdapterException(TaskStatus.UNKNOWN, "$game 绑定对象格式变化")
                    val uid = requiredValue(binding, "uid", "$game 绑定对象缺少 UID")
                    if (kind == ARK) {
                        val server = requiredValue(binding, "channelMasterId", "明日方舟绑定缺少区服标识")
                        val name = stringValue(binding, "nickName").ifBlank { "角色 $uid" }
                        targets += Target("$kind:$uid:$server", kind, "$name · UID $uid", game,
                            stringValue(binding, "channelName").ifBlank { "区服 $server" }, metadata = common + mapOf(
                                "uid" to uid, "serverId" to server,
                                "unavailable" to if (binding.optBoolean("isDelete")) "绑定角色已删除，请在森空岛处理" else ""
                            ))
                    } else {
                        val roles = binding.optJSONArray("roles")?.takeIf { it.length() > 0 }
                            ?: binding.optJSONObject("defaultRole")?.let { JSONArray().put(it) }
                        if (roles == null || roles.length() == 0) {
                            targets += Target("$kind:$uid:missing", kind, "绑定 $uid · 缺少角色信息", game,
                                metadata = common + mapOf("uid" to uid, "roleMissing" to "true"))
                            continue
                        }
                        for (j in 0 until roles.length()) {
                            val role = roles.optJSONObject(j) ?: throw AdapterException(TaskStatus.UNKNOWN, "终末地角色对象格式变化")
                            val roleId = requiredValue(role, "roleId", "终末地角色缺少 roleId")
                            val serverId = requiredValue(role, "serverId", "终末地角色缺少 serverId")
                            val name = stringValue(role, "nickname").ifBlank { "角色 $roleId" }
                            targets += Target("$kind:$uid:$roleId:$serverId", kind, "$name · UID $roleId", game,
                                stringValue(role, "serverName").ifBlank { "区服 $serverId" }, metadata = common + mapOf(
                                    "uid" to uid, "roleId" to roleId, "serverId" to serverId,
                                    "unavailable" to when {
                                        binding.optBoolean("isDelete") -> "绑定角色已删除，请在森空岛处理"
                                        role.optBoolean("isBanned") -> "此角色已封禁，停止执行"
                                        else -> ""
                                    }
                                ))
                        }
                    }
                }
            }
            return targets.distinctBy { it.id }
        }

        internal fun parseAttendanceState(kind: String, response: JSONObject): TaskResult {
            val data = checkedData(response, "code", "查询签到状态")
            if (kind == ENDFIELD) {
                val hasToday = data.opt("hasToday") as? Boolean
                    ?: return TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", diagnostic(kind, response))
                if (!hasToday) return TaskResult(TaskStatus.PENDING, "今日尚未签到")
                val today = attendanceDate(data, "currentTs")
                return signedToday(data, today, todayRecords(data, today), endfield = true)
            }
            if (kind != ARK) return TaskResult(TaskStatus.NOT_CONNECTED, "不支持的森空岛任务")
            val calendar = data.optJSONArray("calendar")
                ?: return TaskResult(TaskStatus.UNKNOWN, "暂时无法查询签到日历", diagnostic(kind, response))
            val daily = (0 until calendar.length()).mapNotNull { calendar.optJSONObject(it) }.filter { it.optString("type") == "daily" }
            if (daily.isEmpty() || daily.any { it.opt("available") !is Boolean || it.opt("done") !is Boolean })
                return TaskResult(TaskStatus.UNKNOWN, "暂时无法查询签到日历", diagnostic(kind, response))
            if (daily.any { it.optBoolean("available") }) return TaskResult(TaskStatus.PENDING, "今日可以签到")
            // A valid but all-unavailable calendar may also be a guest/role error. Require an actual
            // server record for today before reporting ALREADY; empty data is never success.
            val today = attendanceDate(data, "currentTs")
            val records = todayRecords(data, today)
            if (records.isNotEmpty()) return signedToday(data, today, records, endfield = false)
            return TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", diagnostic(kind, response))
        }

        private fun attendanceDate(data: JSONObject, field: String): LocalDate? {
            val timestamp = stringValue(data, field).toLongOrNull() ?: return null
            return try { Instant.ofEpochSecond(timestamp).atZone(attendanceZone).toLocalDate() }
            catch (_: DateTimeException) { null }
        }

        private fun objects(array: JSONArray?): List<JSONObject> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

        private fun todayRecords(data: JSONObject, today: LocalDate?): List<JSONObject> =
            if (today == null) emptyList() else objects(data.optJSONArray("records")).filter { attendanceDate(it, "ts") == today }

        private fun signedToday(data: JSONObject, today: LocalDate?, records: List<JSONObject>, endfield: Boolean): TaskResult {
            val result = TaskResult(TaskStatus.ALREADY, "今日已签到")
            val resources = data.optJSONObject("resourceInfoMap")
            val calendar = objects(data.optJSONArray("calendar"))
            val datedClaims = if (today == null) emptyList() else calendar.filter {
                it.opt("done") == true && (attendanceDate(it, "ts") == today || it.opt("date") == today.toString())
            }
            val awards = records.flatMap { mappedAwards(it, resources) }
                .ifEmpty { datedClaims.flatMap { mappedAwards(it, resources) } }
                .ifEmpty { mappedAwards(data.optJSONArray("awardIds"), resources) }
                .ifEmpty {
                    if (endfield) latestEndfieldClaim(calendar)?.let { mappedAwards(it, resources) }.orEmpty()
                    else emptyList()
                }
            return if (awards.isEmpty()) result.withUnrecognizedStats(listOf(
                "records", "calendar", "ts", "date", "done", "awardIds", "awardId", "resourceId", "resourceInfoMap", "id", "name", "count"
            )) else withAwards(result, awards)
        }

        private fun latestEndfieldClaim(calendar: List<JSONObject>): JSONObject? {
            val daily = calendar.filter { it.opt("type") == "daily" }
            if (daily.any { it.has("ts") || it.has("date") || it.opt("done") !is Boolean }) return null
            // After hasToday=true, the last slot in an uninterrupted sequence of completed daily slots is today's claim.
            val completed = daily.takeWhile { it.opt("done") == true }
            if (daily.drop(completed.size).any { it.opt("done") == true }) return null
            return completed.lastOrNull()
        }

        private fun mappedAwards(entry: JSONObject, resources: JSONObject?): List<String> {
            val id = stringValue(entry, "resourceId").ifBlank { stringValue(entry, "awardId") }
            return mappedAwards(entry.optJSONArray("awardIds"), resources).ifEmpty {
                listOfNotNull(mappedAward(resources, id, entry.statLong("count")))
            }
        }

        private fun mappedAwards(ids: JSONArray?, resources: JSONObject?): List<String> {
            if (ids == null || resources == null) return emptyList()
            return (0 until ids.length()).mapNotNull { index ->
                val item = ids.optJSONObject(index)
                val id = if (item == null) {
                    when (val value = ids.opt(index)) {
                        is String, is Number -> value.toString()
                        else -> ""
                    }
                } else stringValue(item, "id").ifBlank { stringValue(item, "resourceId") }
                mappedAward(resources, id, item?.statLong("count"))
            }
        }

        private fun mappedAward(resources: JSONObject?, id: String, count: Long?): String? {
            val resource = resources?.optJSONObject(id.takeIf { it.isNotBlank() } ?: return null) ?: return null
            return awardText(resource, count ?: resource.statLong("count"))
        }

        private fun awardText(resource: JSONObject?, count: Number?): String? {
            val name = resource?.let { stringValue(it, "name") }.orEmpty()
            return if (name.isNotBlank() && count != null) "$name ×$count" else null
        }

        private fun withAwards(result: TaskResult, awards: List<String>): TaskResult {
            val text = awards.joinToString("、")
            val value = if (text.length <= 40) text else text.take(39).trimEnd('、').dropLastWhile { it.isHighSurrogate() } + "…"
            return result.copy(stats = listOf(Stat("今日奖励", value)))
        }

        internal fun parseAttendanceResult(kind: String, response: JSONObject): TaskResult {
            val code = numericCode(response, "code")
            val message = stringValue(response, "message")
            if (code != null && code != 0 && (message.contains("请勿重复签到") || message == "Please do not sign in again!"))
                return TaskResult(TaskStatus.ALREADY, "今日已签到", diagnostic(kind, response))
            if (code != 0) {
                val error = responseError(response, "code", "签到")
                return TaskResult(error.status, error.message, error.diagnostic)
            }
            val data = response.optJSONObject("data")
                ?: return TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", diagnostic(kind, response))
            val awards = if (kind == ARK) {
                val list = data.optJSONArray("awards") ?: return TaskResult(TaskStatus.UNKNOWN, "签到奖励未确认", diagnostic(kind, response))
                objects(list).mapNotNull { item ->
                    awardText(item.optJSONObject("resource"), item.opt("count") as? Number)
                }
            } else if (kind == ENDFIELD) {
                val ids = data.optJSONArray("awardIds")
                val resources = data.optJSONObject("resourceInfoMap")
                if (ids == null || resources == null || stringValue(data, "ts").isBlank())
                    return TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", diagnostic(kind, response))
                objects(ids).mapNotNull { award ->
                    val item = resources.optJSONObject(stringValue(award, "id")) ?: return@mapNotNull null
                    awardText(item, item.opt("count") as? Number)
                }
            } else return TaskResult(TaskStatus.NOT_CONNECTED, "不支持的森空岛任务")
            if (awards.isEmpty()) return TaskResult(TaskStatus.UNKNOWN, "签到奖励未确认", diagnostic(kind, response))
            return withAwards(TaskResult(TaskStatus.SUCCESS, "签到成功", diagnostic(kind, response)), awards)
        }

        private fun checkedData(response: JSONObject, field: String, stage: String): JSONObject {
            if (numericCode(response, field) != 0) throw responseError(response, field, stage)
            return response.optJSONObject("data") ?: throw AdapterException(TaskStatus.UNKNOWN, "$stage 返回缺少业务数据", diagnostic(stage, response))
        }

        private fun responseError(response: JSONObject, field: String, stage: String): AdapterException {
            val code = numericCode(response, field)
            val msg = stringValue(response, "message").ifBlank { stringValue(response, "msg") }
            val status = when {
                field == "code" && code == 10002 -> TaskStatus.EXPIRED
                field == "status" && code == 3 -> TaskStatus.EXPIRED // Verified with an invalid, non-account test value.
                msg.contains("验证码") || msg.contains("风险") || msg.contains("验证失败") || msg.contains("设备本地时间") || msg.contains("设备时间") -> TaskStatus.MANUAL
                msg.contains("未登录") || msg.contains("登录失效") || msg.contains("登录过期") || msg.contains("登录已过期") ||
                    msg.contains("token无效", true) || msg.contains("token已失效", true) || msg.contains("token is invalid", true) -> TaskStatus.EXPIRED
                msg.contains("未绑定") -> TaskStatus.UNBOUND
                else -> TaskStatus.UNKNOWN
            }
            val text = when (status) {
                TaskStatus.EXPIRED -> "登录已失效，请重新登录"
                TaskStatus.MANUAL -> "需要在森空岛 App 完成验证。如提示时间异常，请开启系统自动时间"
                TaskStatus.UNBOUND -> "未绑定角色"
                else -> "结果未确认，请稍后重试"
            }
            return AdapterException(status, text, diagnostic(stage, response))
        }

        private fun numericCode(json: JSONObject, field: String): Int? {
            val value = (json.opt(field) as? Number)?.toDouble() ?: return null
            return if (value.isFinite() && value == value.toInt().toDouble()) value.toInt() else null
        }
        private fun stringValue(json: JSONObject, key: String): String = when (val value = json.opt(key)) {
            is String -> value
            is Number -> value.toString()
            else -> ""
        }
        private fun requiredValue(json: JSONObject, key: String, error: String): String = stringValue(json, key).takeIf { it.isNotBlank() }
            ?: throw AdapterException(TaskStatus.UNKNOWN, error)
        private fun requiredMeta(target: Target, key: String): String = target.metadata[key]?.takeIf { it.isNotBlank() }
            ?: throw AdapterException(TaskStatus.UNKNOWN, "角色参数缺少 $key，请重新校验账号")
        private fun validHeaderSecret(value: String): String {
            if (value.length > 4096 || value.isBlank() || value.any { it.code !in 33..126 })
                throw AdapterException(TaskStatus.FAILED, "会话凭证格式不正确")
            return value
        }
        /** Whitelisted metadata only: never record messages, request bodies, headers, or raw JSON. */
        private fun diagnostic(stage: String, response: JSONObject): String {
            val data = response.optJSONObject("data")
            val fields = listOf("awards", "awardIds", "resourceInfoMap", "calendar", "records", "hasToday", "currentTs", "ts")
                .filter { data?.has(it) == true }.joinToString(",")
            return "skland;stage=$stage;code=${numericCode(response, "code")};status=${numericCode(response, "status")};knownFields=$fields"
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    }
}

internal interface SklandTransport {
    fun get(url: String, headers: Map<String, String>): JSONObject
    fun postJson(url: String, body: JSONObject, headers: Map<String, String>): JSONObject
    fun postRawJson(url: String, body: String, headers: Map<String, String>): JSONObject
}

private class OfficialSklandTransport : SklandTransport {
    private val net = NetClient(setOf("as.hypergryph.com", "zonai.skland.com"))
    override fun get(url: String, headers: Map<String, String>) = net.get(url, headers)
    override fun postJson(url: String, body: JSONObject, headers: Map<String, String>) = net.postJson(url, body, headers)
    override fun postRawJson(url: String, body: String, headers: Map<String, String>) = net.postRawJson(url, body, headers)
}
