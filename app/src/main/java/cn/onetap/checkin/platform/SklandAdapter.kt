package cn.onetap.checkin.platform

import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.Identity
import cn.onetap.checkin.core.NetClient
import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.core.PlatformAdapter
import cn.onetap.checkin.core.Target
import cn.onetap.checkin.core.TaskResult
import cn.onetap.checkin.core.TaskStatus
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Official platform hosts only. Protocol evidence and limitations: docs/integration-skland.md. */
class SklandAdapter : PlatformAdapter {
    override val platform = Platform.SKLAND
    private val net = NetClient(setOf("as.hypergryph.com", "zonai.skland.com"))

    private data class Session(val cred: String, val signToken: String, val userId: String, val did: String)

    override fun validate(credential: String): Identity {
        val session = authorize(credential)
        val targets = loadTargets(session)
        val names = targets.filter { it.bound && it.metadata["unavailable"].isNullOrEmpty() }
            .map { it.label.substringBefore(" · ") }.distinct().take(2)
        return Identity(
            session.userId,
            names.joinToString(" / ").ifBlank { "森空岛 ${session.userId}" },
            targets,
            "森空岛 ID ${session.userId}；游戏与角色由官方绑定接口获取；接口资料已核对，尚待本人账号验收"
        )
    }

    override fun execute(credential: String, target: Target): TaskResult {
        if (!target.bound) return TaskResult(TaskStatus.UNBOUND, "${target.game}未绑定，不执行签到")
        val session = authorize(credential)
        val current = ownedTarget(session, target)
        if (current.metadata["roleMissing"] == "true")
            return TaskResult(TaskStatus.UNKNOWN, "已绑定终末地账号但未返回角色；请在官方入口确认角色后重新校验")
        current.metadata["unavailable"]?.takeIf { it.isNotBlank() }?.let {
            return TaskResult(TaskStatus.INELIGIBLE, it)
        }
        // The server decides the cycle. Never infer 'already' from an error code alone.
        val before = queryWithSession(session, current)
        if (before.status != TaskStatus.PENDING) return before
        val path = if (current.type == ARK) ARK_PATH else ENDFIELD_PATH
        val body = if (current.type == ARK) JSONObject().put("uid", requiredMeta(current, "uid")).toString() else ""
        val response = net.postRawJson(BASE + path, body, signedHeaders(session, path, body) + roleHeaders(current))
        return parseAttendanceResult(current.type, response)
    }

    override fun query(credential: String, target: Target): TaskResult? {
        if (!target.bound) return TaskResult(TaskStatus.UNBOUND, "${target.game}未绑定")
        val session = authorize(credential)
        val current = ownedTarget(session, target)
        if (current.metadata["roleMissing"] == "true")
            return TaskResult(TaskStatus.UNKNOWN, "已绑定终末地账号但未返回角色；请在官方入口确认角色后重新校验")
        current.metadata["unavailable"]?.takeIf { it.isNotBlank() }?.let {
            return TaskResult(TaskStatus.INELIGIBLE, it)
        }
        return queryWithSession(session, current)
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

    private fun loadTargets(session: Session): List<Target> {
        val path = "/api/v1/game/player/binding"
        val response = net.get(BASE + path, signedHeaders(session, path))
        val data = checkedData(response, "code", "获取森空岛绑定角色")
        val apps = data.optJSONArray("list") ?: throw AdapterException(TaskStatus.UNKNOWN, "绑定返回缺少游戏列表", diagnostic("binding", response))
        return parseTargets(session.userId, apps)
    }

    private fun ownedTarget(session: Session, target: Target): Target {
        if (target.metadata["ownerUserId"] != session.userId)
            throw AdapterException(TaskStatus.FAILED, "凭证与此角色所属森空岛账号不一致，请重新校验账号")
        return loadTargets(session).firstOrNull { it.id == target.id && it.bound }
            ?: throw AdapterException(TaskStatus.UNBOUND, "角色已不在此账号的绑定列表，请校验账号刷新角色")
    }

    private fun queryWithSession(session: Session, target: Target): TaskResult {
        val path = when (target.type) {
            ARK -> ARK_PATH + "?gameId=1&uid=" + requiredMeta(target, "uid").urlEncoded()
            ENDFIELD -> ENDFIELD_PATH
            else -> return TaskResult(TaskStatus.NOT_CONNECTED, "不支持的森空岛任务")
        }
        val response = net.get(BASE + path, signedHeaders(session, path) + roleHeaders(target))
        return parseAttendanceState(target.type, response)
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
        const val ARK = "arknights"
        const val ENDFIELD = "endfield"
        private const val BASE = "https://zonai.skland.com"
        private const val ARK_PATH = "/api/v1/game/attendance"
        private const val ENDFIELD_PATH = "/web/v1/game/endfield/attendance"
        private const val USER_AGENT = "Skland/1.32.1 (com.hypergryph.skland; build:103201004; Android 33; ) Okhttp/4.11.0"

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
                    ?: return TaskResult(TaskStatus.UNKNOWN, "终末地状态返回缺少 hasToday，结果待确认", diagnostic(kind, response))
                return TaskResult(if (hasToday) TaskStatus.ALREADY else TaskStatus.PENDING,
                    if (hasToday) "服务器确认此角色今日已签到" else "服务器确认此角色今日尚未签到")
            }
            if (kind != ARK) return TaskResult(TaskStatus.NOT_CONNECTED, "不支持的森空岛任务")
            val calendar = data.optJSONArray("calendar")
                ?: return TaskResult(TaskStatus.UNKNOWN, "明日方舟状态返回缺少签到日历", diagnostic(kind, response))
            val daily = (0 until calendar.length()).mapNotNull { calendar.optJSONObject(it) }.filter { it.optString("type") == "daily" }
            if (daily.isEmpty() || daily.any { it.opt("available") !is Boolean || it.opt("done") !is Boolean })
                return TaskResult(TaskStatus.UNKNOWN, "明日方舟签到日历字段变化，结果待确认", diagnostic(kind, response))
            if (daily.any { it.optBoolean("available") }) return TaskResult(TaskStatus.PENDING, "服务器签到日历显示今日可签到")
            // A valid but all-unavailable calendar may also be a guest/role error. Require an actual
            // server record for today before reporting ALREADY; empty data is never success.
            val currentTs = stringValue(data, "currentTs").toLongOrNull()
            val records = data.optJSONArray("records")
            if (currentTs != null && records != null) {
                val today = java.time.Instant.ofEpochSecond(currentTs).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()
                val signed = (0 until records.length()).mapNotNull { records.optJSONObject(it) }
                    .mapNotNull { stringValue(it, "ts").toLongOrNull() }.any {
                        java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate() == today
                    }
                if (signed) return TaskResult(TaskStatus.ALREADY, "服务器记录确认此角色今日已签到")
            }
            return TaskResult(TaskStatus.UNKNOWN, "签到日历无可领取项，但未确认今日记录；请在官方入口核对", diagnostic(kind, response))
        }

