package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Direct platform requests. Protocol references and their licenses are in docs/integration-music-tieba.md. */
class NeteaseAdapter internal constructor(
    private val exchange: ((String, JSONObject, Map<String, String>, Boolean) -> JSONObject)? = null
) : PlatformAdapter {
    override val platform = Platform.NETEASE
    private val net = NetClient(setOf("music.163.com", "interfacepc.music.163.com"))
    private val deviceId = UUID.randomUUID().toString().replace("-", "")

    override fun validate(credential: String): Identity {
        val cookie = credentials(credential)
        val result = request("/nuser/account/get", JSONObject(), cookie)
        val code = code(result)
        if (code != 200) throw rejection(result)
        val account = result.optJSONObject("account")
        val profile = result.optJSONObject("profile")
        val uid = account?.optString("id")?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
            ?: throw AdapterException(TaskStatus.EXPIRED, "未取得已登录的网易云账号，请重新导入 MUSIC_U")
        val profileId = profile?.optString("userId")
        if (!profileId.isNullOrEmpty() && profileId != uid)
            throw AdapterException(TaskStatus.UNKNOWN, "网易云账号身份字段不一致，已停止执行")
        return Identity(uid, profile?.optString("nickname")?.takeIf { it.isNotBlank() } ?: "网易云用户 $uid",
            listOf(Target(TARGET_ID, TARGET_TYPE, "黑胶乐签", metadata = mapOf(
                "ownerUserId" to uid, "resetZone" to "Asia/Shanghai", "resetHour" to "0",
                "resetVerified" to "false", "resetEvidence" to "每日业务；精确边界待真实账号跨日确认"
            ))), "已校验网易云 UID；执行黑胶乐签并查询服务端今日状态，不执行播放、领奖或购买")
    }

    override fun execute(credential: String, target: Target): TaskResult {
        if (!isVipTarget(target))
            return TaskResult(TaskStatus.UNKNOWN, "签到任务类型不匹配，已停止")
        val identity = validate(credential)
        if (target.metadata["ownerUserId"] != identity.userId)
            return TaskResult(TaskStatus.EXPIRED, "凭证对应账号已改变，请重新校验账号并选择任务")
        val cookie = credentials(credential)
        val before = readVipState(cookie)
        if (before.status != TaskStatus.PENDING) return before

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
        val after = readVipState(cookie)
        return when (after.status) {
            TaskStatus.ALREADY -> if(code(detail)==200) TaskResult(TaskStatus.SUCCESS, "黑胶乐签成功，服务端已确认今日打卡",
                "netease vipMusicSign code=$taskCode; detail code=200")
                else TaskResult(TaskStatus.ALREADY, "服务端确认今日已打卡；本次签到明细返回未确认，未宣称本次请求成功",
                    "netease vipMusicSign code=$taskCode; detail code=${code(detail) ?: "missing"}")
            TaskStatus.PENDING -> TaskResult(TaskStatus.UNKNOWN, "请求已处理，服务端尚未确认今日打卡；请稍后查询",
                "netease vipMusicSign taskCode=$taskCode detailCode=${code(detail) ?: "missing"}; today=true sign=false")
            else -> after
        }
    }

    override fun query(credential: String, target: Target): TaskResult {
        if (!isVipTarget(target)) return TaskResult(TaskStatus.UNKNOWN, "签到任务类型不匹配，已停止")
        val identity = validate(credential)
        if (target.metadata["ownerUserId"] != identity.userId)
            return TaskResult(TaskStatus.EXPIRED, "凭证对应账号已改变，请重新校验账号并选择任务")
        return readVipState(credentials(credential))
    }

    private fun readVipState(cookie: Map<String, String>): TaskResult = parseVipState(request(
        "/vipnewcenter/app/minidesk/music/sign/pc", JSONObject().put("type", 1), cookie, true))

    private fun request(path: String, data: JSONObject, cookie: Map<String, String>, eapi: Boolean = false): JSONObject {
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
            return net.postForm("https://interfacepc.music.163.com/eapi$path",
                encryptEapi("/api$path", data.toString()), mapOf(
                    "Cookie" to eapiCookie(header),
                    "Referer" to "https://music.163.com/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"
                ))
        }
        data.put("csrf_token", cookie["__csrf"].orEmpty())
        val body = encryptWeapi(data.toString())
        return net.postForm("https://music.163.com/weapi$path", body, mapOf(
            "Cookie" to cookie.entries.joinToString("; ") { "${it.key}=${it.value}" },
            "Referer" to "https://music.163.com/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
        ))
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
            throw AdapterException(TaskStatus.EXPIRED, "Cookie 中缺少有效 MUSIC_U，请从已登录的官网重新复制")
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
            TaskStatus.EXPIRED -> "网易云登录失效，请更新 MUSIC_U"
            TaskStatus.MANUAL -> "网易云要求手动验证，请前往官方 App 完成验证"
            else -> "网易云返回未识别的业务结果，请在官方入口确认"
        }, "netease code=${code ?: "missing"}")
    }

    companion object {
        const val TARGET_ID = "netease:vip:music-sign"
        const val TARGET_TYPE = "vip-music-sign"
        private fun isVipTarget(target: Target) = target.id == TARGET_ID && target.type == TARGET_TYPE

        /** Request results never prove the VIP music-sign's completion. */
        fun parseSignResult(result: JSONObject): TaskResult = when (code(result)) {
            301 -> TaskResult(TaskStatus.EXPIRED, "网易云登录失效，请更新 MUSIC_U", "netease vipMusicSign code=301")
            else -> {
                val msg = result.optString("msg", result.optString("message"))
                if (msg.contains("验证码") || msg.contains("安全验证") || msg.contains("操作频繁"))
                    TaskResult(TaskStatus.MANUAL, "请在网易云官方入口完成验证", "netease vipMusicSign code=${code(result) ?: "missing"}")
                else TaskResult(TaskStatus.UNKNOWN, "黑胶乐签结果尚未确认，请在官方入口确认", "netease vipMusicSign code=${code(result) ?: "missing"}")
            }
        }

        fun parseVipState(result: JSONObject): TaskResult {
            val error = parseSignResult(result)
            if (code(result) != 200 || error.status in setOf(TaskStatus.EXPIRED, TaskStatus.MANUAL)) return error
            val list = result.optJSONObject("data")?.optJSONArray("signInfoList")
                ?: return TaskResult(TaskStatus.UNKNOWN, "服务端未返回黑胶乐签今日状态，结果待确认", "netease vipMusicSign missing signInfoList")
            val today = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .filter { it.opt("today") == true }
            if (today.size != 1 || today[0].opt("sign") !is Boolean)
                return TaskResult(TaskStatus.UNKNOWN, "服务端今日乐签字段无法确认，结果待确认", "netease vipMusicSign ambiguous today/sign")
            return if (today[0].getBoolean("sign"))
                TaskResult(TaskStatus.ALREADY, "黑胶乐签今日已打卡（服务端确认）", "netease vipMusicSign today=true sign=true")
            else TaskResult(TaskStatus.PENDING, "黑胶乐签今日尚未打卡（服务端确认）", "netease vipMusicSign today=true sign=false")
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
