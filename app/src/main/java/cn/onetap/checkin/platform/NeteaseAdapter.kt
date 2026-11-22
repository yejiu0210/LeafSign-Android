package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import okhttp3.FormBody
import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Direct platform requests. Protocol references and their licenses are in docs/integration-music-tieba.md. */
class NeteaseAdapter internal constructor(
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val exchange: ((String, JSONObject, Map<String, String>, Boolean) -> JSONObject)? = null
) : PlatformAdapter {
    override val platform = Platform.NETEASE
    override val maxTargetConcurrency = 1
    private val net = NetClient(setOf("music.163.com", "interfacepc.music.163.com"))
    private val deviceId = UUID.randomUUID().toString().replace("-", "")
    private val accountStats = ConcurrentHashMap<String, ConcurrentHashMap<String, StatsRead>>()

    private data class StatsRead(val stats: List<Stat> = emptyList(), val unrecognized: List<String> = emptyList())

    override fun validate(credential: String): Identity {
        val identity = validateAccount(credentials(credential))
        // Runner validates each account before starting its targets. Internal ownership checks keep that cache.
        accountStats[identity.userId] = ConcurrentHashMap()
        return identity
    }

    private fun validateAccount(cookie: Map<String, String>): Identity {
        val result = request("/nuser/account/get", JSONObject(), cookie)
        val code = code(result)
        if (code != 200) throw rejection(result)
        val account = result.optJSONObject("account")
        val profile = result.optJSONObject("profile")
        val uid = account?.optString("id")?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
            ?: throw AdapterException(TaskStatus.EXPIRED, "登录已失效，请重新登录")
        val profileId = profile?.optString("userId")
        if (!profileId.isNullOrEmpty() && profileId != uid)
            throw AdapterException(TaskStatus.UNKNOWN, "网易云账号信息不一致，请重新登录")
        return Identity(uid, profile?.optString("nickname")?.takeIf { it.isNotBlank() } ?: "网易云用户 $uid",
            listOf(Target(TARGET_ID, TARGET_TYPE, "黑胶乐签", metadata = mapOf(
                "ownerUserId" to uid, "resetZone" to "Asia/Shanghai", "resetHour" to "0",
                "resetVerified" to "false", "resetEvidence" to "每日业务；精确边界待真实账号跨日确认"
            )), Target(YUNBEI_ID, YUNBEI_TYPE, "云贝签到", metadata = mapOf(
                "ownerUserId" to uid, "resetZone" to "Asia/Shanghai", "resetHour" to "0", "defaultEnabled" to "true"
            ))), "")
    }

    override fun execute(credential: String, target: Target): TaskResult {
        if (isYunbeiTarget(target)) return executeYunbei(credential, target)
        if (!isVipTarget(target))
            return TaskResult(TaskStatus.UNKNOWN, "不支持此签到任务")
        val cookie = credentials(credential)
        val identity = validateAccount(cookie)
        if (target.metadata["ownerUserId"] != identity.userId)
            return TaskResult(TaskStatus.EXPIRED, "账号已变化，请在账号页刷新")
        val before = readVipState(cookie)
        if (before.status != TaskStatus.PENDING) return withVipStats(before, cookie, identity.userId)

        // Both operations are necessary in the documented black-vinyl music-sign flow.
        // The history/detail operation is intentionally NOT used by query(): it participates in signing.
        val taskSign = request("/vip-center-bff/task/sign", JSONObject(), cookie)
        val taskCode = code(taskSign)
        val taskError = parseSignResult(taskSign)
        if (taskCode != 200 || taskError.status in setOf(TaskStatus.EXPIRED, TaskStatus.MANUAL)) return taskError
        val detail = request("/vipnewcenter/app/level/user/checkin/history/detail",
            JSONObject().put("signDayTime", System.currentTimeMillis()).put("type", 1), cookie, true)
        val detailError = parseSignResult(detail)
        if (detailError.status in setOf(TaskStatus.EXPIRED, TaskStatus.MANUAL)) return detailError

        // A successful request chain is insufficient. Only today's explicit server flag completes it.
        val after = checkAfterSign { readVipState(cookie) }
        val result = when (after?.status) {
            TaskStatus.ALREADY -> if(code(detail)==200) TaskResult(TaskStatus.SUCCESS, "签到成功",
                "netease vipMusicSign code=$taskCode; detail code=200")
                else TaskResult(TaskStatus.ALREADY, "今日已签到",
                    "netease vipMusicSign code=$taskCode; detail code=${code(detail) ?: "missing"}")
            TaskStatus.PENDING -> TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试",
                "netease vipMusicSign taskCode=$taskCode detailCode=${code(detail) ?: "missing"}; today=true sign=false")
            null -> TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试")
            else -> after
        }
        return withVipStats(result, cookie, identity.userId)
    }

    override fun query(credential: String, target: Target): TaskResult? {
        if (isYunbeiTarget(target)) {
            if (!target.bound) return TaskResult(TaskStatus.UNBOUND,"云贝签到不可用，请在账号页刷新")
            val cookie = credentials(credential)
            val identity = validateAccount(cookie)
            if (identity.userId != target.metadata["ownerUserId"])
                return TaskResult(TaskStatus.EXPIRED,"账号已变化，请在账号页刷新")
            return readYunbeiState(cookie)?.let { withYunbeiStats(it, cookie, identity.userId) }
        }
        if (!isVipTarget(target)) return TaskResult(TaskStatus.UNKNOWN, "不支持此签到任务")
        val cookie = credentials(credential)
        val identity = validateAccount(cookie)
        if (target.metadata["ownerUserId"] != identity.userId)
            return TaskResult(TaskStatus.EXPIRED, "账号已变化，请在账号页刷新")
        return withVipStats(readVipState(cookie), cookie, identity.userId)
    }

    private fun readVipState(cookie: Map<String, String>): TaskResult = parseVipState(request(
        "/vipnewcenter/app/minidesk/music/sign/pc", JSONObject().put("type", 1), cookie, true))

    private fun executeYunbei(credential: String, target: Target): TaskResult {
        if (!target.bound) return TaskResult(TaskStatus.UNBOUND,"云贝签到不可用，请在账号页刷新")
        val cookie = credentials(credential)
        val identity = validateAccount(cookie)
        if (identity.userId != target.metadata["ownerUserId"])
            return TaskResult(TaskStatus.EXPIRED,"账号已变化，请在账号页刷新")
        val before = readYunbeiState(cookie)
        if (before != null && before.status != TaskStatus.PENDING)
            return withYunbeiStats(before, cookie, identity.userId)
        val result = parseYunbeiSign(yunbeiRequest("/pointmall/user/sign", cookie))
        if (result.status != TaskStatus.SUCCESS) return withYunbeiStats(result, cookie, identity.userId)
        val after = checkAfterSign { readYunbeiState(cookie) }
        val confirmed = when (after?.status) {
            TaskStatus.PENDING -> TaskResult(TaskStatus.UNKNOWN,"结果未确认，请稍后重试")
            null, TaskStatus.ALREADY -> result
            else -> after
        }
        return withYunbeiStats(confirmed, cookie, identity.userId)
    }

    private fun withVipStats(result: TaskResult, cookie: Map<String, String>, uid: String): TaskResult =
        withStats(result, cookie, uid, "/vipnewcenter/app/level/growhpoint/basic", ::parseVipStats)

    private fun withYunbeiStats(result: TaskResult, cookie: Map<String, String>, uid: String): TaskResult =
        withStats(result, cookie, uid, "/v1/user/info", ::parseYunbeiStats)

    private fun withStats(result: TaskResult, cookie: Map<String, String>, uid: String, path: String,
        parse: (JSONObject) -> StatsRead): TaskResult {
        if (result.status != TaskStatus.SUCCESS && result.status != TaskStatus.ALREADY) return result
        val cache = accountStats.computeIfAbsent(uid) { ConcurrentHashMap() }
        val read = cache.computeIfAbsent(path) {
            try {
                val response = request(path, JSONObject(), cookie)
                if (code(response) == 200) parse(response) else StatsRead()
            } catch (e: IOException) {
                if (Thread.currentThread().isInterrupted) throw e
                StatsRead()
            } catch (_: AdapterException) {
                StatsRead()
            } catch (_: JSONException) {
                StatsRead()
            }
        }
        val withStats = result.copy(stats = read.stats)
        return if (read.unrecognized.isEmpty()) withStats else withStats.withUnrecognizedStats(read.unrecognized)
    }

    private fun parseVipStats(response: JSONObject): StatsRead {
        val data = response.optJSONObject("data")
        val card = data?.optJSONObject("levelCard")
        val sources = listOfNotNull(data?.optJSONObject("userLevel"), card?.optJSONObject("userLevel"), card, data)
        fun firstNumber(fields: List<String>): Long? =
            sources.firstNotNullOfOrNull { item -> fields.firstNotNullOfOrNull(item::statLong) }

        val levelFields = listOf("level", "vipLevel", "currentLevel")
        val growthFields = listOf("growthPoint", "currentGrowthPoint", "growPoint")
        val level = firstNumber(levelFields)
        val growth = firstNumber(growthFields)
        val next = growth?.let { findVipThreshold(sources, level, it) }
        val stats = listOfNotNull(
            level?.let { Stat("黑胶等级", "V$it") },
            growth?.let { progressStat("成长值", it, next) ?: Stat("成长值", it.toString()) }
        )
        val unrecognized = when {
            stats.isEmpty() -> levelFields + growthFields + legacyVipThresholdFields
            level != null && growth != null && next == null -> vipDiagnosticFields(data)
            else -> emptyList()
        }
        return StatsRead(stats, unrecognized)
    }

    private fun findVipThreshold(sources: List<JSONObject>, level: Long?, growth: Long): Long? {
        fun threshold(points: Long?) = points?.takeIf { it > 0 && it >= growth }

        sources.firstNotNullOfOrNull { source ->
            vipThresholdFields.firstNotNullOfOrNull { threshold(source.statLong(it)) }
        }?.let { return it }

        sources.firstNotNullOfOrNull { source ->
            vipRemainingFields.firstNotNullOfOrNull { field ->
                val remaining = source.statLong(field)?.takeIf { it <= Long.MAX_VALUE - growth }
                threshold(remaining?.let { growth + it })
            }
        }?.let { return it }

        val nextLevel = level?.takeIf { it < Long.MAX_VALUE }?.plus(1) ?: return null
        return sources.firstNotNullOfOrNull { source ->
            vipLevelListFields.firstNotNullOfOrNull { field ->
                source.optJSONArray(field)?.let { levels ->
                    (0 until levels.length()).firstNotNullOfOrNull { index ->
                        val entry = levels.optJSONObject(index)
                        val entryLevel = entry?.statLong("level") ?: entry?.statLong("vipLevel")
                        threshold(entry?.takeIf { entryLevel == nextLevel }?.statLong("growthPoint"))
                    }
                }
            }
        }
    }

    private fun vipDiagnosticFields(data: JSONObject?): List<String> {
        val fields = linkedSetOf<String>()
        fun collect(value: Any?, prefix: String) {
            when (value) {
                is JSONObject -> for (key in value.keys().asSequence().sorted()) {
                    if (fields.size == 20) break
                    val path = if (prefix.isEmpty()) key else "$prefix.$key"
                    if (!vipDiagnosticField.matches(key) || path.length > 40) continue
                    fields.add(path)
                    collect(value.opt(key), path)
                }
                is JSONArray -> for (index in 0 until value.length()) {
                    if (fields.size == 20) break
                    collect(value.opt(index), prefix)
                }
            }
        }
        collect(data, "")
        return fields.toList()
    }

    private fun parseYunbeiStats(response: JSONObject): StatsRead {
        val data = response.optJSONObject("data")
        val balance = response.optJSONObject("userPoint")?.statLong("balance")
            ?: data?.optJSONObject("userPoint")?.statLong("balance")
            ?: response.statLong("pointsBalance") ?: data?.statLong("pointsBalance")
        return if (balance == null) StatsRead(unrecognized = listOf("balance", "pointsBalance"))
        else StatsRead(listOf(Stat("云贝", balance.toString())))
    }

    private fun checkAfterSign(read: () -> TaskResult?): TaskResult? {
        var latest: TaskResult? = null
        for (wait in listOf(0L, 1_000L, 1_500L, 2_500L)) {
            if (wait > 0) pause(wait)
            // Read failures after a write belong to this poll, not the Runner's write retry.
            val state = try { read() }
            catch (e: AdapterException) { TaskResult(e.status, e.message, e.diagnostic) }
            catch (e: IOException) {
                if (Thread.currentThread().isInterrupted) throw e
                TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试")
            }
            if (state != null) {
                if (state.status != TaskStatus.PENDING && state.status != TaskStatus.UNKNOWN) return state
                latest = state
            }
        }
        return latest
    }

    private fun readYunbeiState(cookie: Map<String,String>): TaskResult? = parseYunbeiState(yunbeiRequest("/point/signed/get",cookie))

    private class ChannelRejected : Exception()
    private fun isChannelRejection(e: Exception): Boolean = e is ChannelRejected || e is JSONException ||
        (e is AdapterException && Regex("HTTP [1-5][0-9]{2}(; non-json)?").matches(e.diagnostic) &&
            (e.diagnostic != "HTTP 200"))

    private fun yunbeiRequest(path: String, cookie: Map<String,String>): JSONObject {
        try { return request(path,JSONObject(),cookie,eapi=true,rawChannel=true) }
        catch (e: Exception) { if (!isChannelRejection(e)) throw e }
        // Only a rejected/unparseable channel response permits this one fallback. Business JSON never does.
        try { return request(path,JSONObject(),cookie,eapi=false,rawChannel=true) }
        catch (e: Exception) {
            if (!isChannelRejection(e)) throw e
            throw AdapterException(TaskStatus.UNKNOWN,"结果未确认，请稍后重试","code=missing")
        }
    }

    private fun postEncrypted(url: String, fields: Map<String,String>, headers: Map<String,String>, rawChannel: Boolean): JSONObject {
        if (!rawChannel) return net.postForm(url,fields,headers)
        val body = FormBody.Builder().apply { fields.forEach { (key,value) -> add(key,value) } }.build()
        val raw = net.raw("POST",url,body,headers)
        // Parse before considering HTTP status: even an HTTP error can carry a definitive business result.
        return parseYunbeiChannelResponse(raw)
    }

    private fun request(path: String, data: JSONObject, cookie: Map<String, String>, eapi: Boolean = false, rawChannel: Boolean = false): JSONObject {
        exchange?.let { return it(path, data, cookie, eapi) }
        if (eapi) {
            val header = JSONObject().put("os", "pc").put("appver", "3.1.17.204416")
                .put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit")
                .put("channel", "netease").put("deviceId", deviceId).put("versioncode", "140")
                .put("mobilename", "").put("buildver", (System.currentTimeMillis() / 1000).toString())
                .put("resolution", "1920x1080").put("__csrf", cookie["__csrf"].orEmpty())
                .put("requestId", "${System.currentTimeMillis()}_${SecureRandom().nextInt(1000).toString().padStart(4,'0')}")
                .put("MUSIC_U", cookie.getValue("MUSIC_U"))
            data.put("e_r", false).put("header", header)
            return postEncrypted("https://interfacepc.music.163.com/eapi$path",
                encryptEapi("/api$path", data.toString()), mapOf(
                    "Cookie" to eapiCookie(header),
                    "Referer" to "https://music.163.com/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"
                ), rawChannel)
        }
        data.put("csrf_token", cookie["__csrf"].orEmpty())
        val body = encryptWeapi(data.toString())
        return postEncrypted("https://music.163.com/weapi$path", body, mapOf(
            "Cookie" to cookie.entries.joinToString("; ") { "${it.key}=${it.value}" },
            "Referer" to "https://music.163.com/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
        ), rawChannel)
    }

    internal fun credentials(input: String): Map<String, String> {
        val raw = input.trim().removePrefix("Cookie:").trim()
        if (raw.isEmpty() || raw.any { it == '\r' || it == '\n' } || raw.length > 32768)
            throw AdapterException(TaskStatus.EXPIRED, "请输入 MUSIC_U 值或含 MUSIC_U 的 Cookie")
        val cookies = if (raw.contains("MUSIC_U=")) raw.split(';').mapNotNull { part ->
            val p = part.trim().split('=', limit = 2)
            if (p.size == 2 && p[0] in setOf("MUSIC_U", "__csrf")) p[0] to p[1] else null
        }.toMap() else if (!raw.contains(';') && !raw.contains(' ')) mapOf("MUSIC_U" to raw) else emptyMap()
        if (cookies["MUSIC_U"].isNullOrBlank() || cookies.values.any { it.any { c -> c.code < 33 || c.code > 126 || c == ';' } })
            throw AdapterException(TaskStatus.EXPIRED, "Cookie 里没有 MUSIC_U，请重新复制")
        return cookies
    }

    private fun rejection(result: JSONObject): AdapterException {
        val code = code(result)
        val message = result.optString("msg", result.optString("message"))
        val status = when {
            code == 301 -> TaskStatus.EXPIRED
            message.contains("验证码") || message.contains("安全验证") || message.contains("操作频繁") -> TaskStatus.MANUAL
            else -> TaskStatus.UNKNOWN
        }
        return AdapterException(status, when (status) {
            TaskStatus.EXPIRED -> "登录已失效，请重新登录"
            TaskStatus.MANUAL -> "需要在网易云 App 完成验证"
            else -> "结果未确认，请稍后重试"
        }, "netease code=${code ?: "missing"}")
    }

    companion object {
        const val TARGET_ID = "netease:vip:music-sign"
        const val TARGET_TYPE = "vip-music-sign"
        const val YUNBEI_ID = "netease:yunbei"
        const val YUNBEI_TYPE = "yunbei"
        private val legacyVipThresholdFields =
            listOf("nextLevelGrowthPoint", "nextGrowthPoint", "nextLevelPoint", "nextLevelNeedGrowthPoint")
        private val vipThresholdFields = legacyVipThresholdFields + listOf("nextLevelRequiredGrowthPoint",
            "requiredGrowthPointForNextLevel", "nextLevelGrowPoint", "nextGrowPoint", "nextLevelGrowthThreshold")
        private val vipRemainingFields = listOf("remainGrowthPoint", "remainingGrowthPoint", "growthPointRemaining",
            "growthPointToNextLevel", "nextLevelRemainGrowthPoint", "nextLevelRemainingGrowthPoint",
            "nextLevelGrowthPointDiff", "growthPointDiff")
        private val vipLevelListFields = listOf("levelList", "vipLevelList", "userLevelList", "growthLevelList")
        private val vipDiagnosticField = Regex("[A-Za-z0-9_.]+")
        private fun isYunbeiTarget(target: Target) = target.id == YUNBEI_ID && target.type == YUNBEI_TYPE
        private fun isVipTarget(target: Target) = target.id == TARGET_ID && target.type == TARGET_TYPE

        internal fun parseYunbeiChannelResponse(response: RawResponse): JSONObject =
            try { JSONObject(response.body) } catch (_: JSONException) { throw ChannelRejected() }

        internal fun parseYunbeiState(result: JSONObject): TaskResult? {
            val code = code(result)
            if (code == 301) throw AdapterException(TaskStatus.EXPIRED,"登录已失效，请重新登录","code=301")
            if (code != 200) return if (yunbeiManual(result)) TaskResult(TaskStatus.MANUAL,"需要在网易云 App 完成验证","code=${code ?: "missing"}") else null
            val values = listOfNotNull(result.optJSONObject("data"),result).flatMap { data ->
                listOf("signed","sign","isSign","todaySigned").mapNotNull { data.opt(it) as? Boolean }
            }.distinct()
            if (values.size != 1) return null
            return if (values.single()) TaskResult(TaskStatus.ALREADY,"今日已签到","code=200")
                else TaskResult(TaskStatus.PENDING,"今日尚未签到","code=200")
        }

        internal fun parseYunbeiSign(result: JSONObject): TaskResult {
            val code = code(result)
            val diagnostic = "code=${code ?: "missing"}"
            val message = result.optString("message",result.optString("msg"))
            if (code == 301) return TaskResult(TaskStatus.EXPIRED,"登录已失效，请重新登录",diagnostic)
            if (code == 200) {
                val data = result.optJSONObject("data")
                val amount = listOf("point","yunbei","score","sign").firstNotNullOfOrNull { numericValue(data?.opt(it)) }
                return TaskResult(TaskStatus.SUCCESS,"签到成功" + (amount?.let { "，获得 $it 云贝" } ?: ""),diagnostic)
            }
            if (code == -2 || message.contains("已签到") || message.contains("重复"))
                return TaskResult(TaskStatus.ALREADY,"今日已签到",diagnostic)
            if (yunbeiManual(result)) return TaskResult(TaskStatus.MANUAL,"需要在网易云 App 完成验证",diagnostic)
            return TaskResult(TaskStatus.UNKNOWN,"结果未确认，请稍后重试",diagnostic)
        }

        private fun yunbeiManual(result: JSONObject): Boolean {
            val message = result.optString("message",result.optString("msg"))
            return listOf("验证","风控","频繁").any { message.contains(it) }
        }
        private fun numericValue(value: Any?): String? = (value as? Number)?.takeIf { it.toDouble().isFinite() }?.toString()

        /** Request results never prove the VIP music-sign's completion. */
        fun parseSignResult(result: JSONObject): TaskResult = when (code(result)) {
            301 -> TaskResult(TaskStatus.EXPIRED, "登录已失效，请重新登录", "netease vipMusicSign code=301")
            else -> {
                val msg = result.optString("msg", result.optString("message"))
                if (msg.contains("验证码") || msg.contains("安全验证") || msg.contains("操作频繁"))
                    TaskResult(TaskStatus.MANUAL, "需要在网易云 App 完成验证", "netease vipMusicSign code=${code(result) ?: "missing"}")
                else TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", "netease vipMusicSign code=${code(result) ?: "missing"}")
            }
        }

        fun parseVipState(result: JSONObject): TaskResult {
            val error = parseSignResult(result)
            if (code(result) != 200 || error.status in setOf(TaskStatus.EXPIRED, TaskStatus.MANUAL)) return error
            val list = result.optJSONObject("data")?.optJSONArray("signInfoList")
                ?: return TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", "netease vipMusicSign missing signInfoList")
            val today = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .filter { it.opt("today") == true }
            if (today.size != 1 || today[0].opt("sign") !is Boolean)
                return TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", "netease vipMusicSign ambiguous today/sign")
            return if (today[0].getBoolean("sign"))
                TaskResult(TaskStatus.ALREADY, "今日已签到", "netease vipMusicSign today=true sign=true")
            else TaskResult(TaskStatus.PENDING, "今日尚未签到", "netease vipMusicSign today=true sign=false")
        }

        private fun code(result: JSONObject): Int? = (result.opt("code") as? Number)?.toString()?.toIntOrNull()

        /** Same encodeURIComponent header-cookie envelope as the referenced request.js. */
        internal fun eapiCookie(header: JSONObject): String {
            fun encode(value: String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
                .replace("%21","!").replace("%27","'").replace("%28","(").replace("%29",")").replace("%7E","~")
            return header.keys().asSequence().joinToString("; ") { "${encode(it)}=${encode(header.getString(it))}" }
        }

        /** EAPI envelope and fixed protocol key from api-enhanced/util/crypto.js (MIT). */
        internal fun encryptEapi(path: String, text: String): Map<String, String> {
            require(path.startsWith("/api/"))
            val digest = MessageDigest.getInstance("MD5").digest("nobody${path}use${text}md5forencrypt".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val plain = "$path-36cd479b6b5-$text-36cd479b6b5-$digest"
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(Charsets.UTF_8), "AES"))
            return mapOf("params" to cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02X".format(it.toInt() and 255) })
        }

        /** weapi from api-enhanced/util/crypto.js, MIT. Secure random replaces Math.random. */
        internal fun encryptWeapi(text: String): Map<String, String> {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val random = SecureRandom()
            val secret = (1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            fun aes(value: String, key: String): String {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
                    IvParameterSpec("0102030405060708".toByteArray(Charsets.UTF_8)))
                return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
            }
            val key = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key)))
            val rsa = Cipher.getInstance("RSA/ECB/NoPadding")
            rsa.init(Cipher.ENCRYPT_MODE, publicKey)
            val encSecKey = rsa.doFinal(secret.reversed().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
            return mapOf("params" to aes(aes(text, "0CoJUm6Qyw8W8jud"), secret), "encSecKey" to encSecKey)
        }
    }
}
