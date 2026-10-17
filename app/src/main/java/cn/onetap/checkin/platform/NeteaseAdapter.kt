package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import org.json.JSONObject
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Direct platform requests. Protocol references and their licenses are in docs/integration-music-tieba.md. */
class NeteaseAdapter : PlatformAdapter {
    override val platform = Platform.NETEASE
    private val net = NetClient(setOf("music.163.com"))

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
            listOf(Target("netease:daily:android", "daily", "每日签到（Android）", metadata = mapOf(
                "ownerUserId" to uid, "resetZone" to "Asia/Shanghai", "resetHour" to "0",
                "resetVerified" to "false", "resetEvidence" to "每日业务；精确边界待真实账号跨日确认"
            ))), "已校验网易云 UID；仅执行每日签到 type=0，不执行播放或其他任务")
    }

    override fun execute(credential: String, target: Target): TaskResult {
        if (target.type != "daily" || target.id != "netease:daily:android")
            return TaskResult(TaskStatus.UNKNOWN, "签到任务类型不匹配，已停止")
        val identity = validate(credential)
        if (target.metadata["ownerUserId"] != identity.userId)
            return TaskResult(TaskStatus.EXPIRED, "凭证对应账号已改变，请重新校验账号并选择任务")
        val result = request("/point/dailyTask", JSONObject().put("type", 0), credentials(credential))
        return parseSignResult(result)
    }

    // No independently verified read-only status API for this exact dailyTask(type=0).
    // A timeout is UNKNOWN and must not automatically repeat the write.
    override fun query(credential: String, target: Target): TaskResult? = null

    private fun request(path: String, data: JSONObject, cookie: Map<String, String>): JSONObject {
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
        /** Pure response parser; HTTP success alone never reaches SUCCESS. */
        fun parseSignResult(result: JSONObject): TaskResult = when (code(result)) {
            200 -> TaskResult(TaskStatus.SUCCESS, "网易云每日签到成功", "netease dailyTask code=200")
            -2 -> TaskResult(TaskStatus.ALREADY, "网易云明确返回重复签到", "netease dailyTask code=-2")
            301 -> TaskResult(TaskStatus.EXPIRED, "网易云登录失效，请更新 MUSIC_U", "netease dailyTask code=301")
            else -> {
                val msg = result.optString("msg", result.optString("message"))
                if (msg.contains("验证码") || msg.contains("安全验证") || msg.contains("操作频繁"))
                    TaskResult(TaskStatus.MANUAL, "请在网易云官方入口完成验证", "netease dailyTask code=${code(result) ?: "missing"}")
                else TaskResult(TaskStatus.UNKNOWN, "网易云返回未识别结果，请在官方入口确认", "netease dailyTask code=${code(result) ?: "missing"}")
            }
        }

        private fun code(result: JSONObject): Int? = result.opt("code")?.toString()?.toIntOrNull()

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
