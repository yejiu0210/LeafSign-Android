package cn.onetap.checkin.platform

import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.Identity
import cn.onetap.checkin.core.NetClient
import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.core.PlatformAdapter
import cn.onetap.checkin.core.Target
import cn.onetap.checkin.core.TaskResult
import cn.onetap.checkin.core.TaskStatus
import org.json.JSONObject

/** Only the member-center Exclusive Level Acceleration Pack, never VIP points sign-in. */
class BilibiliAdapter : PlatformAdapter {
    override val platform = Platform.BILIBILI
    private val net = NetClient(setOf("api.bilibili.com"))

    override fun validate(credential: String): Identity {
        val cookies = parseCookies(credential)
        val response = net.get("$BASE/x/web-interface/nav", headers(cookies))
        checkCode(response, "nav")
        val data = response.optJSONObject("data")
            ?: throw AdapterException(TaskStatus.UNKNOWN, "身份响应不完整，结果待确认", "nav; missing data")
        val loggedIn = data.opt("isLogin") as? Boolean
            ?: throw AdapterException(TaskStatus.UNKNOWN, "身份登录状态格式未知，无法确认身份", "nav; invalid isLogin")
        if (!loggedIn)
            throw AdapterException(TaskStatus.EXPIRED, "哔哩哔哩登录失效，请更新 Cookie", "nav; isLogin=false")
        val uid = positiveUid(data.opt("mid"))
            ?: throw AdapterException(TaskStatus.UNKNOWN, "未取得账号 UID，无法确认身份", "nav; missing mid")
        val vip = data.optJSONObject("vip")
        val vipStatus = strictInt(vip?.opt("status"))
        val level = strictInt(data.optJSONObject("level_info")?.opt("current_level"))
        val eligibility = when {
            vipStatus == 0 -> "当前未开通有效大会员"
            vipStatus != 1 -> "大会员资格需由官方会员中心确认"
            level == 0 -> "需先在官方完成答题成为正式会员"
            else -> "有效大会员；领取前需在官方观看视频 1 分钟"
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
            )),
            "UID $uid · $eligibility；只领取等级加速包，不执行积分签到或自动观看"
        )
    }

    override fun query(credential: String, target: Target): TaskResult? {
        requireTarget(target)
        return queryState(parseCookies(credential), target)
    }

    override fun execute(credential: String, target: Target): TaskResult {
        requireTarget(target)
        val cookies = parseCookies(credential)
        val before = queryState(cookies, target)
        // No write is sent unless the server explicitly says this exact benefit is claimable.
        if (before.status != TaskStatus.PENDING) return before
        val fields = mutableMapOf("csrf" to cookies.getValue("bili_jct"))
        cookies["buvid3"]?.let { fields["buvid"] = it }
        val response = net.postForm("$BASE/x/vip/experience/add", fields, headers(cookies))
        val code = strictInt(response.opt("code"))
        if (code == 69198) {
            val confirmed = queryState(cookies, target)
            return if (confirmed.status == TaskStatus.ALREADY) confirmed
            else TaskResult(TaskStatus.UNKNOWN, "平台提示经验已领取，但当前状态尚未确认", "experience/add; code=69198", "到账状态待确认")
        }
        checkCode(response, "experience/add")
        return parseClaimResponse(response)
    }

    private fun queryState(cookies: Map<String, String>, target: Target): TaskResult {
        val response = net.get(
            "$BASE/x/vip/web/vip_center/v2?is_selected=false&select_modules=VipExclusive%2CBigPoint%2CComicShow",
            headers(cookies)
        )
        checkCode(response, "vip_center/v2")
        val data = response.optJSONObject("data")
            ?: return TaskResult(TaskStatus.UNKNOWN, "会员中心响应不完整", "vip_center/v2; missing data")
        val vip = data.optJSONObject("vip_info")
            ?: return TaskResult(TaskStatus.UNKNOWN, "无法确认大会员资格", "vip_center/v2; missing vip_info")
        membershipProblem(vip, target.metadata["ownerUserId"])?.let { return it }
        val experience = data.optJSONObject("experience")
            ?: return TaskResult(TaskStatus.NOT_CONNECTED, "官方未返回等级加速包模块，请在官方入口确认后提供页面说明", "vip_center/v2; no experience module", "未查询到发放状态")
        val level = strictInt(experience.opt("level"))
            ?: return TaskResult(TaskStatus.UNKNOWN, "正式会员等级字段缺失或格式未知", "vip_center/v2; invalid experience.level", "到账状态待确认")
        if (level < 0) return TaskResult(TaskStatus.UNKNOWN, "正式会员等级字段未知", "vip_center/v2; invalid experience.level", "到账状态待确认")
        if (level == 0)
            return TaskResult(TaskStatus.INELIGIBLE, "需先在官方完成答题成为正式会员", "vip_center/v2; experience.level=0", "未领取")
        return parseExperienceState(experience)
    }

    private fun checkCode(response: JSONObject, endpoint: String) {
        val code = strictInt(response.opt("code"))
        when (code) {
            0 -> return
            -101, -401 -> throw AdapterException(TaskStatus.EXPIRED, "哔哩哔哩登录失效，请更新 Cookie", "$endpoint; code=$code")
            -111 -> throw AdapterException(TaskStatus.EXPIRED, "Cookie 中的校验凭证无效，请更新完整 Cookie", "$endpoint; code=$code")
            -352, -412, 73319 -> throw AdapterException(TaskStatus.MANUAL, "平台需要手动验证，请在官方入口完成后再试", "$endpoint; code=$code")
            6034007 -> throw AdapterException(TaskStatus.MANUAL, "平台限制请求频率，请稍后手动重试", "$endpoint; code=$code")
            else -> throw AdapterException(TaskStatus.UNKNOWN, "平台返回未识别业务结果，结果待确认", "$endpoint; code=$code")
        }
    }

    private fun requireTarget(target: Target) {
        if (target.type != TYPE || target.id != "vip-level-acceleration")
            throw AdapterException(TaskStatus.NOT_CONNECTED, "不支持的哔哩哔哩任务类型")
    }

    private fun headers(cookies: Map<String, String>) = mapOf(
        "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
        "Origin" to "https://big.bilibili.com",
        "Referer" to ENTRY
    )

    companion object {
        const val TYPE = "bilibili_vip_level_acceleration"
        const val ENTRY = "https://big.bilibili.com/mobile/index?location=drawExperienceModule"
        private const val BASE = "https://api.bilibili.com"
        private val integerLiteral = Regex("-?(0|[1-9][0-9]*)")

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
                ?: return TaskResult(TaskStatus.UNKNOWN, "会员中心 UID 格式未知，无法确认身份", "vip_center/v2; invalid mid")
            if (mid == 0L) return TaskResult(TaskStatus.EXPIRED, "哔哩哔哩登录失效，请更新 Cookie", "vip_center/v2; mid=0")
            if (mid < 0L) return TaskResult(TaskStatus.UNKNOWN, "会员中心 UID 无效，无法确认身份", "vip_center/v2; invalid mid")
            if (expectedUid.isNullOrBlank() || expectedUid != mid.toString())
                return TaskResult(TaskStatus.MANUAL, "当前凭证的 UID 与任务账号不一致，请重新校验账号", "vip_center/v2; owner mismatch")
            val status = strictInt(vip.opt("vip_status"))
                ?: return TaskResult(TaskStatus.UNKNOWN, "大会员资格字段缺失或格式未知", "vip_center/v2; invalid vip_status")
            return when (status) {
                1 -> null
                0, 2, 3 -> TaskResult(TaskStatus.INELIGIBLE, "当前没有可领取此权益的有效大会员资格", "vip_center/v2; vip_status=$status", "未领取")
                else -> TaskResult(TaskStatus.UNKNOWN, "平台返回未识别的大会员资格状态", "vip_center/v2; vip_status=$status")
            }
        }

        internal fun parseClaimResponse(response: JSONObject): TaskResult {
            val code = strictInt(response.opt("code"))
            val data = response.optJSONObject("data")
            val type = strictInt(data?.opt("type"))
            if (code != 0 || data == null || data.opt("is_grant") !is Boolean || type == null)
                return TaskResult(TaskStatus.UNKNOWN, "领取响应字段不完整，结果待确认", "experience/add; code=$code; unconfirmed grant fields", "到账状态待确认")
            if (!data.getBoolean("is_grant"))
                return TaskResult(TaskStatus.FAILED, "平台未确认领取成功", "experience/add; code=0; is_grant=false", "未确认发放")
            return if (type == 0) {
                TaskResult(TaskStatus.SUCCESS, "等级加速包领取成功", "experience/add; code=0; is_grant=true; type=0", "官方确认经验已领取；未额外核对经验流水")
            } else {
                TaskResult(TaskStatus.SUCCESS, "等级加速包领取已受理", "experience/add; code=0; is_grant=true; type=$type", "平台提示系统维护，经验稍后发放；尚未确认到账")
            }
        }

        internal fun parseExperienceState(experience: JSONObject): TaskResult {
            val state = strictInt(experience.opt("state"))
            if (state == null)
                return TaskResult(TaskStatus.UNKNOWN, "等级加速包状态字段缺失", "vip_center/v2; missing experience.state", "到账状态待确认")
            return when (state) {
                0 -> TaskResult(TaskStatus.PENDING, "已满足观看条件，可以领取等级加速包", "vip_center/v2; experience.state=0", "尚未领取")
                1 -> TaskResult(TaskStatus.ALREADY, "平台显示今日等级加速包已领取", "vip_center/v2; experience.state=1", "平台显示已领取；未额外核对经验流水")
                2 -> TaskResult(TaskStatus.INELIGIBLE, "请先在哔哩哔哩手动观看视频 1 分钟，然后重试", "vip_center/v2; experience.state=2", "尚未满足观看条件，未领取")
                3 -> TaskResult(TaskStatus.FAILED, "平台显示等级加速包领取失败", "vip_center/v2; experience.state=3", "未确认发放")
                4 -> TaskResult(TaskStatus.UNKNOWN, "平台仍在处理等级加速包，结果待确认", "vip_center/v2; experience.state=4", "到账状态待确认")
                else -> TaskResult(TaskStatus.UNKNOWN, "未识别的等级加速包状态，结果待确认", "vip_center/v2; experience.state=$state", "到账状态待确认")
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