        internal fun parseAttendanceResult(kind: String, response: JSONObject): TaskResult {
            val code = numericCode(response, "code")
            val message = stringValue(response, "message")
            if (code != null && code != 0 && (message.contains("请勿重复签到") || message == "Please do not sign in again!"))
                return TaskResult(TaskStatus.ALREADY, "服务器明确提示此角色今日已签到", diagnostic(kind, response))
            if (code != 0) {
                val error = responseError(response, "code", "签到")
                return TaskResult(error.status, error.message, error.diagnostic)
            }
            val data = response.optJSONObject("data")
                ?: return TaskResult(TaskStatus.UNKNOWN, "签到响应缺少业务数据，结果待确认", diagnostic(kind, response))
            val awards = mutableListOf<String>()
            if (kind == ARK) {
                val list = data.optJSONArray("awards") ?: return TaskResult(TaskStatus.UNKNOWN, "明日方舟返回缺少奖励列表，结果待确认", diagnostic(kind, response))
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val name = item.optJSONObject("resource")?.let { stringValue(it, "name") }.orEmpty()
                    val count = item.opt("count") as? Number
                    if (name.isNotBlank() && count != null) awards += "$name ×$count"
                }
            } else if (kind == ENDFIELD) {
                val ids = data.optJSONArray("awardIds")
                val resources = data.optJSONObject("resourceInfoMap")
                if (ids == null || resources == null || stringValue(data, "ts").isBlank())
                    return TaskResult(TaskStatus.UNKNOWN, "终末地返回缺少签到奖励或时间字段，结果待确认", diagnostic(kind, response))
                for (i in 0 until ids.length()) {
                    val id = ids.optJSONObject(i)?.let { stringValue(it, "id") }.orEmpty()
                    val item = resources.optJSONObject(id) ?: continue
                    val name = stringValue(item, "name")
                    val count = item.opt("count") as? Number
                    if (name.isNotBlank() && count != null) awards += "$name ×$count"
                }
            } else return TaskResult(TaskStatus.NOT_CONNECTED, "不支持的森空岛任务")
            if (awards.isEmpty()) return TaskResult(TaskStatus.UNKNOWN, "服务器返回成功码，但奖励明细无法确认，请在官方入口核对", diagnostic(kind, response))
            return TaskResult(TaskStatus.SUCCESS, "签到成功：${awards.joinToString("、")}；游戏内邮件到账可能延迟", diagnostic(kind, response))
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
                TaskStatus.EXPIRED -> "$stage：登录或签名凭证失效，请更新凭证后校验"
                TaskStatus.MANUAL -> "$stage：需要在官方入口手动验证；若提示时间异常，请启用系统自动时间"
                TaskStatus.UNBOUND -> "$stage：官方提示未绑定角色"
                else -> "$stage 结果待确认（业务码 ${code ?: "缺失"}），请在官方入口核对"
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
