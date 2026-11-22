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
import cn.onetap.checkin.core.Rules
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Member-center level acceleration and explicitly enabled VIP big-point sign-in. */
class BilibiliAdapter internal constructor(
    private val exchange: ((String, String, JSONObject?, Map<String,String>) -> JSONObject)? = null
) : PlatformAdapter {
    override val platform = Platform.BILIBILI
    override val maxTargetConcurrency = 1
    private val net = NetClient(setOf("api.bilibili.com"))
    private val levelStats = ConcurrentHashMap<String, List<Stat>>()

    override fun validate(credential: String): Identity {
        val cookies = parseCookies(credential)
        val response = get("$BASE/x/web-interface/nav", headers(cookies))
        checkCode(response, "nav")
        val data = response.optJSONObject("data")
            ?: throw AdapterException(TaskStatus.UNKNOWN, "暂时无法确认账号", "nav; missing data")
        val loggedIn = data.opt("isLogin") as? Boolean
            ?: throw AdapterException(TaskStatus.UNKNOWN, "暂时无法确认登录状态", "nav; invalid isLogin")
        if (!loggedIn)
            throw AdapterException(TaskStatus.EXPIRED, "登录已失效，请重新登录", "nav; isLogin=false")
        val uid = positiveUid(data.opt("mid"))
            ?: throw AdapterException(TaskStatus.UNKNOWN, "暂时无法确认账号", "nav; missing mid")
        val vip = data.optJSONObject("vip")
        val vipStatus = strictInt(vip?.opt("status"))
        val levelInfo = data.optJSONObject("level_info")
        val level = strictInt(levelInfo?.opt("current_level"))
        levelStats[uid] = listOfNotNull(
            level?.takeIf { it >= 0 }?.let { Stat("等级", "LV$it") },
            progressStat("经验", levelInfo?.statLong("current_exp"), levelInfo?.statLong("next_exp"))
        )
        val eligibility = when {
            vipStatus == 0 -> "未开通大会员"
            vipStatus != 1 -> "大会员状态未知"
            level == 0 -> "需要先答题转正"
            else -> "大会员 · 每天先看 1 分钟视频再领取"
        }
        return Identity(
            uid,
            data.optString("uname").ifBlank { "哔哩哔哩用户 $uid" },
            listOf(Target(
                id = "vip-level-acceleration",
                type = TYPE,
                label = "大会员等级加速包签到",
                metadata = mapOf(
                    "ownerUserId" to uid,
                    "entry" to ENTRY,
                    "resetZone" to "Asia/Shanghai",
                    "resetHour" to "0",
                    "resetVerified" to "false",
                    "resetEvidence" to "官方确认日限一次；零点边界来自社区资料，执行前每次查询官方状态",
                    "eligibility" to eligibility
                )
            ), Target(BIGPOINT_ID,BIGPOINT_TYPE,if(vipStatus==1) "大积分签到" else "大积分签到（需大会员）",bound=vipStatus==1,
                metadata=mapOf("ownerUserId" to uid,"resetZone" to "Asia/Shanghai","resetHour" to "0","defaultEnabled" to "true"))),
            "UID $uid · $eligibility"
        )
    }

    override fun query(credential: String, target: Target): TaskResult? {
        if (isBigPoint(target)) {
            bigPointIdentityProblem(credential,target)?.let { return it }
            return queryBigPoint(parseCookies(credential))
        }
        requireTarget(target)
        return queryState(parseCookies(credential), target)
    }

    override fun execute(credential: String, target: Target): TaskResult {
        if (isBigPoint(target)) return executeBigPoint(credential,target)
        requireTarget(target)
        val cookies = parseCookies(credential)
        val before = queryState(cookies, target)
        // No write is sent unless the server explicitly says this exact benefit is claimable.
        if (before.status != TaskStatus.PENDING) return before
        val fields = mutableMapOf("csrf" to cookies.getValue("bili_jct"))
        cookies["buvid3"]?.let { fields["buvid"] = it }
        val response = exchange?.invoke("POST","$BASE/x/vip/experience/add",JSONObject(fields),headers(cookies))
            ?: net.postForm("$BASE/x/vip/experience/add", fields, headers(cookies))
        val code = strictInt(response.opt("code"))
        if (code == 69198) {
            val confirmed = queryState(cookies, target)
            return if (confirmed.status == TaskStatus.ALREADY) confirmed
            else TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", "experience/add; code=69198", "")
        }
        checkCode(response, "experience/add")
        val result = parseClaimResponse(response)
        return if (result.status == TaskStatus.SUCCESS) withLevelStats(result, target) else result
    }

    private fun queryState(cookies: Map<String, String>, target: Target): TaskResult {
        val response = get(
            "$BASE/x/vip/web/vip_center/v2?is_selected=false&select_modules=VipExclusive%2CBigPoint%2CComicShow",
            headers(cookies)
        )
        checkCode(response, "vip_center/v2")
        val data = response.optJSONObject("data")
            ?: return TaskResult(TaskStatus.UNKNOWN, "暂时无法查询，请稍后重试", "vip_center/v2; missing data")
        val vip = data.optJSONObject("vip_info")
            ?: return TaskResult(TaskStatus.UNKNOWN, "暂时无法确认大会员状态", "vip_center/v2; missing vip_info")
        membershipProblem(vip, target.metadata["ownerUserId"])?.let { return it }
        val experience = data.optJSONObject("experience")
            ?: return TaskResult(TaskStatus.NOT_CONNECTED, "暂时无法查询等级加速包", "vip_center/v2; no experience module", "")
        val level = strictInt(experience.opt("level"))
            ?: return TaskResult(TaskStatus.UNKNOWN, "暂时无法确认账号等级", "vip_center/v2; invalid experience.level", "")
        if (level < 0) return TaskResult(TaskStatus.UNKNOWN, "暂时无法确认账号等级", "vip_center/v2; invalid experience.level", "")
        if (level == 0)
            return TaskResult(TaskStatus.INELIGIBLE, "需要先答题转正", "vip_center/v2; experience.level=0", "")
        val result = parseExperienceState(experience)
        return if (result.status == TaskStatus.ALREADY) withLevelStats(result, target) else result
    }

    private fun withLevelStats(result: TaskResult, target: Target): TaskResult =
        result.copy(stats = target.metadata["ownerUserId"]?.let { levelStats[it] }.orEmpty())

    private fun get(url: String, headers: Map<String,String>): JSONObject = exchange?.invoke("GET",url,null,headers) ?: net.get(url,headers)

    private fun bigPointIdentityProblem(credential: String, target: Target): TaskResult? {
        if (!target.bound) return TaskResult(TaskStatus.INELIGIBLE,"大积分需要大会员")
        val identity = validate(credential)
        if (identity.userId != target.metadata["ownerUserId"])
            return TaskResult(TaskStatus.EXPIRED,"账号已变化，请在账号页刷新")
        return if (identity.targets.none { it.id==BIGPOINT_ID && it.bound }) TaskResult(TaskStatus.INELIGIBLE,"大积分需要大会员") else null
    }

    private fun queryBigPoint(cookies: Map<String,String>): TaskResult? {
        val time = System.currentTimeMillis()
        val url = "$BASE/x/vip/vip_center/sign_in/three_days_sign".toHttpUrl().newBuilder()
            .addQueryParameter("csrf",cookies.getValue("bili_jct")).addQueryParameter("mobi_app","android")
            .addQueryParameter("platform","android").addQueryParameter("build","8451100").addQueryParameter("disable_rcmd","0")
            .addQueryParameter("t",time.toString()).addQueryParameter("ts",(time/1000).toString()).build().toString()
        return parseBigPointState(get(url,headers(cookies)+mapOf("Referer" to "https://big.bilibili.com/mobile/bigPoint/task",
            "Accept" to "application/json, text/plain, */*")))
    }

    private fun executeBigPoint(credential: String, target: Target): TaskResult {
        bigPointIdentityProblem(credential,target)?.let { return it }
        val cookies = parseCookies(credential)
        val before = queryBigPoint(cookies)
        if (before != null && before.status != TaskStatus.PENDING) return before
        val time = System.currentTimeMillis()
        val url = "$BASE/pgc/activity/score/task/sign2".toHttpUrl().newBuilder()
            .addQueryParameter("mobi_app","android").addQueryParameter("csrf",cookies.getValue("bili_jct"))
            .addQueryParameter("platform","android").build().toString()
        val body = JSONObject().put("device","phone").put("t",time).put("ts",time/1000)
        val headers = headers(cookies)+mapOf("Referer" to "https://big.bilibili.com/mobile/index","Content-Type" to "application/json")
        val response = exchange?.invoke("POST",url,body,headers) ?: net.postRawJson(url,body.toString(),headers)
        val result = parseBigPointSign(response)
        if (result.status != TaskStatus.SUCCESS) return result
        val after = try { queryBigPoint(cookies) }
        catch (e: AdapterException) { return TaskResult(e.status,"暂时无法查询签到状态",Rules.safeDiagnostic(e.diagnostic)) }
        catch (_: IOException) { return TaskResult(TaskStatus.UNKNOWN,"结果未确认，请稍后重试") }
        return if (after?.status == TaskStatus.PENDING) TaskResult(TaskStatus.UNKNOWN,"结果未确认，请稍后重试")
        else result.copy(stats = after?.stats.orEmpty())
    }

    private fun checkCode(response: JSONObject, endpoint: String) {
        val code = strictInt(response.opt("code"))
        when (code) {
            0 -> return
            -101, -401 -> throw AdapterException(TaskStatus.EXPIRED, "登录已失效，请重新登录", "$endpoint; code=$code")
            -111 -> throw AdapterException(TaskStatus.EXPIRED, "登录信息不完整，请重新登录", "$endpoint; code=$code")
            -352, -412, 73319 -> throw AdapterException(TaskStatus.MANUAL, "需要在哔哩哔哩 App 完成验证", "$endpoint; code=$code")
            6034007 -> throw AdapterException(TaskStatus.MANUAL, "请求太频繁，请稍后重试", "$endpoint; code=$code")
            else -> throw AdapterException(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", "$endpoint; code=$code")
        }
    }

    private fun requireTarget(target: Target) {
        if (target.type != TYPE || target.id != "vip-level-acceleration")
            throw AdapterException(TaskStatus.NOT_CONNECTED, "不支持这个任务")
    }

    private fun headers(cookies: Map<String, String>) = mapOf(
        "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
        "Origin" to "https://big.bilibili.com",
        "Referer" to ENTRY
    )

    companion object {
        const val TYPE = "bilibili_vip_level_acceleration"
        const val BIGPOINT_ID = "bilibili:bigpoint"
        const val BIGPOINT_TYPE = "bigpoint"
        private fun isBigPoint(target: Target) = target.id==BIGPOINT_ID && target.type==BIGPOINT_TYPE
        const val ENTRY = "https://big.bilibili.com/mobile/index?location=drawExperienceModule"
        private const val BASE = "https://api.bilibili.com"
        private val integerLiteral = Regex("-?(0|[1-9][0-9]*)")

        internal fun parseBigPointState(response: JSONObject): TaskResult? {
            val code = strictInt(response.opt("code"))
            if (code == -101) throw AdapterException(TaskStatus.EXPIRED,"登录已失效，请重新登录","code=-101")
            if (code != 0) return null
            val data = response.optJSONObject("data") ?: return null
            val signed = data.optJSONObject("three_day_sign")?.opt("signed") as? Boolean ?: return null
            val balance = numericValue(data.optJSONObject("big_point")?.opt("point"))
            return if (signed) TaskResult(TaskStatus.ALREADY,"今日已签到","code=0",
                stats = listOfNotNull(balance?.let { Stat("大积分", it) }))
                else TaskResult(TaskStatus.PENDING,"今日尚未签到","code=0")
        }

        internal fun parseBigPointSign(response: JSONObject): TaskResult {
            val code = strictInt(response.opt("code"))
            val diagnostic = "code=${code ?: "missing"}"
            if (code == -101) return TaskResult(TaskStatus.EXPIRED,"登录已失效，请重新登录",diagnostic)
            val data = response.optJSONObject("data")
            if (code == 0 && data != null && data.length()>0) {
                val score = numericValue(data.opt("score")) ?: numericValue(data.opt("vipScore"))
                val count = numericValue(data.opt("count")); val duration = numericValue(data.opt("duration"))
                val message = "签到成功"+(score?.let { "，获得 $it 积分" } ?: "") +
                    (if(count!=null && duration!=null) "，连续签到 $count/$duration 天" else "")
                return TaskResult(TaskStatus.SUCCESS,message,diagnostic)
            }
            val message = response.optString("message")
            if (message.contains("已签到") || message.contains("已完成")) return TaskResult(TaskStatus.ALREADY,"今日已签到",diagnostic)
            if (message.contains("大会员")) return TaskResult(TaskStatus.INELIGIBLE,"大积分需要大会员",diagnostic)
            return TaskResult(TaskStatus.UNKNOWN,"结果未确认，请稍后重试",diagnostic)
        }

        private fun numericValue(value: Any?): String? = (value as? Number)?.takeIf { it.toDouble().isFinite() }?.toString()

        /** Reject numeric strings, decimal/exponent numbers, overflow and fractional coercion. */
        internal fun strictLong(value: Any?): Long? {
            if (value !is Number) return null
            val literal = value.toString()
            return if (integerLiteral.matches(literal)) literal.toLongOrNull() else null
        }

        internal fun strictInt(value: Any?): Int? = strictLong(value)
            ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

        internal fun positiveUid(value: Any?): String? = strictLong(value)?.takeIf { it > 0 }?.toString()

        internal fun membershipProblem(vip: JSONObject, expectedUid: String?): TaskResult? {
            val mid = strictLong(vip.opt("mid"))
                ?: return TaskResult(TaskStatus.UNKNOWN, "暂时无法确认账号", "vip_center/v2; invalid mid")
            if (mid == 0L) return TaskResult(TaskStatus.EXPIRED, "登录已失效，请重新登录", "vip_center/v2; mid=0")
            if (mid < 0L) return TaskResult(TaskStatus.UNKNOWN, "暂时无法确认账号", "vip_center/v2; invalid mid")
            if (expectedUid.isNullOrBlank() || expectedUid != mid.toString())
                return TaskResult(TaskStatus.MANUAL, "账号已变化，请在账号页刷新", "vip_center/v2; owner mismatch")
            val status = strictInt(vip.opt("vip_status"))
                ?: return TaskResult(TaskStatus.UNKNOWN, "暂时无法确认大会员状态", "vip_center/v2; invalid vip_status")
            return when (status) {
                1 -> null
                0, 2, 3 -> TaskResult(TaskStatus.INELIGIBLE, "需要大会员才能领取", "vip_center/v2; vip_status=$status", "")
                else -> TaskResult(TaskStatus.UNKNOWN, "暂时无法确认大会员状态", "vip_center/v2; vip_status=$status")
            }
        }

        internal fun parseClaimResponse(response: JSONObject): TaskResult {
            val code = strictInt(response.opt("code"))
            val data = response.optJSONObject("data")
            val type = strictInt(data?.opt("type"))
            if (code != 0 || data == null || data.opt("is_grant") !is Boolean || type == null)
                return TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", "experience/add; code=$code; unconfirmed grant fields", "")
            if (!data.getBoolean("is_grant"))
                return TaskResult(TaskStatus.FAILED, "领取结果未确认", "experience/add; code=0; is_grant=false", "")
            return if (type == 0) {
                TaskResult(TaskStatus.SUCCESS, "领取成功", "experience/add; code=0; is_grant=true; type=0", "")
            } else {
                TaskResult(TaskStatus.SUCCESS, "已领取，经验稍后到账", "experience/add; code=0; is_grant=true; type=$type", "")
            }
        }

        internal fun parseExperienceState(experience: JSONObject): TaskResult {
            val state = strictInt(experience.opt("state"))
            if (state == null)
                return TaskResult(TaskStatus.UNKNOWN, "暂时无法确认领取状态", "vip_center/v2; missing experience.state", "")
            return when (state) {
                0 -> TaskResult(TaskStatus.PENDING, "可以领取", "vip_center/v2; experience.state=0", "")
                1 -> TaskResult(TaskStatus.ALREADY, "今日已领取", "vip_center/v2; experience.state=1", "")
                2 -> TaskResult(TaskStatus.INELIGIBLE, "先在 B 站看 1 分钟视频再来领取", "vip_center/v2; experience.state=2", "")
                3 -> TaskResult(TaskStatus.FAILED, "领取失败", "vip_center/v2; experience.state=3", "")
                4 -> TaskResult(TaskStatus.UNKNOWN, "正在处理", "vip_center/v2; experience.state=4", "")
                else -> TaskResult(TaskStatus.UNKNOWN, "结果未确认，请稍后重试", "vip_center/v2; experience.state=$state", "")
            }
        }

        internal fun parseCookies(input: String): Map<String, String> {
            val value = input.trim().removePrefix("Cookie:").trim()
            if (value.length > 32_768 || value.any { it == '\r' || it == '\n' || it.code < 0x20 })
                throw AdapterException(TaskStatus.MANUAL, "请粘贴单行 Cookie，不要包含换行或控制字符")
            val allowed = setOf("SESSDATA", "bili_jct", "buvid3", "DedeUserID", "DedeUserID__ckMd5", "bili_ticket", "bili_ticket_expires")
            val cookies = value.split(';').mapNotNull { item ->
                val separator = item.indexOf('=')
                if (separator <= 0) null else item.substring(0, separator).trim() to item.substring(separator + 1).trim()
            }.filter { it.first in allowed }.toMap()
            if (cookies["SESSDATA"].isNullOrBlank() || cookies["bili_jct"].isNullOrBlank())
                throw AdapterException(TaskStatus.MANUAL, "Cookie 需包含 SESSDATA 和 bili_jct，请按账号页说明重新导入")
            return cookies
        }
    }
}
